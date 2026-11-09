package com.eollas.sleepmonitor.data

import android.content.Context
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
        val epochs = dao.epochs(nightId)
        val s = settings.current()
        val res = SleepAnalyzer.analyze(
            LongArray(epochs.size) { epochs[it].t }, FloatArray(epochs.size) { epochs[it].activity },
            night.startTime, endTime, s.sensitivity,
        )
        val staged = epochs.mapIndexed { i, e -> e.copy(stage = res.stages.getOrElse(i) { Stage.AWAKE }) }
        dao.insertEpochs(staged)
        val events = dao.events(nightId)
        val snoreMs = events.filter { it.category == SoundCategory.SNORING.id }.sumOf { it.end - it.start }
        val dbs = epochs.map { it.db }.filter { it > 0f }
        var updated = night.copy(
            endTime = endTime,
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
            talkCount = events.count { it.category == SoundCategory.SPEECH.id },
            coughCount = events.count { it.category == SoundCategory.COUGH.id },
            noiseCount = events.count { it.category == SoundCategory.NOISE.id },
            avgDb = if (dbs.isEmpty()) 0f else dbs.average().toFloat(),
            finished = true,
            interrupted = interrupted,
        )
        dao.updateNight(updated)
        if (s.hcWrite && !updated.hcExported && runCatching { health.writeSleep(updated, staged) }.getOrDefault(false)) {
            updated = updated.copy(hcExported = true)
            dao.updateNight(updated)
        }
        updated = refreshExternal(updated)
        cleanClips(s.retentionDays)
        return updated
    }

    /** Récupère la nuit Mi Fitness / Health Connect et le rythme cardiaque. */
    suspend fun refreshExternal(night: Night): Night {
        if (!health.available) return night
        val end = night.endTime ?: return night
        val ext = runCatching { health.readExternalSleep(night.startTime, end) }.getOrNull()
        val hrFrom = ext?.start ?: night.sleepOnset ?: night.startTime
        val hrTo = ext?.end ?: night.wakeTime ?: end
        val hr = runCatching { health.readHeartRate(hrFrom, hrTo) }.getOrNull()
        if (ext == null && hr == null) return night
        val n = night.copy(
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
        dao.updateNight(n)
        return n
    }

    /** Termine une nuit coupée (téléphone redémarré, app arrêtée par le système…). */
    suspend fun recoverInterrupted() {
        if (Live.state.value.running) return
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

    fun clipsSize(): Long = clipsRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    suspend fun deleteNight(id: Long) {
        File(clipsRoot, id.toString()).deleteRecursively()
        dao.deleteNight(id)
    }

    suspend fun deleteEverything() {
        dao.deleteAll()
        clipsRoot.deleteRecursively()
    }

    companion object {
        @Volatile private var instance: Repository? = null
        fun get(ctx: Context): Repository = instance ?: synchronized(this) {
            instance ?: Repository(ctx.applicationContext).also { instance = it }
        }
    }
}
