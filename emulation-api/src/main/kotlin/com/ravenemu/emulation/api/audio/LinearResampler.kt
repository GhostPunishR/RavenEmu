package com.ravenemu.emulation.api.audio

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

    /** Trames d'entrée à avancer par trame de sortie. */
    private val step = inputRate.toDouble() / outputRate

    private var frac = 0.0
    private var lastL = 0.0
    private var lastR = 0.0

    val isIdentity: Boolean get() = inputRate == outputRate

    /** Majorant du nombre de shorts produits pour [inputCount] shorts d'entrée. */
    fun maxOutput(inputCount: Int): Int {
        if (isIdentity) return inputCount
        val inFrames = (inputCount / 2).toLong()
        return (((inFrames * outputRate) / inputRate) + 2).toInt() * 2
    }

    /**
     * Rééchantillonne les [inputCount] premiers shorts de [input] (paires L/R)
     * vers [output] et retourne le nombre de shorts écrits (toujours pair).
     * L'écriture s'arrête si [output] est plein (jamais de dépassement).
     */
    fun resample(input: ShortArray, inputCount: Int, output: ShortArray): Int {
        if (isIdentity) {
            val n = minOf(inputCount, output.size)
            System.arraycopy(input, 0, output, 0, n)
            return n
        }
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

    /** Réinitialise l'état (à la reprise après une coupure du flux). */
    fun reset() {
        frac = 0.0
        lastL = 0.0
        lastR = 0.0
    }
}
