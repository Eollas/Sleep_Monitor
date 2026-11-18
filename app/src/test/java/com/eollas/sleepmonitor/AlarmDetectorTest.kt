package com.eollas.sleepmonitor

import com.eollas.sleepmonitor.analysis.AlarmDetector
import com.eollas.sleepmonitor.data.SoundCategory
import com.eollas.sleepmonitor.data.SoundEvent
import com.eollas.sleepmonitor.tracking.AudioMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class AlarmDetectorTest {
    // Minuit UTC d'un jour quelconque : les heures ci-dessous sont des heures « rondes »
    private val day = 1_794_787_200_000L
    private var id = 1L

    private fun t(h: Int, m: Int, s: Int) = day + ((h * 60 + m) * 60 + s) * 1000L

    private fun ev(h: Int, m: Int, s: Int, durS: Int, label: String = "Musique", cat: SoundCategory = SoundCategory.NOISE) =
        SoundEvent(id = id++, nightId = 1, start = t(h, m, s), end = t(h, m, s) + durS * 1000L,
            category = cat.id, label = label, confidence = 0.6f, peakDb = 60f)

    @Test
    fun nuitDu16Novembre_reveilRepousse() {
        // Sons réels de l'export (nuit 12) : sonnerie toutes les 7 min à partir de 7 h 30
        val evs = listOf(
            ev(2, 35, 7, 2, "Toux", SoundCategory.COUGH),
            ev(7, 30, 12, 13), ev(7, 30, 34, 23),
            ev(7, 37, 12, 7), ev(7, 37, 23, 7), ev(7, 37, 34, 21),
            ev(7, 44, 12, 14), ev(7, 44, 33, 24),
            ev(7, 51, 12, 13), ev(7, 51, 34, 3),
            ev(8, 15, 8, 12), ev(8, 32, 15, 3), ev(8, 44, 58, 4),
        )
        val r = AlarmDetector.detect(evs, t(8, 45, 35))
        assertEquals(7, r.rings.size)
        assertEquals(t(7, 30, 12), r.first)
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2), r.snoozeIndex)
        assertTrue(evs[0].id !in r.eventIds) // la toux n'est pas touchée
    }

    @Test
    fun musiqueEnPleineNuit_resteUnBruit() {
        // Musique à 3 h 12 min 40 s (pas à une minute ronde), suivi arrêté à 8 h
        val r = AlarmDetector.detect(listOf(ev(3, 12, 40, 30)), t(8, 0, 0))
        assertTrue(r.rings.isEmpty())
    }

    @Test
    fun uneSeuleSonnerie_loinDeLaFin_nonRetenue() {
        // 6 h 00 pile mais le suivi continue jusqu'à 7 h 30 : une seule sonnerie ne suffit pas
        assertTrue(AlarmDetector.detect(listOf(ev(6, 0, 10, 20)), t(7, 30, 0)).rings.isEmpty())
        // Juste avant l'arrêt du suivi : c'est le réveil
        assertEquals(1, AlarmDetector.detect(listOf(ev(8, 0, 1, 7)), t(8, 24, 9)).rings.size)
        // Non alignée mais terminée 3 min avant l'arrêt : réveil quand même
        assertEquals(1, AlarmDetector.detect(listOf(ev(11, 30, 40, 9)), t(11, 33, 55)).rings.size)
    }

    @Test
    fun resume() {
        val evs = listOf(ev(7, 30, 12, 13), ev(7, 37, 12, 7), ev(7, 44, 12, 14))
        val r = AlarmDetector.detect(evs, t(7, 50, 0))
        val marked = evs.mapIndexed { i, e ->
            val n = r.snoozeIndex[i]
            e.copy(category = SoundCategory.ALARM.id, label = AlarmDetector.label(n, if (n > 0) 420_000L else null))
        }
        val s = AlarmDetector.summary(marked)!!
        assertEquals(3, s.rings); assertEquals(2, s.snoozes)
        assertEquals("Réveil repoussé · rappel 1 (+7 min)", marked[1].label)
    }

    @Test
    fun rythmeDeRespiration() {
        // Bouffée toutes les 3,3 s (pas de 0,1 s) : très régulier
        val breath = FloatArray(250) { (40 + 6 * maxOf(0.0, sin(2 * PI * it / 33.0))).toFloat() }
        assertTrue(AudioMonitor.rhythm(breath) > 0.5f)
        // Silence plat avec un léger bruit aléatoire : pas de rythme
        val rnd = java.util.Random(1)
        val flat = FloatArray(250) { 40f + rnd.nextGaussian().toFloat() }
        assertTrue(AudioMonitor.rhythm(flat) < 0.35f)
    }
}
