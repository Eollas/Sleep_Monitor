package com.eollas.sleepmonitor

import com.eollas.sleepmonitor.analysis.Highlights
import com.eollas.sleepmonitor.data.SoundCategory
import com.eollas.sleepmonitor.data.SoundEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightsTest {
    private var id = 0L
    private fun ev(min: Int, cat: SoundCategory, sec: Int, db: Float = 60f) =
        SoundEvent(id = ++id, nightId = 1, start = min * 60_000L, end = min * 60_000L + sec * 1000L, category = cat.id, label = "", confidence = 0.6f, peakDb = db, clipPath = "x")

    @Test
    fun garde20MomentsPrioritaires() {
        val evs = buildList {
            repeat(300) { add(ev(30 + it, SoundCategory.SNORING, 5 + it % 90)) }   // 300 ronflements
            repeat(3) { add(ev(100 + it, SoundCategory.APNEA, 15 + it)) }
            repeat(10) { add(ev(200 + it, SoundCategory.SPEECH, 3)) }
            add(ev(5, SoundCategory.SPEECH, 10))                                    // avant l'endormissement
            repeat(30) { add(ev(250 + it, SoundCategory.NOISE, 2)) }
        }
        val keep = Highlights.select(evs, 20 * 60_000L, 480 * 60_000L) { true }
        val kept = evs.filter { it.id in keep }
        assertEquals(20, kept.size)
        assertEquals(3, kept.count { it.category == SoundCategory.APNEA.id })
        assertEquals(6, kept.count { it.category == SoundCategory.SPEECH.id })
        assertTrue(kept.none { it.start < 20 * 60_000L })
        assertTrue(kept.filter { it.category == SoundCategory.SNORING.id }.all { it.end - it.start >= 20_000 })
    }
}
