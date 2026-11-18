package com.eollas.sleepmonitor.analysis

import com.eollas.sleepmonitor.data.SoundCategory
import com.eollas.sleepmonitor.data.SoundEvent

/**
 * Reconnaît la sonnerie du réveil parmi les sons de la fin de nuit.
 *
 * Le modèle classe la plupart des sonneries en « Musique ». Nuits réelles (export du
 * 18 nov. 2026) : la sonnerie démarre pile à une minute ronde (détectée 8 à 15 s
 * après, le temps de confirmer le son) et revient toutes les 7 min quand on la
 * repousse (07:30:12, 07:37:12, 07:44:12…).
 *
 * Règles :
 * - une « sonnerie » = des sons musicaux / sonneries espacés de moins de 90 s ;
 * - elle compte comme réveil si elle commence dans les 25 s après une minute ronde,
 *   ou si elle se termine dans les 10 min avant l'arrêt du suivi ;
 * - seules les 4 dernières heures du suivi sont examinées ;
 * - une seule sonnerie « à la minute ronde » ne suffit que si elle a lieu dans
 *   les 45 min avant l'arrêt (sinon il en faut au moins deux) ;
 * - une sonnerie moins de 20 min après la précédente = réveil repoussé (« rappel »).
 */
object AlarmDetector {
    const val RING_GAP_MS = 90_000L
    const val ALIGN_S = 25
    const val NEAR_END_MS = 10 * 60_000L
    const val SINGLE_MS = 45 * 60_000L
    const val LOOKBACK_MS = 4 * 60 * 60_000L
    const val SNOOZE_MS = 20 * 60_000L

    /** Noms possibles d'une sonnerie (voir Labels : 132..276 = « Musique »). */
    private val NAMES = setOf("Musique", "Réveil", "Sonnerie", "Téléphone")

    /** Libellé d'origine gardé dans les événements « réveil » (« Réveil · Musique »). */
    private const val PREFIX = "Réveil"

    fun isCandidate(e: SoundEvent): Boolean =
        e.category == SoundCategory.ALARM.id ||
            (e.category == SoundCategory.NOISE.id && e.label in NAMES)

    /** Une sonnerie : plusieurs sons rapprochés. */
    data class Ring(val events: List<SoundEvent>) {
        val start get() = events.first().start
        val end get() = events.maxOf { it.end }
    }

    /** Résultat : les sonneries du réveil, dans l'ordre, et la place de chacune. */
    data class Result(val rings: List<Ring>, val snoozeIndex: List<Int>) {
        val eventIds: Set<Long> get() = rings.flatMap { r -> r.events.map { it.id } }.toSet()
        val first: Long? get() = rings.firstOrNull()?.start
        val last: Long? get() = rings.lastOrNull()?.start
        /** Nombre de fois où le réveil a été repoussé (rappels). */
        val snoozes: Int get() = snoozeIndex.count { it > 0 }
    }

    fun detect(events: List<SoundEvent>, trackingEnd: Long): Result {
        val cands = events.filter { isCandidate(it) && it.start >= trackingEnd - LOOKBACK_MS }.sortedBy { it.start }
        val rings = mutableListOf<MutableList<SoundEvent>>()
        for (e in cands) {
            val cur = rings.lastOrNull()
            if (cur != null && e.start - cur.maxOf { it.end } <= RING_GAP_MS) cur += e else rings += mutableListOf(e)
        }
        fun aligned(t: Long) = (t / 1000 % 60) < ALIGN_S
        val alarm = rings.map { Ring(it) }.filter { r ->
            aligned(r.start) || trackingEnd - r.end <= NEAR_END_MS
        }
        val valid = when {
            alarm.isEmpty() -> emptyList()
            alarm.size == 1 && trackingEnd - alarm[0].end > SINGLE_MS -> emptyList()
            else -> alarm
        }
        // Rappel n°1, 2… tant que chaque sonnerie suit la précédente de moins de 20 min
        val idx = mutableListOf<Int>()
        for ((i, r) in valid.withIndex()) {
            idx += if (i > 0 && r.start - valid[i - 1].start <= SNOOZE_MS) idx[i - 1] + 1 else 0
        }
        return Result(valid, idx)
    }

    /** Libellé affiché pour un son de la sonnerie n°[snooze] (0 = première sonnerie). */
    fun label(snooze: Int, gapMs: Long?): String =
        if (snooze == 0) "$PREFIX : sonnerie"
        else "$PREFIX repoussé · rappel $snooze" + (gapMs?.let { " (+${(it + 30_000) / 60_000} min)" } ?: "")

    /** Résumé affiché : heure de la 1re sonnerie, de la dernière, nombre de rappels. */
    data class Summary(val first: Long, val last: Long, val rings: Int, val snoozes: Int)

    /** Résumé à partir des sons déjà marqués « réveil » en base. */
    fun summary(events: List<SoundEvent>): Summary? {
        val al = events.filter { it.category == SoundCategory.ALARM.id }.sortedBy { it.start }
        if (al.isEmpty()) return null
        val starts = mutableListOf<SoundEvent>()
        var lastEnd = Long.MIN_VALUE
        for (e in al) {
            if (starts.isEmpty() || e.start - lastEnd > RING_GAP_MS) starts += e
            lastEnd = maxOf(lastEnd, e.end)
        }
        return Summary(starts.first().start, starts.last().start, starts.size, starts.count { it.label.contains("rappel") })
    }

    /** Nom d'origine à remettre si un son n'est plus considéré comme le réveil. */
    const val ORIGINAL = "Musique"
}
