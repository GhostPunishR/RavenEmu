package com.ravenemu.emulation.api.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asservissement du débit sur l'horloge de sortie.
 *
 * Les premières vérifications portent sur la loi de commande prise isolément.
 * La dernière est d'une autre nature : elle **rejoue une minute de son** à
 * travers le vrai rééchantillonneur, avec une horloge de sortie volontairement
 * fausse, et compare ce qui arrive avec et sans asservissement. C'est celle-là
 * qui dit si le problème est réellement réglé — les autres ne décrivent que la
 * mécanique.
 */
class AudioClockGovernorTest {

    private companion object {
        const val INPUT_RATE = 32768
        const val OUTPUT_RATE = 48000

        /** Trames produites par le moteur pour une trame vidéo à 59,8 Hz. */
        const val FRAMES_PER_BLOCK = 548

        /** Ce que cela devient en sortie, et l'avance visée (six blocs). */
        const val OUT_PER_BLOCK = 803
        const val TARGET = OUT_PER_BLOCK * 6
    }

    /** Écart de hauteur d'une correction, en centièmes de demi-ton. */
    private fun cents(scale: Double): Double = 1200.0 * ln(scale) / ln(2.0)

    @Test
    fun `a la cible, aucune correction n'est appliquee`() {
        val governor = AudioClockGovernor(TARGET)
        repeat(100) { assertEquals(1.0, governor.onQueuedFrames(TARGET)) }
    }

    /**
     * Dans la tolérance non plus : c'est le cas ordinaire, et il doit passer au
     * débit natif exact. Une correction permanente, même minuscule, n'aurait
     * aucune raison d'être et déplacerait la hauteur en pure perte.
     */
    @Test
    fun `dans la tolerance, le debit reste natif`() {
        val governor = AudioClockGovernor(TARGET, toleranceFrames = 200)
        repeat(50) { governor.onQueuedFrames(TARGET + 150) }
        assertEquals(1.0, governor.rateScale)
        repeat(50) { governor.onQueuedFrames(TARGET - 150) }
        assertEquals(1.0, governor.rateScale)
    }

    @Test
    fun `une avance excessive fait produire moins, un retard fait produire plus`() {
        val plein = AudioClockGovernor(TARGET)
        repeat(200) { plein.onQueuedFrames(TARGET * 2) }
        assertTrue(plein.rateScale > 1.0, "trop plein : ${plein.rateScale}")

        val vide = AudioClockGovernor(TARGET)
        repeat(200) { vide.onQueuedFrames(0) }
        assertTrue(vide.rateScale < 1.0, "trop vide : ${vide.rateScale}")
    }

    /**
     * La correction reste sous le seuil d'audibilité.
     *
     * C'est la contrainte qui justifie toute l'approche : corriger la dérive en
     * changeant la hauteur n'est acceptable que si le changement ne s'entend
     * pas. Dix centièmes de demi-ton sont l'ordre de grandeur du plus petit
     * écart perceptible sur un son tenu ; on reste en dessous.
     */
    @Test
    fun `la correction ne franchit jamais le seuil d'audibilite`() {
        for (queued in listOf(0, TARGET * 4, TARGET * 8)) {
            val governor = AudioClockGovernor(TARGET)
            repeat(1000) { governor.onQueuedFrames(queued) }
            assertTrue(
                abs(governor.rateScale - 1.0) <= AudioClockGovernor.MAX_CORRECTION + 1e-12,
                "avance $queued : ${governor.rateScale}",
            )
            assertTrue(abs(cents(governor.rateScale)) < 10.0, "avance $queued : ${cents(governor.rateScale)} cents")
        }
    }

    /**
     * La hauteur glisse, elle ne saute pas.
     *
     * Une correction appliquée d'un coup s'entendrait comme un accroc, même
     * petite. Chaque bloc ne peut la déplacer que d'un pas.
     */
    @Test
    fun `la hauteur ne peut pas sauter d'un bloc a l'autre`() {
        val governor = AudioClockGovernor(TARGET)
        var previous = governor.rateScale
        // Une alternance brutale entre les deux extrêmes : le pire cas.
        repeat(400) { index ->
            val queued = if (index % 2 == 0) 0 else TARGET * 8
            val current = governor.onQueuedFrames(queued)
            assertTrue(
                abs(current - previous) <= AudioClockGovernor.MAX_STEP + 1e-12,
                "saut de ${abs(current - previous)} au bloc $index",
            )
            previous = current
        }
    }

