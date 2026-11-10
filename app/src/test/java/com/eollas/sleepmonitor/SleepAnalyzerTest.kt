package com.eollas.sleepmonitor

import com.eollas.sleepmonitor.analysis.SleepAnalyzer
import com.eollas.sleepmonitor.data.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Nuit simulée : 20 min d'agitation, sommeil, un réveil de 6 min à 3 h, lever. */
class SleepAnalyzerTest {
    private val start = 1_700_000_000_000L
    private val ep = SleepAnalyzer.EPOCH_MS

    private fun night(): Pair<LongArray, FloatArray> {
        val rnd = Random(1)
        val n = 960 // 8 h
        val acts = FloatArray(n) { 1f + rnd.nextFloat() * 0.6f }
        for (i in 0 until 40) acts[i] = 5f + rnd.nextFloat() * 25f            // coucher agité
        for (i in 360 until 372) acts[i] = 8f + rnd.nextFloat() * 20f         // réveil nocturne
        for (k in 0 until 25) acts[rnd.nextInt(60, 900)] = 6f + rnd.nextFloat() * 6f // retournements
        for (i in 940 until n) acts[i] = 10f + rnd.nextFloat() * 30f          // lever
        return LongArray(n) { start + it * ep } to acts
    }

    @Test
    fun detecteEndormissementEtReveil() {
        val (t, a) = night()
        val r = SleepAnalyzer.analyze(t, a, start, start + t.size * ep, 1)
        assertNotNull(r.onset)
        val latency = (r.onset!! - start) / 60_000
        assertTrue("latence $latency", latency in 20..30)
        val wakeMin = (r.wake!! - start) / 60_000
        assertTrue("réveil $wakeMin", wakeMin in 465..471)
        val runs = mutableListOf<String>(); var s0 = -1
        for (i in r.stages.indices) { if (r.stages[i] == Stage.AWAKE) { if (s0 < 0) s0 = i } else if (s0 >= 0) { runs += "$s0-${i - 1}"; s0 = -1 } }
        println("éveil: $runs onset=${(r.onset!! - start) / ep}")
        assertEquals(1, r.awakenings)
        assertTrue("sommeil ${r.sleepMin}", r.sleepMin in 390..450)
        assertTrue("profond ${r.deepMin}", r.deepMin in 30..115)
        assertTrue("score ${r.score}", r.score in 60..100)
        assertEquals(Stage.AWAKE, r.stages[0])
        println("latence=$latency réveil=$wakeMin sommeil=${r.sleepMin} profond=${r.deepMin} léger=${r.lightMin} réveils=${r.awakenings} score=${r.score}")
    }

    @Test
    fun parolesEtEcranRetardentEndormissement() {
        // Calme dès le coucher, mais on parle / regarde l'écran pendant 12 min
        val n = 600
        val rnd = Random(4)
        val a = FloatArray(n) { 1f + rnd.nextFloat() * 0.6f }
        for (i in n - 20 until n) a[i] = 20f
        val t = LongArray(n) { start + it * ep }
        val busy = BooleanArray(n) { it in 2..20 && it % 3 == 0 }
        val screen = BooleanArray(n) { it < 24 }
        val calm = SleepAnalyzer.analyze(t, a, start, start + n * ep, 1)
        val r = SleepAnalyzer.analyze(t, a, start, start + n * ep, 1, SleepAnalyzer.Flags(screen, busy, null))
        val lat0 = (calm.onset!! - start) / 60_000
        val lat = (r.onset!! - start) / 60_000
        println("latence calme=$lat0 avec écran/paroles=$lat")
        assertTrue("au moins 5 min même si calme : $lat0", lat0 >= 5)
        assertTrue("après l'écran + 5 min : $lat", lat >= 17)
    }

    @Test
    fun profondRealiste() {
        val (t, a) = night()
        val r = SleepAnalyzer.analyze(t, a, start, start + t.size * ep, 1)
        assertTrue("profond ${r.deepMin} / ${r.sleepMin}", r.deepMin <= r.sleepMin * 0.26)
    }

    @Test
    fun horairesMiFitnessRespectes() {
        val (t, a) = night()
        val r = SleepAnalyzer.analyze(t, a, start, start + t.size * ep, 1, minOnset = start + 40 * 60_000L, maxWake = start + 400 * 60_000L)
        assertTrue(r.onset!! >= start + 40 * 60_000L)
        assertTrue(r.wake!! <= start + 400 * 60_000L + ep)
    }

    @Test
    fun nuitAgiteeSansSommeil() {
        val rnd = Random(2)
        val n = 200
        val a = FloatArray(n) { if (it % 3 == 0) 20f else 1f + rnd.nextFloat() }
        val r = SleepAnalyzer.analyze(LongArray(n) { start + it * ep }, a, start, start + n * ep, 1)
        assertEquals(null, r.onset)
        assertEquals(0, r.score)
    }
}
