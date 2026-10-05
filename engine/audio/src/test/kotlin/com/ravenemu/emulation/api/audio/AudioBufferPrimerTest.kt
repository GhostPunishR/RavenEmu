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

    /**
     * Le préremplissage ne se redéclenche jamais de lui-même.
     *
     * Il l'a fait, sur chaque rupture rapportée par la plateforme, et c'était le
     * mauvais réflexe : vider la piste jetait l'avance déjà calculée et imposait
     * une centaine de millisecondes de silence pour réparer une interruption qui
     * en durait quelques-unes. Après une rupture la file est vide, mais la piste
     * continue de jouer, et l'écriture bloquante ne bloque que sur une file
     * pleine : la réserve se reconstitue seule, en quelques blocs.
     *
     * Une fois démarrée, la lecture ne s'arrête donc plus que sur demande
     * explicite — pause ou arrêt de session, par [AudioBufferPrimer.reset].
     */
    @Test
    fun `la lecture demarree ne se rearme que sur demande explicite`() {
        val primer = AudioBufferPrimer(startThresholdSamples = 200)
        assertTrue(primer.onSamplesQueued(200))
        assertTrue(primer.playbackStarted)

        // Des blocs continuent d'arriver : rien ne redémarre, rien ne se remet
        // à compter.
        repeat(50) { assertFalse(primer.onSamplesQueued(200)) }
        assertTrue(primer.playbackStarted)

        primer.reset()
        assertFalse(primer.playbackStarted)
        assertEquals(0, primer.queuedSamples)
        assertFalse(primer.onSamplesQueued(120))
        assertTrue(primer.onSamplesQueued(80), "le seuil doit être repassé après un vidage")
    }

    @Test
    fun `le seuil doit etre strictement positif`() {
        assertFailsWith<IllegalArgumentException> {
            AudioBufferPrimer(startThresholdSamples = 0)
        }
    }
}
