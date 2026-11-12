package com.eollas.sleepmonitor

import com.eollas.sleepmonitor.data.SoundCategory
import com.eollas.sleepmonitor.tracking.SoundRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Règles de classement des sons (sans téléphone ni modèle : scores fabriqués à la main). */
class SoundRulesTest {
    private fun scores(vararg p: Pair<Int, Float>) = FloatArray(521).also { a -> p.forEach { (i, v) -> a[i] = v } }
    private val normal = SoundRules.Tuning(0.5f)

    @Test
    fun curseurPlusSensibleBaisseLesSeuils() {
        val least = SoundRules.Tuning(0f)
        val most = SoundRules.Tuning(1f)
        assertTrue(least.gateDb > normal.gateDb && normal.gateDb > most.gateDb)
        assertTrue(least.snore > most.snore && least.speech > most.speech)
        assertTrue(least.votes(SoundCategory.SNORING) > most.votes(SoundCategory.SNORING))
        assertEquals(3, normal.votes(SoundCategory.SNORING))
        assertEquals(2, normal.votes(SoundCategory.COUGH))
        assertTrue(most.votes(SoundCategory.NOISE) >= 1)
    }

    @Test
    fun ronflementRetenuSeulementSIlDomineLeFond() {
        val ok = SoundRules.decide(scores(38 to 0.5f, 494 to 0.1f), 8f, normal)
        assertEquals(SoundCategory.SNORING, ok?.category)
        // Le modèle hésite et trouve « Silence » plus probable : rien n'est retenu
        assertNull(SoundRules.decide(scores(38 to 0.5f, 494 to 0.6f), 8f, normal))
        // Score trop faible pour la sensibilité moyenne
        assertNull(SoundRules.decide(scores(38 to 0.28f), 8f, normal))
    }

    @Test
    fun uneRespirationNEstPasDesParoles() {
        assertNull(SoundRules.decide(scores(0 to 0.55f, 36 to 0.7f), 8f, normal))
        assertEquals(SoundCategory.SPEECH, SoundRules.decide(scores(0 to 0.7f, 36 to 0.2f), 8f, normal)?.category)
    }

    @Test
    fun bruitPerturbantDoitEtreFort() {
        val door = scores(352 to 0.8f) // claquement
        assertNull(SoundRules.decide(door, 8f, normal))
        assertEquals(SoundCategory.NOISE, SoundRules.decide(door, 25f, normal)?.category)
        // Ventilateur, silence, respiration : jamais des bruits perturbants
        for (i in listOf(406, 494, 36, 507, 481)) assertFalse(i in SoundRules.DISTURBING)
    }

    @Test
    fun troisCategoriesParOrdreDeProbabilite() {
        val top = SoundRules.top(scores(38 to 0.62f, 36 to 0.21f, 494 to 0.08f, 0 to 0.01f))
        assertEquals(listOf(38, 36, 494), top.map { it.first })
        val back = SoundRules.decodeTop(SoundRules.encodeTop(top))
        assertEquals(listOf(38, 36, 494), back.map { it.first })
        assertEquals(0.62f, back[0].second, 0.001f)
        assertTrue(SoundRules.decodeTop(null).isEmpty())
    }
}
