package com.ravenemu.emulation.api.audio

import kotlin.math.ceil

/**
 * Rééchantillonneur linéaire **en flux**, PCM 16 bits stéréo entrelacé.
 *
 * Convertit un flux à [inputRate] vers [outputRate] par interpolation
 * linéaire, en conservant son état d'un appel à l'autre (continuité entre les
 * blocs successifs, donc aucun clic aux jonctions). Il permet de sortir
 * l'audio du moteur au débit natif du périphérique et d'éviter le
 * rééchantillonneur — de qualité variable — du système.
 *
 * Quand les deux débits sont égaux, [resample] recopie l'entrée sans
 * traitement.
 */
class LinearResampler(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    init {
        require(inputRate > 0 && outputRate > 0) { "Débits invalides" }
    }

    /** Trames d'entrée à avancer par trame de sortie à vitesse native. */
    private val baseStep = inputRate.toDouble() / outputRate

    private var frac = 0.0
    private var lastL = 0.0
    private var lastR = 0.0

    val isIdentity: Boolean get() = inputRate == outputRate

    /**
     * Majorant du nombre de shorts produits pour [inputCount] shorts d'entrée.
     * [rateScale] vaut 1 à vitesse native et descend sous 1 pour étirer le son.
     */
    fun maxOutput(inputCount: Int, rateScale: Double = 1.0): Int {
        validateRateScale(rateScale)
        if (isIdentity && rateScale == 1.0) return inputCount
        val inFrames = inputCount / 2
        return (ceil(inFrames / (baseStep * rateScale)).toInt() + 2) * 2
    }

    /**
     * Rééchantillonne les [inputCount] premiers shorts de [input] (paires L/R)
     * vers [output] et retourne le nombre de shorts écrits (toujours pair).
     * L'écriture s'arrête si [output] est plein (jamais de dépassement).
     */
    fun resample(
        input: ShortArray,
        inputCount: Int,
        output: ShortArray,
        rateScale: Double = 1.0,
    ): Int {
        validateRateScale(rateScale)
        if (isIdentity && rateScale == 1.0) {
            val n = minOf(inputCount, output.size)
            System.arraycopy(input, 0, output, 0, n)
            return n
        }
        val step = baseStep * rateScale
        var o = 0
        var i = 0
        val frames = inputCount / 2
        while (i < frames) {
            val curL = input[i * 2].toDouble()
            val curR = input[i * 2 + 1].toDouble()
            while (frac < 1.0) {
                if (o + 1 >= output.size) return o
                output[o++] = (lastL + (curL - lastL) * frac).toInt().toShort()
                output[o++] = (lastR + (curR - lastR) * frac).toInt().toShort()
                frac += step
            }
            frac -= 1.0
            lastL = curL
            lastR = curR
            i++
        }
        return o
    }

    private fun validateRateScale(rateScale: Double) {
        require(rateScale.isFinite() && rateScale > 0.0) { "Échelle de débit invalide" }
    }

    /** Réinitialise l'état (à la reprise après une coupure du flux). */
    fun reset() {
        frac = 0.0
        lastL = 0.0
        lastR = 0.0
    }
}
