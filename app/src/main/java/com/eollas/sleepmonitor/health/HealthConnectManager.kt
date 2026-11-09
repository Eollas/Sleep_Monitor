package com.eollas.sleepmonitor.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.eollas.sleepmonitor.data.Epoch
import com.eollas.sleepmonitor.data.Night
import com.eollas.sleepmonitor.data.Stage
import java.time.Instant
import java.time.ZoneId

/**
 * Lien avec Health Connect, le carnet de santé d'Android (stocké sur le téléphone).
 * Mi Fitness y dépose le sommeil et le rythme cardiaque mesurés par la montre.
 */
class HealthConnectManager(private val ctx: Context) {

    val available: Boolean
        get() = HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(ctx) }

    val permissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getWritePermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    suspend fun granted(): Set<String> =
        if (!available) emptySet() else runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    suspend fun canRead() = granted().contains(HealthPermission.getReadPermission(SleepSessionRecord::class))
    suspend fun canWrite() = granted().contains(HealthPermission.getWritePermission(SleepSessionRecord::class))
    suspend fun canReadHr() = granted().contains(HealthPermission.getReadPermission(HeartRateRecord::class))

    data class ExternalSleep(
        val source: String,
        val start: Long,
        val end: Long,
        val sleepMin: Int,
        val deepMin: Int,
        val lightMin: Int,
        val remMin: Int,
        val awakeMin: Int,
    )

    /** Nuit enregistrée par une autre app (Mi Fitness…) qui chevauche notre suivi. */
    suspend fun readExternalSleep(start: Long, end: Long): ExternalSleep? {
        if (!canRead()) return null
        val req = ReadRecordsRequest(
            recordType = SleepSessionRecord::class,
            timeRangeFilter = TimeRangeFilter.between(
                Instant.ofEpochMilli(start - 4 * HOUR), Instant.ofEpochMilli(end + 4 * HOUR),
            ),
        )
        val records = client.readRecords(req).records.filter {
            it.metadata.dataOrigin.packageName != ctx.packageName &&
                it.endTime.toEpochMilli() > start - HOUR && it.startTime.toEpochMilli() < end + HOUR
        }
        val r = records.maxByOrNull { it.endTime.toEpochMilli() - it.startTime.toEpochMilli() } ?: return null
        var deep = 0L; var light = 0L; var rem = 0L; var awake = 0L; var sleeping = 0L
        for (s in r.stages) {
            val d = s.endTime.toEpochMilli() - s.startTime.toEpochMilli()
            when (s.stage) {
                SleepSessionRecord.STAGE_TYPE_DEEP -> deep += d
                SleepSessionRecord.STAGE_TYPE_LIGHT -> light += d
                SleepSessionRecord.STAGE_TYPE_REM -> rem += d
                SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> awake += d
                else -> sleeping += d
            }
        }
        val total = r.endTime.toEpochMilli() - r.startTime.toEpochMilli()
        val sleepMs = if (r.stages.isEmpty()) total else deep + light + rem + sleeping
        return ExternalSleep(
            source = sourceName(r.metadata.dataOrigin.packageName),
            start = r.startTime.toEpochMilli(), end = r.endTime.toEpochMilli(),
            sleepMin = (sleepMs / 60_000).toInt(), deepMin = (deep / 60_000).toInt(),
            lightMin = (light / 60_000).toInt(), remMin = (rem / 60_000).toInt(),
            awakeMin = (awake / 60_000).toInt(),
        )
    }

    /** Rythme cardiaque moyen et minimum pendant la nuit. */
    suspend fun readHeartRate(start: Long, end: Long): Pair<Int, Int>? {
        if (!canReadHr()) return null
        val req = ReadRecordsRequest(
            recordType = HeartRateRecord::class,
            timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(start), Instant.ofEpochMilli(end)),
        )
        val bpm = client.readRecords(req).records.flatMap { r -> r.samples.map { it.beatsPerMinute } }
        if (bpm.isEmpty()) return null
        return bpm.average().toInt() to bpm.min().toInt()
    }

    /** Ajoute notre nuit (endormissement → réveil, avec les phases) dans Health Connect. */
    suspend fun writeSleep(night: Night, epochs: List<Epoch>): Boolean {
        if (!canWrite()) return false
        val onset = night.sleepOnset ?: return false
        val wake = night.wakeTime ?: return false
        if (wake - onset < 30 * 60_000L) return false
        val stages = mutableListOf<SleepSessionRecord.Stage>()
        var curStage = -1
        var curStart = 0L
        fun flush(endT: Long) {
            if (curStage < 0 || endT <= curStart) return
            val type = when (curStage) {
                Stage.DEEP -> SleepSessionRecord.STAGE_TYPE_DEEP
                Stage.LIGHT -> SleepSessionRecord.STAGE_TYPE_LIGHT
                else -> SleepSessionRecord.STAGE_TYPE_AWAKE
            }
            stages += SleepSessionRecord.Stage(Instant.ofEpochMilli(curStart), Instant.ofEpochMilli(endT), type)
        }
        for (e in epochs) {
            if (e.t < onset || e.t >= wake) continue
            if (e.stage != curStage) { flush(e.t); curStage = e.stage; curStart = e.t }
        }
        flush(wake)
        val zone = ZoneId.systemDefault().rules
        val record = SleepSessionRecord(
            startTime = Instant.ofEpochMilli(onset),
            startZoneOffset = zone.getOffset(Instant.ofEpochMilli(onset)),
            endTime = Instant.ofEpochMilli(wake),
            endZoneOffset = zone.getOffset(Instant.ofEpochMilli(wake)),
            metadata = Metadata.autoRecorded(Device(type = Device.TYPE_PHONE)),
            title = "Sleep Monitor",
            stages = stages,
        )
        return runCatching { client.insertRecords(listOf(record)) }.isSuccess
    }

    fun settingsIntent(): Intent = Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")

    private fun sourceName(pkg: String): String = when {
        pkg == "com.xiaomi.wearable" || pkg == "com.mi.health" -> "Mi Fitness"
        pkg == "com.xiaomi.hm.health" -> "Zepp Life"
        else -> runCatching {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    companion object { private const val HOUR = 3_600_000L }
}
