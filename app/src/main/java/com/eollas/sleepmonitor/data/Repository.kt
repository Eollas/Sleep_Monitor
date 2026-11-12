package com.eollas.sleepmonitor.data

import android.content.Context
import com.eollas.sleepmonitor.analysis.Highlights
import com.eollas.sleepmonitor.analysis.SleepAnalyzer
import com.eollas.sleepmonitor.health.HealthConnectManager
import com.eollas.sleepmonitor.tracking.Live
import java.io.File

/** Point d'accès unique aux données (base locale, extraits, Health Connect). */
class Repository(private val ctx: Context) {
    val dao = SleepDatabase.get(ctx).dao()
    val settings = SettingsRepo(ctx)
    val health = HealthConnectManager(ctx)

    val clipsRoot: File get() = File(ctx.filesDir, "clips")
    fun clipFile(nightId: Long, eventStart: Long) = File(clipsRoot, "$nightId/$eventStart.m4a")

    /** Calcule le récapitulatif d'une nuit terminée. */
    suspend fun finish(nightId: Long, endTime: Long, interrupted: Boolean = false): Night? {
        val night = dao.night(nightId) ?: return null
        val withExt = readExternal(night.copy(endTime = endTime))
        return analyze(withExt, interrupted || night.interrupted)
    }

    /**
     * Analyse complète : phases, sons pendant le sommeil, choix des moments forts,
     * export Health Connect. Peut être relancée (nouvelle version de l'analyse).
     */
    private suspend fun analyze(night: Night, interrupted: Boolean): Night {
        val end = night.endTime ?: night.startTime
        val epochs = dao.epochs(night.id)
        val all = dao.events(night.id)
        // Anciennes détections trop vagues (« Animal »…) : ignorées
        val events = all.filterNot { it.category == SoundCategory.NOISE.id && it.label in LEGACY_IGNORED }
        val s = settings.current()

        // Les heures Mi Fitness (montre, rythme cardiaque) encadrent notre estimation
        val minOnset = night.extStart?.takeIf { it > night.startTime && it < end }
        val maxWake = night.extEnd?.takeIf { it < end && it > (minOnset ?: night.startTime) }
        val res = SleepAnalyzer.analyze(
            LongArray(epochs.size) { epochs[it].t }, FloatArray(epochs.size) { epochs[it].activity },
            night.startTime, end, s.sensitivity, flags(epochs, events), minOnset, maxWake,
        )
        val staged = epochs.mapIndexed { i, e -> e.copy(stage = res.stages.getOrElse(i) { Stage.AWAKE }) }
        dao.insertEpochs(staged)

        // Seuls les sons pendant le sommeil comptent (paroles avant de dormir ≠ somniloquie)
        val asleep = events.filter { Highlights.inSleep(it, res.onset, res.wake) }
        fun count(c: SoundCategory) = asleep.count { it.category == c.id }
        val snoreMs = asleep.filter { it.category == SoundCategory.SNORING.id }.sumOf { it.end - it.start }

        // Moments forts : on garde 10 à 20 extraits, les autres sont effacés
        val keep = Highlights.select(events, res.onset, res.wake) { e -> e.clipPath?.let { File(it).exists() } == true }
        for (e in all) {
            val h = e.id in keep
            if (h != e.highlight) dao.setHighlight(e.id, h)
            if (!h && e.clipPath != null) {
                File(e.clipPath).delete()
                dao.clearClip(e.id)
            }
        }

        val dbs = epochs.map { it.db }.filter { it > 0f }
        var updated = night.copy(
            sleepOnset = res.onset,
            wakeTime = res.wake,
            totalSleepMin = res.sleepMin,
            deepMin = res.deepMin,
            lightMin = res.lightMin,
            awakeMin = res.awakeMin,
            latencyMin = res.latencyMin,
            awakenings = res.awakenings,
            efficiency = res.efficiency,
            score = res.score,
            snoreMin = (snoreMs / 60_000).toInt(),
            talkCount = count(SoundCategory.SPEECH),
            coughCount = count(SoundCategory.COUGH),
            noiseCount = count(SoundCategory.NOISE),
            apneaCount = count(SoundCategory.APNEA),
            avgDb = if (dbs.isEmpty()) 0f else dbs.average().toFloat(),
            finished = true,
            interrupted = interrupted,
            analysisVersion = SleepAnalyzer.VERSION,
        )
        dao.updateNight(updated)
        if (s.hcWrite && !updated.hcExported && runCatching { health.writeSleep(updated, staged) }.getOrDefault(false)) {
            updated = updated.copy(hcExported = true)
            dao.updateNight(updated)
        }
        cleanClips(s.retentionDays)
        return updated
    }

