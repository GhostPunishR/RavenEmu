package com.ravenemu.emulation.api.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Rééchantillonneur **à bande limitée**, en flux, PCM 16 bits stéréo entrelacé.
 *
 * ### Pourquoi pas une interpolation linéaire
 *
 * Relier deux échantillons par une droite revient à filtrer le signal par un
 * triangle, dont la réponse en fréquence décroît lentement : elle laisse
 * passer les **images** du spectre repliées autour du débit d'entrée, et
 * atténue en même temps le haut de la bande utile. À l'oreille, sur un son de
 * console — riche en harmoniques parce qu'il est fait de créneaux — cela donne
 * un aigu à la fois terne et graveleux.
 *
 * Ce rééchantillonneur convolue par un **sinus cardinal fenêtré**, la réponse
 * du filtre passe-bas idéal tronquée proprement. Les images tombent alors bien
 * plus bas que le bruit de quantification, et la bande utile reste plate.
 *
 * ### Ce que coûte cette qualité
 *
 * Les coefficients sont calculés **une fois** à la construction, pour
 * [PHASE_COUNT] positions fractionnaires. Chaque échantillon de sortie ne coûte
 * alors que [TAP_COUNT] multiplications-additions par voie, soit environ un
 * million et demi par seconde à quarante-huit kilohertz — négligeable devant
 * l'émulation elle-même, et sans aucune allocation dans la boucle.
 *
 * ### Le retard
 *
 * Un filtre symétrique regarde autant devant que derrière : la sortie est donc
 * en retard de la moitié du noyau, soit huit trames d'entrée, moins d'un quart
 * de milliseconde. Ce retard est constant et n'entre pas dans la boucle de
 * synchronisation.
 *
 * Quand les deux débits sont égaux et qu'aucune correction n'est demandée,
 * [resample] recopie l'entrée sans rien calculer.
 */