    /**
     * Un relevé inutilisable ne bouscule rien.
     *
     * Le compteur de trames jouées de la plateforme repart de zéro à chaque
     * vidage et déborde après une douzaine d'heures : l'avance calculée devient
     * alors négative ou énorme. Prendre ces valeurs au sérieux enverrait la
     * correction à sa borne pour rien.
     */
    @Test
    fun `un releve absurde laisse la correction en place`() {
        val governor = AudioClockGovernor(TARGET)
        repeat(200) { governor.onQueuedFrames(TARGET * 3) }
        val etabli = governor.rateScale
        assertTrue(etabli > 1.0)
        assertEquals(etabli, governor.onQueuedFrames(-1))
        assertEquals(etabli, governor.onQueuedFrames(Int.MAX_VALUE))
        assertEquals(etabli, governor.onQueuedFrames(TARGET * 64))
    }

    @Test
    fun `la reprise repart sans correction`() {
        val governor = AudioClockGovernor(TARGET)
        repeat(200) { governor.onQueuedFrames(0) }
        assertTrue(governor.rateScale < 1.0)
        governor.reset()
        assertEquals(1.0, governor.rateScale)
    }

    // ---- La boucle fermée, avec le vrai rééchantillonneur ----

    /**
     * Une minute de son, avec une horloge de sortie fausse de [drift].
     *
     * Le moteur remet un bloc par trame vidéo ; la sortie en consomme, pendant
     * ce temps, ce que son propre quartz lui dicte. Rend l'avance minimale et
     * l'avance maximale observées, en trames.
     */
    private fun simulate(drift: Double, governor: AudioClockGovernor?): Pair<Double, Double> {
        val resampler = BandLimitedResampler(INPUT_RATE, OUTPUT_RATE)
        val input = ShortArray(FRAMES_PER_BLOCK * 2) { 4000 }
        val output = ShortArray(resampler.maxOutput(input.size, 1.0 - AudioClockGovernor.MAX_CORRECTION))

        var queued = TARGET.toDouble()
        var lowest = queued
        var highest = queued
        val consumedPerBlock = FRAMES_PER_BLOCK.toDouble() * OUTPUT_RATE / INPUT_RATE * (1.0 + drift)

        repeat(3600) {
            val scale = governor?.onQueuedFrames(queued.toInt()) ?: 1.0
            queued += resampler.resample(input, input.size, output, scale) / 2
            queued -= consumedPerBlock
            // Une avance négative n'existe pas : la sortie s'est vidée, et ce
            // qui manquait est perdu. C'est la rupture qu'on cherche à éviter.
            if (queued < 0.0) queued = 0.0
            lowest = minOf(lowest, queued)
            highest = maxOf(highest, queued)
        }
        return lowest to highest
    }

    /**
     * Sans asservissement, une horloge un peu rapide vide la sortie.
     *
     * Trois millièmes d'écart — l'ordre de grandeur d'un quartz ordinaire —
     * retirent deux ou trois trames d'avance à chaque bloc. En une minute,
     * l'avance de départ y passe entièrement : c'est la rupture, suivie du
     * blanc de repréremplissage, et elle revient périodiquement.
     */
    @Test
    fun `sans asservissement la sortie finit par se vider`() {
        val (lowest, _) = simulate(drift = 0.003, governor = null)
        assertEquals(0.0, lowest, "la sortie aurait dû se vider, plancher $lowest")
    }

    /** Et dans l'autre sens, elle enfle sans limite : la latence grandit. */
    @Test
    fun `sans asservissement la sortie finit par deborder`() {
        val (_, highest) = simulate(drift = -0.003, governor = null)
        assertTrue(highest > TARGET * 2, "l'avance aurait dû enfler, sommet $highest")
    }

    /**
     * Avec l'asservissement, l'avance reste autour de sa cible, des deux côtés.
     *
     * C'est la même minute, la même horloge fausse, le même rééchantillonneur.
     * Seule la correction change.
     */
    @Test
    fun `l'asservissement tient le remplissage des deux cotes`() {
        for (drift in listOf(0.003, -0.003, 0.001, -0.001)) {
            val (lowest, highest) = simulate(drift, AudioClockGovernor(TARGET))
            // Jamais vide : c'est la rupture, et le blanc, qu'on supprime.
            assertTrue(lowest > 0.0, "dérive $drift : la sortie s'est vidée")
            // Et il reste de la marge des deux côtés : au moins deux trames
            // vidéo de réserve, sans que la latence double.
            assertTrue(lowest > TARGET / 3.0, "dérive $drift : plancher $lowest")
            assertTrue(highest < TARGET * 2.0, "dérive $drift : sommet $highest")
        }
    }
}
