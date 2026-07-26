package com.ravenemu.emulation.api.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioBufferPrimerTest {

    @Test
    fun `la lecture commence seulement apres le seuil`() {
        val primer = AudioBufferPrimer(startThresholdSamples = 300)

        assertFalse(primer.onSamplesQueued(120))
        assertFalse(primer.onSamplesQueued(120))
        assertTrue(primer.onSamplesQueued(60))
        assertTrue(primer.playbackStarted)
        assertEquals(300, primer.queuedSamples)

        assertFalse(primer.onSamplesQueued(300), "la piste ne doit pas redémarrer")
    }

    @Test
    fun `une nouvelle rupture impose un nouveau preremplissage`() {
        val primer = AudioBufferPrimer(startThresholdSamples = 200)
        assertTrue(primer.onSamplesQueued(200))

        assertFalse(primer.onUnderrunCount(0))
        assertTrue(primer.onUnderrunCount(1))
        assertFalse(primer.playbackStarted)
        assertEquals(0, primer.queuedSamples)

        assertFalse(primer.onSamplesQueued(120))
        assertTrue(primer.onSamplesQueued(80))
    }

    @Test
    fun `un ancien compteur ne casse pas un preremplissage en cours`() {
        val primer = AudioBufferPrimer(startThresholdSamples = 200)

        assertFalse(primer.onUnderrunCount(12))
        assertFalse(primer.onSamplesQueued(100))
        assertFalse(primer.onUnderrunCount(12))
        assertTrue(primer.onSamplesQueued(100))
        assertFalse(primer.onUnderrunCount(12))
    }

    @Test
    fun `reset conserve le compteur courant et vide la piste`() {
        val primer = AudioBufferPrimer(startThresholdSamples = 100)
        assertTrue(primer.onSamplesQueued(100))

        primer.reset(currentUnderruns = 7)

        assertFalse(primer.playbackStarted)
        assertEquals(0, primer.queuedSamples)
        assertFalse(primer.onUnderrunCount(7))
        assertTrue(primer.onSamplesQueued(100))
        assertTrue(primer.onUnderrunCount(8))
    }

    @Test
    fun `le seuil doit etre strictement positif`() {
        assertFailsWith<IllegalArgumentException> {
            AudioBufferPrimer(startThresholdSamples = 0)
        }
    }
}
