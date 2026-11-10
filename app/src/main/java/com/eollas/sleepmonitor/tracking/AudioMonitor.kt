package com.eollas.sleepmonitor.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.eollas.sleepmonitor.data.SoundCategory
import com.eollas.sleepmonitor.data.SoundEvent
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Écoute le micro toute la nuit. Rien n'est gardé tant que c'est calme :
 * seuls les extraits des bruits détectés sont enregistrés (fichiers privés de l'app),
 * puis triés le matin pour ne garder que les moments forts.
 */
class AudioMonitor(
    private val ctx: Context,
    private val nightId: Long,
    private val sensitivity: Int,
    private val onEvent: (SoundEvent, ShortArray?) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var classifier: SoundClassifier? = null

    // Historique circulaire (pré-enregistrement, pauses respiratoires, fenêtre d'analyse)
    private val history = ShortArray(SR * HISTORY_S)
    private var histPos = 0
    private var histFilled = 0
    private val window = FloatArray(SoundClassifier.WINDOW)

    private var floor = 40f
    private val recentDb = ArrayDeque<Float>()
    private var sinceInfer = 0

    private val lock = Any()
    private var energySum = 0.0
    private var energyCount = 0
    private var epochMax = 0f

    private var snoreClips = 0
    private var otherClips = 0

    // Respiration : sert à repérer « ronflement → silence → reprise brusque »
    private val snoreTimes = ArrayDeque<Long>()
    private var lastBreath = 0L
    private var apnea: PendingApnea? = null

    private class Current(
        val category: SoundCategory,
        var index: Int,
        val start: Long,
        var last: Long,
        var confidence: Float,
        var peakDb: Float,
        val audio: ShortBuilder,
    )
    private var current: Current? = null

    private class PendingApnea(val start: Long, val resume: Long, val peakDb: Float, val audio: ShortBuilder)

    fun start() {
        running = true
        thread = Thread({ loop() }, "audio").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
        closeCurrent()
        flushApnea(force = true)
        classifier?.close()
        classifier = null
    }

    /** Niveau sonore moyen et maximal depuis le dernier appel. */
    fun takeEpoch(): Pair<Float, Float> = synchronized(lock) {
        val avg = if (energyCount > 0) toDb(sqrt(energySum / energyCount)) else 0f
        val r = avg to epochMax
        energySum = 0.0; energyCount = 0; epochMax = 0f
        r
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, CHUNK * 8),
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Micro indisponible", t); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        classifier = runCatching { SoundClassifier(ctx) }.onFailure { Log.e(TAG, "Modèle", it) }.getOrNull()
        val chunk = ShortArray(CHUNK)
        rec.startRecording()
        try {
            while (running) {
                var read = 0
                while (read < CHUNK && running) {
                    val r = rec.read(chunk, read, CHUNK - read)
                    if (r <= 0) break
                    read += r
                }
                if (read < CHUNK) continue
                process(chunk, System.currentTimeMillis())
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    private fun process(chunk: ShortArray, now: Long) {
        var e = 0.0
        for (s in chunk) e += s.toDouble() * s
        val rms = sqrt(e / chunk.size)
        val db = toDb(rms)
        synchronized(lock) {
            energySum += rms * rms; energyCount++
            if (db > epochMax) epochMax = db
        }
        onLevel(db)
        // Bruit de fond : descend vite, remonte lentement
        floor = if (db < floor) floor + (db - floor) * 0.1f else floor + 0.003f
        recentDb.addLast(db); if (recentDb.size > 10) recentDb.removeFirst()

        for (s in chunk) {
            history[histPos] = s
            histPos = (histPos + 1) % history.size
        }
        histFilled = minOf(history.size, histFilled + chunk.size)
        current?.let { c ->
            val maxLen = if (c.category == SoundCategory.SNORING) MAX_SNORE_CLIP else MAX_CLIP
            if (c.audio.size < maxLen) c.audio.add(chunk)
        }
        apnea?.audio?.add(chunk)
        flushApnea(force = false, now = now)

        sinceInfer += chunk.size
        if (sinceInfer < HOP) return
        sinceInfer = 0
        if (histFilled < SoundClassifier.WINDOW) return

        val peak = recentDb.maxOrNull() ?: db
        val above = peak - floor
        var detection: SoundRules.Detection? = null
        var scores: FloatArray? = null
        val cls = classifier
        if (cls != null && above >= gate()) {
            val start = (histPos - SoundClassifier.WINDOW + history.size) % history.size
            for (i in 0 until SoundClassifier.WINDOW) window[i] = history[(start + i) % history.size] / 32768f
            scores = runCatching { cls.classify(window) }.getOrNull()
            detection = scores?.let { SoundRules.decide(it, above, sensitivity) }
        }
        checkBreathing(scores, detection, above, peak, now)
        handle(detection, peak, now)
    }

    private fun gate() = when (sensitivity) { 0 -> 7f; 2 -> 3f; else -> 5f }

    /**
     * Pause respiratoire possible : on ronflait (au moins 3 ronflements dans la minute),
     * puis plus aucun souffle pendant 10 à 60 s, puis une reprise brusque
     * (halètement, reniflement fort ou ronflement bruyant).
     */
    private fun checkBreathing(scores: FloatArray?, d: SoundRules.Detection?, above: Float, peak: Float, now: Long) {
        while (snoreTimes.isNotEmpty() && now - snoreTimes.first() > 90_000L) snoreTimes.removeFirst()
        if (scores == null) return
        val snore = d?.category == SoundCategory.SNORING
        val breath = snore || scores[SoundRules.BREATHING] >= 0.2f ||
            scores[SoundRules.GASP] >= 0.1f || scores[SoundRules.SNORT] >= 0.15f
        if (!breath) return
        val gap = now - lastBreath
        val snoringBefore = snoreTimes.count { it in (lastBreath - 60_000L)..lastBreath } >= 3
        val brusque = scores[SoundRules.GASP] >= 0.1f || scores[SoundRules.SNORT] >= 0.15f ||
            (snore && above >= 12f)
        if (lastBreath > 0 && gap in MIN_PAUSE_MS..MAX_PAUSE_MS && snoringBefore && brusque && apnea == null) {
            // On garde 5 s avant la pause, toute la pause, et 4 s après la reprise
            val wanted = ((gap + 5_000L) * SR / 1000).toInt().coerceAtMost(histFilled)
            val b = ShortBuilder()
            val from = (histPos - wanted + history.size) % history.size
            for (i in 0 until wanted) b.add(history[(from + i) % history.size])
            apnea = PendingApnea(lastBreath, now, peak, b)
        }
        if (snore) snoreTimes.addLast(now)
        lastBreath = now
    }

    private fun flushApnea(force: Boolean, now: Long = System.currentTimeMillis()) {
        val a = apnea ?: return
        if (!force && now - a.resume < 4_000L) return
        apnea = null
        val secs = ((a.resume - a.start) / 1000).toInt()
        val ev = SoundEvent(
            nightId = nightId, start = a.start, end = a.resume, category = SoundCategory.APNEA.id,
            label = "Pause respiratoire ($secs s)", confidence = 1f, peakDb = a.peakDb,
        )
        onEvent(ev, a.audio.toArray())
    }

    private fun handle(d: SoundRules.Detection?, peak: Float, now: Long) {
        val c = current
        if (d != null) {
            if (c != null && c.category == d.category && now - c.start < MAX_EVENT_MS) {
                c.last = now
                c.peakDb = max(c.peakDb, peak)
                if (d.confidence > c.confidence) { c.confidence = d.confidence; c.index = d.index }
            } else {
                closeCurrent()
                // On garde 2,5 s avant la détection pour entendre le début du bruit
                val pre = minOf(histFilled, (SR * 2.5).toInt())
                val b = ShortBuilder()
                val from = (histPos - pre + history.size) % history.size
                for (i in 0 until pre) b.add(history[(from + i) % history.size])
                current = Current(d.category, d.index, now - 1000, now, d.confidence, peak, b)
            }
        } else if (c != null && now - c.last > END_GAP_MS) {
            closeCurrent()
        }
    }

    private fun closeCurrent() {
        val c = current ?: return
        current = null
        val end = c.last + 1000
        // Extraits provisoires : le tri du matin ne garde que les plus intéressants
        val keepClip = if (c.category == SoundCategory.SNORING) snoreClips++ < MAX_SNORE_CLIPS
        else otherClips++ < MAX_OTHER_CLIPS
        val wanted = ((end + 500 - (c.start - 1500)) * SR / 1000).toInt()
        val pcm = if (keepClip) c.audio.toArray(minOf(wanted.coerceAtLeast(SR), c.audio.size)) else null
        val ev = SoundEvent(
            nightId = nightId, start = c.start, end = end, category = c.category.id,
            label = Labels.of(c.index), confidence = c.confidence, peakDb = c.peakDb,
        )
        onEvent(ev, pcm)
    }

    companion object {
        const val SR = 16_000
        const val CHUNK = 1_600            // 0,1 s
        const val HOP = 8_000              // analyse toutes les 0,5 s
        const val HISTORY_S = 70           // 70 s d'historique (pauses jusqu'à 60 s)
        const val MAX_CLIP = SR * 60       // extrait de 60 s au maximum
        const val MAX_SNORE_CLIP = SR * 30 // ronflement : 30 s suffisent
        const val MAX_EVENT_MS = 5 * 60_000L
        const val END_GAP_MS = 4_000L
        const val MIN_PAUSE_MS = 10_000L
        const val MAX_PAUSE_MS = 60_000L
        const val MAX_SNORE_CLIPS = 200
        const val MAX_OTHER_CLIPS = 200
        private const val TAG = "AudioMonitor"

        /** Niveau approximatif en dB (calé pour un micro de téléphone). */
        fun toDb(rms: Double): Float = (20 * log10(rms / 32768.0 + 1e-9) + 100).toFloat().coerceIn(0f, 120f)
    }
}

/** Tableau extensible d'échantillons audio. */
class ShortBuilder {
    private var data = ShortArray(SR_INIT)
    var size = 0; private set
    fun add(s: Short) { ensure(size + 1); data[size++] = s }
    fun add(arr: ShortArray) { ensure(size + arr.size); arr.copyInto(data, size); size += arr.size }
    private fun ensure(n: Int) { if (n > data.size) data = data.copyOf(maxOf(n, data.size * 2)) }
    fun toArray(n: Int = size): ShortArray = data.copyOf(n.coerceAtMost(size))
    private companion object { const val SR_INIT = 16_000 * 8 }
}
