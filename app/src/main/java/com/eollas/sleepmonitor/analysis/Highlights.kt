package com.eollas.sleepmonitor.analysis

import com.eollas.sleepmonitor.data.SoundCategory
import com.eollas.sleepmonitor.data.SoundEvent

/**
 * Choix des « moments de la nuit » : 10 à 20 extraits les plus intéressants.
 * Les autres extraits sont effacés le matin (les statistiques restent complètes).
 */
object Highlights {
    const val MAX = 20
    const val MIN_SNORE_MS = 20_000L

    /** Part maximale de chaque catégorie, par ordre d'importance. */
    private val caps = listOf(
        SoundCategory.APNEA to 8,
        SoundCategory.SPEECH to 6,
        SoundCategory.SNORING to 6,
        SoundCategory.COUGH to 2,
        SoundCategory.NOISE to 4,
    )

    fun inSleep(e: SoundEvent, onset: Long?, wake: Long?) =
        onset != null && wake != null && e.start in onset..wake

    /** Intérêt d'un extrait à l'intérieur de sa catégorie. */
    private fun interest(e: SoundEvent): Double {
        val dur = (e.end - e.start) / 1000.0
        return when (SoundCategory.of(e.category)) {
            SoundCategory.APNEA -> dur
            SoundCategory.SPEECH -> dur + e.confidence * 5
            SoundCategory.SNORING -> minOf(dur, 300.0) + e.peakDb * 0.5
            SoundCategory.COUGH -> e.peakDb.toDouble()
            SoundCategory.NOISE -> e.peakDb + e.confidence * 20.0
        }
    }

    /** @param hasClip vrai si l'extrait audio existe encore sur le téléphone */
    fun select(events: List<SoundEvent>, onset: Long?, wake: Long?, hasClip: (SoundEvent) -> Boolean): Set<Long> {
        val pool = events.filter { hasClip(it) && inSleep(it, onset, wake) }
            .filter { SoundCategory.of(it.category) != SoundCategory.SNORING || it.end - it.start >= MIN_SNORE_MS }
        val chosen = LinkedHashSet<Long>()
        val leftovers = mutableListOf<SoundEvent>()
        for ((cat, cap) in caps) {
            val sorted = pool.filter { it.category == cat.id }.sortedByDescending { interest(it) }
            sorted.take(cap).forEach { if (chosen.size < MAX) chosen += it.id }
            leftovers += sorted.drop(cap)
        }
        // S'il reste de la place, on complète avec les meilleurs extraits restants
        for (e in leftovers.sortedByDescending { interest(it) }) {
            if (chosen.size >= MAX) break
            chosen += e.id
        }
        return chosen
    }
}
