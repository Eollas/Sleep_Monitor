package com.eollas.sleepmonitor.analysis

import com.eollas.sleepmonitor.data.Stage
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Analyse des mouvements (actigraphie) par tranches de 30 s, complétée par les sons
 * et l'écran : calme prolongé, silence et écran éteint = sommeil.
 */
object SleepAnalyzer {
    const val EPOCH_MS = 30_000L

    /** Augmenter quand l'analyse change : les anciennes nuits seront recalculées. */
    const val VERSION = 2

    /** Temps minimal entre le coucher et l'endormissement. */
    private const val MIN_LATENCY_MS = 5 * 60_000L

    /** Calme complet nécessaire avant de compter le dormeur comme endormi (5 min). */
    private const val SETTLE_EPOCHS = 10

    /** Le sommeil profond ne peut pas dépasser 25 % de la nuit (valeur réaliste). */
    private const val MAX_DEEP_RATIO = 0.25f

    /**
     * Indices complémentaires, une valeur par tranche de 30 s :
     * écran allumé, sons d'activité (paroles, bruits), ronflement.
     */
    class Flags(
        val screen: BooleanArray? = null,
        val busy: BooleanArray? = null,
        val snore: BooleanArray? = null,
    ) {
        fun screen(i: Int) = screen?.getOrNull(i) == true
        fun busy(i: Int) = busy?.getOrNull(i) == true
        fun snore(i: Int) = snore?.getOrNull(i) == true
    }

    data class Result(
        val stages: IntArray,
        val onset: Long?,
        val wake: Long?,
        val sleepMin: Int,
        val deepMin: Int,
        val lightMin: Int,
        val awakeMin: Int,
        val latencyMin: Int,
        val awakenings: Int,
        val efficiency: Int,
        val score: Int,
        val threshold: Float,
    )

    /** Seuil de mouvement adapté au bruit de fond du capteur et du matelas. */
    fun threshold(acts: FloatArray, sensitivity: Int): Float {
        val p20 = noise(acts)
        val factor = when (sensitivity) { 0 -> 1.4f; 2 -> 0.75f; else -> 1f }
        return max(max(p20 * 2.5f, p20 + 2.5f) * factor, 1.5f)
    }

    /** Bruit de fond du capteur (20e centile des mouvements). */
    fun noise(acts: FloatArray): Float {
        if (acts.isEmpty()) return 1f
        val sorted = acts.sorted()
        return sorted[(sorted.size * 0.2f).toInt().coerceIn(0, sorted.size - 1)]
    }

    /**
     * @param minOnset endormissement au plus tôt (ex. donné par Mi Fitness)
     * @param maxWake réveil au plus tard (ex. donné par Mi Fitness)
     */
    fun analyze(
        times: LongArray,
        acts: FloatArray,
        start: Long,
        end: Long,
        sensitivity: Int,
        flags: Flags = Flags(),
        minOnset: Long? = null,
        maxWake: Long? = null,
    ): Result {
        val n = acts.size
        val inBedMin = ((end - start) / 60_000L).toInt().coerceAtLeast(1)
        val thr = threshold(acts, sensitivity)
        val stages = IntArray(n) { Stage.AWAKE }
        val empty = Result(stages, null, null, 0, 0, 0, inBedMin, 0, 0, 0, 0, thr)
        if (n < 20) return empty

        val moved = BooleanArray(n) { acts[it] > thr }
        fun count(from: Int, to: Int, f: (Int) -> Boolean): Int {
            var c = 0
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(n)) if (f(i)) c++
            return c
        }

        // Endormissement : 15 min sans mouvement, écran éteint et sans paroles ni bruits
        // d'activité. On ne compte le dormeur endormi qu'après 5 min de ce calme.
        // Un ronflement régulier confirme le sommeil plus tôt.
        val earliest = max(start + MIN_LATENCY_MS, minOnset ?: 0L)
        val w = 30
        var onsetIdx = -1
        for (i in 0 until n) {
            if (i + w <= n &&
                count(i, i + w) { moved[it] } <= 2 &&
                count(i, i + w) { flags.screen(it) } == 0 &&
                count(i, i + w) { flags.busy(it) } <= 1
            ) {
                val c = i + SETTLE_EPOCHS
                if (c < n && times[c] >= earliest) { onsetIdx = c; break }
            }
            if (i + 10 <= n && flags.snore(i) && times[i] >= earliest &&
                count(i, i + 10) { flags.snore(it) } >= 3 &&
                count(i, i + 10) { moved[it] } <= 1 &&
                count(i, i + 10) { flags.screen(it) } == 0
            ) { onsetIdx = i; break }
        }
        if (onsetIdx < 0) return empty