    /** Indices par tranche : écran allumé, paroles / bruits (activité), ronflement. */
    private fun flags(epochs: List<Epoch>, events: List<SoundEvent>): SleepAnalyzer.Flags {
        val n = epochs.size
        val busy = BooleanArray(n)
        val snore = BooleanArray(n)
        if (n > 0) {
            val t0 = epochs[0].t
            for (e in events) {
                val cat = SoundCategory.of(e.category)
                val from = ((e.start - t0) / SleepAnalyzer.EPOCH_MS).toInt().coerceAtLeast(0)
                val to = ((e.end - t0) / SleepAnalyzer.EPOCH_MS).toInt().coerceAtMost(n - 1)
                for (i in from..to) {
                    when (cat) {
                        SoundCategory.SPEECH, SoundCategory.NOISE -> busy[i] = true
                        SoundCategory.SNORING, SoundCategory.APNEA -> snore[i] = true
                        else -> {}
                    }
                }
            }
        }
        return SleepAnalyzer.Flags(BooleanArray(n) { epochs[it].screenOn }, busy, snore)
    }

    /** Lit la nuit Mi Fitness / Health Connect et le rythme cardiaque (sans enregistrer). */
    private suspend fun readExternal(night: Night): Night {
        if (!health.available) return night
        val end = night.endTime ?: return night
        val ext = runCatching { health.readExternalSleep(night.startTime, end) }.getOrNull()
        val hrFrom = ext?.start ?: night.sleepOnset ?: night.startTime
        val hrTo = ext?.end ?: night.wakeTime ?: end
        val hr = runCatching { health.readHeartRate(hrFrom, hrTo) }.getOrNull()
        if (ext == null && hr == null) return night
        return night.copy(
            extSource = ext?.source ?: night.extSource,
            extSleepMin = ext?.sleepMin ?: night.extSleepMin,
            extDeepMin = ext?.deepMin ?: night.extDeepMin,
            extLightMin = ext?.lightMin ?: night.extLightMin,
            extRemMin = ext?.remMin ?: night.extRemMin,
            extAwakeMin = ext?.awakeMin ?: night.extAwakeMin,
            extStart = ext?.start ?: night.extStart,
            extEnd = ext?.end ?: night.extEnd,
            hrAvg = hr?.first ?: night.hrAvg,
            hrMin = hr?.second ?: night.hrMin,
        )
    }

    /** Récupère Mi Fitness ; si ses horaires ont changé, la nuit est recalculée. */
    suspend fun refreshExternal(night: Night): Night {
        val n = readExternal(night)
        if (n == night) return night
        return if (n.extStart != night.extStart || n.extEnd != night.extEnd) analyze(n, n.interrupted)
        else n.also { dao.updateNight(it) }
    }

    /** Recalcule les nuits analysées avec une ancienne version de l'app. */
    suspend fun reanalyzeOutdated() {
        for (n in dao.outdated(SleepAnalyzer.VERSION)) {
            runCatching { analyze(readExternal(n), n.interrupted) }
        }
    }

    /** Termine une nuit coupée (téléphone redémarré, app arrêtée par le système…). */
    suspend fun recoverInterrupted() {
        if (Live.state.value.running) return
        reanalyzeOutdated()
        for (n in dao.unfinished()) {
            val epochs = dao.epochs(n.id)
            if (epochs.size < 10) {
                deleteNight(n.id)
            } else {
                finish(n.id, epochs.last().t + SleepAnalyzer.EPOCH_MS, interrupted = true)
            }
        }
    }

    /** Supprime les extraits plus vieux que la durée de conservation choisie. */
    suspend fun cleanClips(retentionDays: Int) {
        if (retentionDays <= 0) return
        val before = System.currentTimeMillis() - retentionDays * 86_400_000L
        for (e in dao.clipsBefore(before)) {
            e.clipPath?.let { File(it).delete() }
            dao.clearClip(e.id)
        }
        clipsRoot.listFiles()?.forEach { if (it.isDirectory && it.list().isNullOrEmpty()) it.delete() }
    }

    suspend fun deleteAllClips() {
        for (e in dao.allClips()) dao.clearClip(e.id)
        clipsRoot.deleteRecursively()
    }

    fun clipsSize(): Long = clipsStats().bytes

    /** Extraits audio présents sur le téléphone : nombre, taille totale, nombre de nuits. */
    data class ClipsStats(val count: Int = 0, val bytes: Long = 0, val nights: Int = 0)

    fun clipsStats(): ClipsStats {
        val files = clipsRoot.walkTopDown().filter { it.isFile }.toList()
        return ClipsStats(files.size, files.sumOf { it.length() }, files.mapNotNull { it.parentFile?.name }.toSet().size)
    }

    suspend fun deleteNight(id: Long) {
        File(clipsRoot, id.toString()).deleteRecursively()
        dao.deleteNight(id)
    }

    suspend fun deleteEverything() {
        dao.deleteAll()
        clipsRoot.deleteRecursively()
    }

    companion object {
        private val LEGACY_IGNORED = setOf("Animal", "Animal domestique", "Insecte", "Bourdonnement", "Bruit")

        @Volatile private var instance: Repository? = null
        fun get(ctx: Context): Repository = instance ?: synchronized(this) {
            instance ?: Repository(ctx.applicationContext).also { instance = it }
        }
    }
}