class BandLimitedResampler(
    private val inputRate: Int,
    private val outputRate: Int,
) {
    init {
        require(inputRate > 0 && outputRate > 0) { "Débits invalides" }
    }

    /** Trames d'entrée à avancer par trame de sortie à vitesse native. */
    private val baseStep = inputRate.toDouble() / outputRate

    /**
     * Noyau rangé par phase : [PHASE_COUNT] jeux de [TAP_COUNT] coefficients.
     *
     * Un seul tableau plat plutôt qu'un tableau de tableaux : le parcours d'une
     * phase est alors contigu en mémoire, ce qui compte dans une boucle appelée
     * quarante-huit mille fois par seconde.
     */
    private val kernel = DoubleArray(PHASE_COUNT * TAP_COUNT)

    /** Les dernières trames d'entrée, dont le noyau a besoin de part et d'autre. */
    private val historyLeft = DoubleArray(TAP_COUNT)
    private val historyRight = DoubleArray(TAP_COUNT)
    private var historyIndex = 0

    private var frac = 0.0

    val isIdentity: Boolean get() = inputRate == outputRate

    init {
        buildKernel()
    }

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
            val n = min(inputCount, output.size)
            System.arraycopy(input, 0, output, 0, n)
            return n
        }
        val step = baseStep * rateScale
        var written = 0
        val frames = inputCount / 2
        var frame = 0
        while (frame < frames) {
            historyLeft[historyIndex] = input[frame * 2].toDouble()
            historyRight[historyIndex] = input[frame * 2 + 1].toDouble()
            historyIndex = (historyIndex + 1) and TAP_MASK

            while (frac < 1.0) {
                if (written + 1 >= output.size) return written
                // La phase quantifie la position fractionnaire. Deux cent
                // cinquante-six positions laissent une erreur de placement bien
                // sous la marche de quantification du seize bits.
                val phase = (frac * PHASE_COUNT).toInt().coerceIn(0, PHASE_COUNT - 1)
                val base = phase * TAP_COUNT
                var accumulatedLeft = 0.0
                var accumulatedRight = 0.0
                // `historyIndex` désigne la case qui sera écrite ensuite, donc
                // la plus ancienne : le parcours part de là et remonte le temps.
                var slot = historyIndex
                for (tap in 0 until TAP_COUNT) {
                    val coefficient = kernel[base + tap]
                    accumulatedLeft += historyLeft[slot] * coefficient
                    accumulatedRight += historyRight[slot] * coefficient
                    slot = (slot + 1) and TAP_MASK
                }
                output[written++] = toSample(accumulatedLeft)
                output[written++] = toSample(accumulatedRight)
                frac += step
            }
            frac -= 1.0
            frame++
        }
        return written
    }

    /** Réinitialise l'état (à la reprise après une coupure du flux). */
    fun reset() {
        frac = 0.0
        historyLeft.fill(0.0)
        historyRight.fill(0.0)
        historyIndex = 0
    }

    /**
     * Arrondit et borne.
     *
     * Les deux comptent, et pour des raisons différentes. Tronquer vers zéro
     * creuserait une zone morte autour du silence, audible comme une aspérité
     * sur les fins de note. Et un noyau à bande limitée **dépasse** aux
     * transitions — c'est le phénomène de Gibbs, inévitable dès qu'on tronque
     * un sinus cardinal — si bien qu'un signal déjà proche du maximum peut
     * sortir au-delà : sans borne, il repartirait de l'autre extrême et
     * claquerait.
     */
    private fun toSample(value: Double): Short =
        value.roundToInt().coerceIn(-32768, 32767).toShort()

    private fun validateRateScale(rateScale: Double) {
        require(rateScale.isFinite() && rateScale > 0.0) { "Échelle de débit invalide" }
    }

    /**
     * Calcule le noyau, une fois pour toutes.
     *
     * La fréquence de coupure est celle de la plus basse des deux bandes : en
     * montant en débit il faut retenir les images au-dessus de la bande
     * d'entrée, en descendant il faut retirer ce que la sortie ne saurait plus
     * porter. [ROLLOFF] la place un peu en dessous, pour laisser au filtre la
     * place de descendre au lieu de couper net.
     *
     * La fenêtre est celle de Blackman : ses lobes secondaires tombent à
     * soixante-quatorze décibels, très au-dessous du bruit de quantification du
     * seize bits, ce qui met les images hors de portée de l'oreille.
     */
    private fun buildKernel() {
        val cutoff = ROLLOFF * 0.5 * min(1.0, outputRate.toDouble() / inputRate)
        for (phase in 0 until PHASE_COUNT) {
            val offset = phase.toDouble() / PHASE_COUNT
            var sum = 0.0
            for (tap in 0 until TAP_COUNT) {
                // Position du coefficient par rapport au point interpolé, en
                // trames d'entrée. Le centre du noyau tombe entre les deux
                // trames qui encadrent ce point.
                val distance = (tap - (CENTRE - 1)).toDouble() - offset
                val value = sinc(2.0 * cutoff * distance) * 2.0 * cutoff * window(tap, offset)
                kernel[phase * TAP_COUNT + tap] = value
                sum += value
            }
            // Chaque phase est ramenée à un gain unitaire : sans cela le niveau
            // ondulerait au rythme des phases, ce qui s'entend comme un
            // chuintement à la fréquence de battement des deux débits.
            if (sum != 0.0) {
                for (tap in 0 until TAP_COUNT) kernel[phase * TAP_COUNT + tap] /= sum
            }
        }
    }

    /** Fenêtre de Blackman, centrée sur le point interpolé. */
    private fun window(tap: Int, offset: Double): Double {
        val position = (tap.toDouble() + (1.0 - offset)) / TAP_COUNT
        if (position <= 0.0 || position >= 1.0) return 0.0
        val angle = 2.0 * PI * position
        return 0.42 - 0.5 * cos(angle) + 0.08 * cos(2.0 * angle)
    }

    private fun sinc(x: Double): Double {
        if (x == 0.0) return 1.0
        val angle = PI * x
        return sin(angle) / angle
    }

    companion object {
        /**
         * Longueur du noyau, en trames d'entrée.
         *
         * Seize suffisent à placer les images sous le bruit de quantification
         * du seize bits, sans que le retard introduit soit perceptible. Une
         * puissance de deux, parce que l'historique est parcouru au masque.
         */
        const val TAP_COUNT = 16
        private const val TAP_MASK = TAP_COUNT - 1
        private const val CENTRE = TAP_COUNT / 2

        /** Positions fractionnaires précalculées entre deux trames d'entrée. */
        const val PHASE_COUNT = 256

        /**
         * Part de la bande conservée avant la coupure.
         *
         * Couper exactement à la moitié du débit demanderait un filtre infini.
         * Descendre à quatre-vingt-onze centièmes laisse au noyau de seize
         * points la place de rejoindre le plancher, au prix d'un aigu qui
         * s'arrête vers quinze kilohertz — au-dessus de ce qu'une console de
         * cette époque produit, et de ce qu'un haut-parleur de téléphone rend.
         */
        private const val ROLLOFF = 0.91
    }
}