        // Réveil : fin de la dernière période de 10 min quasi immobile, écran éteint.
        var wakeIdx = -1
        for (j in n - 1 downTo onsetIdx) {
            if (maxWake != null && times[j] >= maxWake) continue
            if (!flags.screen(j) && count(j - 19, j + 1) { moved[it] } <= 2) { wakeIdx = j; break }
        }
        if (wakeIdx < onsetIdx) return empty

        // Score pondéré des voisins (inspiré de Cole-Kripke).
        val weights = floatArrayOf(0.15f, 0.15f, 0.15f, 0.2f, 1f, 0.2f, 0.1f)
        val awake = BooleanArray(n)
        for (i in onsetIdx..wakeIdx) {
            var d = 0f
            for (k in weights.indices) {
                val j = i + k - 4
                // Seuls les vrais mouvements (au-dessus du seuil) comptent
                if (j in 0 until n && moved[j]) d += weights[k] * (acts[j] / thr)
            }
            awake[i] = d >= 1f || flags.screen(i)
        }
        // Un mouvement isolé (30 s) ne compte pas comme un réveil.
        for (i in onsetIdx + 1 until wakeIdx) {
            if (awake[i] && !awake[i - 1] && !awake[i + 1] && !flags.screen(i)) awake[i] = false
        }

        // Profond : loin de tout mouvement. On exige d'abord 10 min de calme de chaque côté,
        // puis davantage si le profond dépasse 25 % du sommeil.
        val dist = IntArray(n) { Int.MAX_VALUE }
        var last = -100_000
        for (i in 0 until n) { if (moved[i]) last = i; dist[i] = i - last }
        last = 100_000
        for (i in n - 1 downTo 0) { if (moved[i]) last = i; dist[i] = minOf(dist[i], last - i) }
        val sleepIdx = (onsetIdx..wakeIdx).filter { !awake[it] }
        var need = 20
        while (sleepIdx.count { dist[it] >= need } > sleepIdx.size * MAX_DEEP_RATIO) need += 4

        var deepEp = 0; var lightEp = 0; var awakeEp = 0
        for (i in onsetIdx..wakeIdx) {
            when {
                awake[i] -> { stages[i] = Stage.AWAKE; awakeEp++ }
                dist[i] >= need -> { stages[i] = Stage.DEEP; deepEp++ }
                else -> { stages[i] = Stage.LIGHT; lightEp++ }
            }
        }
        val sleepEp = deepEp + lightEp

        // Réveils nocturnes : éveil d'au moins 3 minutes.
        var awakenings = 0
        var run = 0
        for (i in onsetIdx..wakeIdx) {
            if (awake[i]) run++ else { if (run >= 6) awakenings++; run = 0 }
        }
        if (run >= 6) awakenings++

        val onset = times[onsetIdx]
        val wake = times[wakeIdx] + EPOCH_MS
        val sleepMin = sleepEp / 2
        val deepMin = deepEp / 2
        val latency = ((onset - start) / 60_000L).toInt().coerceAtLeast(0)
        val efficiency = (sleepMin * 100 / inBedMin).coerceIn(0, 100)
        val score = score(sleepMin, efficiency, deepMin, awakenings, latency)
        return Result(
            stages, onset, wake, sleepMin, deepMin, lightEp / 2,
            inBedMin - sleepMin, latency, awakenings, efficiency, score, thr,
        )
    }

    fun score(sleepMin: Int, efficiency: Int, deepMin: Int, awakenings: Int, latency: Int): Int {
        if (sleepMin <= 0) return 0
        val h = sleepMin / 60f
        val dur = when {
            h in 7f..9f -> 1f
            h < 7f -> ((h - 3f) / 4f).coerceIn(0f, 1f)
            else -> (1f - (h - 9f) / 4f).coerceIn(0.6f, 1f)
        }
        val eff = ((efficiency - 65f) / 25f).coerceIn(0f, 1f)
        val deep = ((deepMin * 100f / sleepMin) / 18f).coerceIn(0f, 1f)
        val awk = (1f - awakenings * 0.2f).coerceIn(0f, 1f)
        val lat = when {
            latency <= 20 -> 1f
            latency >= 60 -> 0f
            else -> 1f - (latency - 20) / 40f
        }
        return (40 * dur + 25 * eff + 15 * deep + 10 * awk + 10 * lat).roundToInt().coerceIn(0, 100)
    }

    fun verdict(score: Int) = when {
        score >= 85 -> "Excellente nuit"
        score >= 70 -> "Bonne nuit"
        score >= 50 -> "Nuit correcte"
        score > 0 -> "Nuit agitée"
        else -> "Sommeil non détecté"
    }
}
