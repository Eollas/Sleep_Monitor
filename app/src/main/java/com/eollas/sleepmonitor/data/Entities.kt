package com.eollas.sleepmonitor.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Une nuit de suivi. Les champs "ext" viennent de Health Connect (Mi Fitness). */
@Entity(tableName = "nights")
data class Night(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,               // début du suivi (coucher)
    val endTime: Long? = null,         // fin du suivi
    val sleepOnset: Long? = null,      // endormissement détecté
    val wakeTime: Long? = null,        // réveil détecté
    val totalSleepMin: Int = 0,
    val deepMin: Int = 0,
    val lightMin: Int = 0,
    val awakeMin: Int = 0,
    val latencyMin: Int = 0,
    val awakenings: Int = 0,
    val efficiency: Int = 0,           // % du temps au lit passé à dormir
    val score: Int = 0,
    val snoreMin: Int = 0,
    val talkCount: Int = 0,
    val coughCount: Int = 0,
    val noiseCount: Int = 0,
    val avgDb: Float = 0f,
    val audioEnabled: Boolean = true,
    val finished: Boolean = false,
    val interrupted: Boolean = false,
    val hcExported: Boolean = false,
    val extSource: String? = null,
    val extSleepMin: Int? = null,
    val extDeepMin: Int? = null,
    val extLightMin: Int? = null,
    val extRemMin: Int? = null,
    val extAwakeMin: Int? = null,
    val extStart: Long? = null,
    val extEnd: Long? = null,
    val hrAvg: Int? = null,
    val hrMin: Int? = null,
    @ColumnInfo(defaultValue = "0") val apneaCount: Int = 0,
    // Version de l'analyse : les vieilles nuits sont recalculées après une mise à jour
    @ColumnInfo(defaultValue = "1") val analysisVersion: Int = 0,
)

/** Tranche de 30 secondes : mouvement (milli-g) et niveau sonore (dB approx.). */
@Entity(
    tableName = "epochs",
    primaryKeys = ["nightId", "t"],
    foreignKeys = [ForeignKey(Night::class, ["id"], ["nightId"], onDelete = ForeignKey.CASCADE)],
)
data class Epoch(
    val nightId: Long,
    val t: Long,
    val activity: Float,
    val db: Float,
    val maxDb: Float,
    val stage: Int = Stage.UNKNOWN,
    @ColumnInfo(defaultValue = "0") val screenOn: Boolean = false,
)

object Stage {
    const val UNKNOWN = 0
    const val AWAKE = 1
    const val LIGHT = 2
    const val DEEP = 3
}

/** Un bruit détecté pendant la nuit, avec éventuellement son extrait audio. */
@Entity(
    tableName = "sound_events",
    indices = [Index("nightId")],
    foreignKeys = [ForeignKey(Night::class, ["id"], ["nightId"], onDelete = ForeignKey.CASCADE)],
)
data class SoundEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nightId: Long,
    val start: Long,
    val end: Long,
    val category: Int,
    val label: String,
    val confidence: Float,
    val peakDb: Float,
    val clipPath: String? = null,
    // Fait partie des « moments de la nuit » gardés le matin
    @ColumnInfo(defaultValue = "0") val highlight: Boolean = false,
)

enum class SoundCategory(val id: Int, val label: String, val color: Long) {
    SNORING(1, "Ronflement", 0xFF9D8CF2),
    SPEECH(2, "Paroles", 0xFF6FC3DF),
    COUGH(3, "Toux", 0xFFE9B872),
    NOISE(4, "Bruit perturbant", 0xFFE57B7B),
    APNEA(5, "Pause respiratoire", 0xFFFF8A65);

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: NOISE
    }
}
