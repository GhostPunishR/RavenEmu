package com.ravenemu.emulation.api.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Relevé du transport audio.
 *
 * Deux exigences opposées : ne rien coûter quand il est éteint, et distinguer
 * les causes possibles d'un craquement quand il est allumé.
 */
class AudioTransportStatsTest {

    private fun actif() = AudioTransportStats().apply { enabled = true }

    @Test
    fun `desactive, aucun compteur ne bouge`() {
        // C'est la garantie de coût nul : pas de branchement à retirer, pas de
        // variante de construction à distinguer, aucun compteur touché.
        val stats = AudioTransportStats()
        assertFalse(stats.enabled, "Le relevé doit être éteint par défaut")

        stats.onBlock(submitted = 1098, resampled = 1608, written = 800)
        stats.onRestart()
        stats.onUnderrunCount(12)
        stats.onFailure(IllegalStateException("piste morte"))

        assertEquals(0L, stats.samplesSubmitted)
        assertEquals(0L, stats.samplesResampled)
        assertEquals(0L, stats.samplesWritten)
        assertEquals(0, stats.shortWrites)
        assertEquals(0, stats.restarts)
        assertEquals(0, stats.outputUnderruns)
        assertEquals(0, stats.failures)
        assertNull(stats.lastFailure)
        assertEquals("", stats.summary(), "Aucune chaîne ne doit être construite")
    }

    @Test
    fun `un bloc entierement ecrit ne compte aucune troncature`() {
        val stats = actif()
        stats.onBlock(submitted = 1098, resampled = 1608, written = 1608)

        assertEquals(1098L, stats.samplesSubmitted)
        assertEquals(1608L, stats.samplesResampled)
        assertEquals(1608L, stats.samplesWritten)
        assertEquals(0, stats.shortWrites)
    }

    @Test
    fun `une ecriture partielle est comptee comme discontinuite`() {
        // C'est le cas qui passait inaperçu : la fin du bloc est perdue, le son
        // saute, et rien n'en gardait trace.
        val stats = actif()
        stats.onBlock(submitted = 1098, resampled = 1608, written = 1200)

        assertEquals(1, stats.shortWrites)
        assertEquals(408L, stats.samplesResampled - stats.samplesWritten)
    }

    @Test
    fun `les compteurs s'accumulent d'un bloc a l'autre`() {
        val stats = actif()
        repeat(60) { stats.onBlock(submitted = 1098, resampled = 1608, written = 1608) }

        assertEquals(60 * 1098L, stats.samplesSubmitted)
        assertEquals(60 * 1608L, stats.samplesWritten)
        assertEquals(0, stats.shortWrites)
    }

    @Test
    fun `le compteur de ruptures de la plateforme ne recule pas`() {
        // `AudioTrack.underrunCount` est cumulatif, mais une piste vidée puis
        // relancée peut le redonner plus bas. Un compteur qui recule ferait
        // croire à une amélioration.
        val stats = actif()
        stats.onUnderrunCount(5)
        stats.onUnderrunCount(3)
        assertEquals(5, stats.outputUnderruns)
        stats.onUnderrunCount(9)
        assertEquals(9, stats.outputUnderruns)
        stats.onUnderrunCount(-1)
        assertEquals(9, stats.outputUnderruns, "Une valeur négative ne doit rien changer")
    }

    @Test
    fun `un echec retient son type et son message`() {
        val stats = actif()
        stats.onFailure(IllegalStateException("piste non initialisée"))
        stats.onFailure(IllegalStateException("piste non initialisée"))

        assertEquals(2, stats.failures)
        val dernier = assertNotNull(stats.lastFailure)
        assertTrue("IllegalStateException" in dernier, dernier)
        assertTrue("piste non initialisée" in dernier, dernier)
    }

    @Test
    fun `un echec sans message reste lisible`() {
        val stats = actif()
        stats.onFailure(RuntimeException())
        assertEquals("RuntimeException: sans message", stats.lastFailure)
    }

    @Test
    fun `le resume nomme les anomalies presentes et tait les autres`() {
        val stats = actif()
        stats.onBlock(submitted = 1098, resampled = 1608, written = 1608)
        val sain = stats.summary()
        assertTrue("tronq" !in sain, sain)
        assertTrue("vide" !in sain, sain)
        assertTrue("relance" !in sain, sain)
        assertTrue("err" !in sain, sain)

        stats.onBlock(submitted = 1098, resampled = 1608, written = 1000)
        stats.onUnderrunCount(4)
        stats.onRestart()
        stats.onFailure(IllegalStateException("x"))
        val degrade = stats.summary()
        assertTrue("tronq:1" in degrade, degrade)
        assertTrue("vide:4" in degrade, degrade)
        assertTrue("relance:1" in degrade, degrade)
        assertTrue("err:1" in degrade, degrade)
    }

    @Test
    fun `la remise a zero efface tout sauf l'activation`() {
        val stats = actif()
        stats.onBlock(submitted = 100, resampled = 150, written = 100)
        stats.onRestart()
        stats.onUnderrunCount(3)
        stats.onFailure(RuntimeException("x"))

        stats.reset()

        assertEquals(0L, stats.samplesSubmitted)
        assertEquals(0, stats.shortWrites)
        assertEquals(0, stats.restarts)
        assertEquals(0, stats.outputUnderruns)
        assertEquals(0, stats.failures)
        assertNull(stats.lastFailure)
        assertTrue(stats.enabled, "La remise à zéro ne doit pas éteindre le relevé")
    }
}
