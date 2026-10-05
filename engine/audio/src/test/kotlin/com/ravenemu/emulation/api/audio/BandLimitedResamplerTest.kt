package com.ravenemu.emulation.api.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rééchantillonneur à bande limitée.
 *
 * Deux familles de vérifications, et elles ne disent pas la même chose.
 *
 * Les premières portent sur le **contrat** : nombre de trames rendues,
 * continuité entre deux blocs, absence de dépassement. Elles passeraient sur
 * n'importe quel rééchantillonneur, y compris un mauvais.
 *
 * Les dernières portent sur la **qualité**, et c'est là que se joue la raison
 * d'être de cette classe. Elles mesurent ce qui sort à des fréquences où rien
 * ne devrait sortir, et posent un seuil chiffré. Sans elles, quelqu'un pourrait
 * remplacer le noyau par une droite et tous les autres tests passeraient.
 */
class BandLimitedResamplerTest {

    /** Génère un bloc stéréo entrelacé de [frames] trames via [gen]. */
    private fun stereo(frames: Int, gen: (Int) -> Pair<Int, Int>): ShortArray {
        val out = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val (l, r) = gen(f)
            out[f * 2] = l.toShort()
            out[f * 2 + 1] = r.toShort()
        }
        return out
    }

    /**
     * Trames de sortie à écarter avant de mesurer un régime établi.
     *
     * L'historique du noyau met [BandLimitedResampler.TAP_COUNT] trames à se
     * remplir, et ces trames-là sont des trames **d'entrée**. Une trame d'entrée
     * en vaut `outputRate / inputRate` en sortie : de 32 768 vers 48 000, les
     * seize trames d'amorçage en couvrent près de vingt-quatre. Compter
     * l'amorçage en trames de sortie, comme si les deux cadences n'en faisaient
     * qu'une, reviendrait à mesurer le transitoire en croyant mesurer le régime
     * établi — et à faire échouer un rééchantillonneur correct.
     *
     * [rateScale] entre dans le calcul pour la même raison : ralentir la cadence
     * allonge d'autant la sortie que couvre le même amorçage.
     */
    private fun settleFrames(inputRate: Int, outputRate: Int, rateScale: Double = 1.0): Int {
        val perInputFrame = outputRate / (inputRate.toDouble() * rateScale)
        return ceil(BandLimitedResampler.TAP_COUNT * perInputFrame).toInt() + 1
    }

    @Test
    fun `identite recopie l'entree`() {
        val r = BandLimitedResampler(48000, 48000)
        assertTrue(r.isIdentity)
        val input = stereo(10) { it to -it }
        val out = ShortArray(20)
        val n = r.resample(input, input.size, out)
        assertEquals(20, n)
        assertContentEquals(input, out.copyOf(n))
    }

    @Test
    fun `sur-echantillonnage augmente le nombre de trames`() {
        val r = BandLimitedResampler(32768, 48000)
        val input = stereo(328) { 1000 to 1000 }
        val out = ShortArray(r.maxOutput(input.size))
        val n = r.resample(input, input.size, out)
        val outFrames = n / 2
        // 328 * 48000/32768 ≈ 480 trames.
        assertTrue(outFrames in 476..484, "obtenu $outFrames")
    }

    @Test
    fun `sous-echantillonnage reduit le nombre de trames`() {
        val r = BandLimitedResampler(48000, 16000)
        val input = stereo(480) { 500 to 500 }
        val out = ShortArray(r.maxOutput(input.size))
        val n = r.resample(input, input.size, out)
        val outFrames = n / 2
        // 480 * 16000/48000 = 160 trames.
        assertTrue(outFrames in 158..162, "obtenu $outFrames")
    }

    /**
     * Un niveau tenu ressort au même niveau.
     *
     * C'est la vérification du gain continu, et elle ne va pas de soi : un
     * noyau tronqué n'a un gain unitaire que si chaque phase est ramenée à la
     * somme un. Sans cette normalisation, le niveau ondulerait au rythme des
     * phases — un chuintement à la fréquence de battement des deux débits.
     *
     * L'amorçage dure le temps que l'historique se remplisse, soit la longueur
     * du noyau ; c'est après qu'on mesure.
     */
    @Test
    fun `un signal constant reste constant`() {
        val r = BandLimitedResampler(32768, 48000)
        val input = stereo(400) { 2000 to -2000 }
        val out = ShortArray(r.maxOutput(input.size))
        val n = r.resample(input, input.size, out)
        for (f in settleFrames(32768, 48000) until n / 2) {
            assertEquals(2000, out[f * 2].toInt(), "trame $f")
            assertEquals(-2000, out[f * 2 + 1].toInt(), "trame $f")
        }
    }

    @Test
    fun `continuite entre deux blocs`() {
        val r = BandLimitedResampler(32768, 48000)
        val blockA = stereo(200) { 3000 to 3000 }
        val blockB = stereo(200) { 3000 to 3000 }
        val out = ShortArray(r.maxOutput(blockA.size))
        r.resample(blockA, blockA.size, out)
        val n = r.resample(blockB, blockB.size, out)
        // Le second bloc, précédé du même niveau, ne réintroduit pas d'amorçage.
        for (f in 0 until n / 2) {
            assertEquals(3000, out[f * 2].toInt(), "trame $f")
        }
    }

    @Test
    fun `le debit de sortie moyen suit le ratio sur une longue serie`() {
        val r = BandLimitedResampler(32768, 44100)
        var totalIn = 0
        var totalOut = 0
        val out = ShortArray(4096)
        repeat(100) {
            val input = stereo(549) { 100 to 100 }
            totalIn += 549
            totalOut += r.resample(input, input.size, out) / 2
        }
        val ratio = totalOut.toDouble() / totalIn
        assertTrue(abs(ratio - 44100.0 / 32768.0) < 0.01, "ratio $ratio")
    }

    @Test
    fun `une cadence ralentie etire le bloc sans rupture`() {
        val input = stereo(549) { 2000 to -2000 }

        val normal = BandLimitedResampler(32768, 48000)
        val normalOut = ShortArray(normal.maxOutput(input.size))
        val normalFrames = normal.resample(input, input.size, normalOut) / 2

        val slowed = BandLimitedResampler(32768, 48000)
        val scale = 0.86
        val slowedOut = ShortArray(slowed.maxOutput(input.size, scale))
        val slowedCount = slowed.resample(input, input.size, slowedOut, scale)
        val slowedFrames = slowedCount / 2

        assertTrue(slowedFrames > normalFrames)
        assertTrue(
            abs(slowedFrames.toDouble() / normalFrames - 1.0 / scale) < 0.02,
            "ratio ralenti : ${slowedFrames.toDouble() / normalFrames}",
        )
        for (f in settleFrames(32768, 48000, scale) until slowedFrames) {
            assertEquals(2000, slowedOut[f * 2].toInt(), "trame $f")
        }
    }

    @Test
    fun `pas de depassement si la sortie est trop petite`() {
        val r = BandLimitedResampler(32768, 48000)
        val input = stereo(300) { 1000 to 1000 }
        val small = ShortArray(50)
        val n = r.resample(input, input.size, small)
        assertTrue(n <= 50)
    }

    // ---- Qualité : ce que le contrat ne dit pas ----

    /**
     * Énergie du signal à une fréquence donnée, par l'algorithme de Goertzel.
     *
     * Une transformée entière serait un outil disproportionné : on ne veut la
     * réponse qu'à trois fréquences connues d'avance. Une fenêtre de Hann est
     * appliquée pour que la fuite d'une raie vers ses voisines ne vienne pas
     * masquer ce qu'on cherche à mesurer, qui est justement très faible.
     */
    private fun magnitudeAt(samples: DoubleArray, rate: Int, frequency: Double): Double {
        val n = samples.size
        val omega = 2.0 * PI * frequency / rate
        val cosine = cos(omega)
        val sine = sin(omega)
        var previous = 0.0
        var beforePrevious = 0.0
        for (i in 0 until n) {
            val window = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            val current = 2.0 * cosine * previous - beforePrevious + samples[i] * window
            beforePrevious = previous
            previous = current
        }
        val real = previous - beforePrevious * cosine
        val imaginary = beforePrevious * sine
        return sqrt(real * real + imaginary * imaginary) / n
    }

    /** Rééchantillonne une sinusoïde et rend la voie gauche en réels. */
    private fun sweepThrough(
        resample: (ShortArray, Int, ShortArray) -> Int,
        inputRate: Int,
        frequency: Double,
        frames: Int,
        outputCapacity: Int,
    ): DoubleArray {
        val input = ShortArray(frames * 2)
        for (f in 0 until frames) {
            val value = (28000.0 * sin(2.0 * PI * frequency * f / inputRate)).toInt().toShort()
            input[f * 2] = value
            input[f * 2 + 1] = value
        }
        val output = ShortArray(outputCapacity)
        val produced = resample(input, input.size, output)
        // L'amorçage du noyau est écarté : il porte un transitoire qui n'est pas
        // le régime établi qu'on mesure.
        val start = BandLimitedResampler.TAP_COUNT * 2
        val frameCount = produced / 2 - start
        return DoubleArray(frameCount) { output[(start + it) * 2].toDouble() }
    }

    /**
     * Les images du spectre tombent sous le bruit du seize bits.
     *
     * Une sinusoïde à quatre kilohertz échantillonnée à 32 768 Hz possède des
     * images autour de ce débit. Portées à 48 000 Hz, elles se replient à
     * 11 232 Hz et 19 232 Hz — en pleine bande audible. C'est exactement ce
     * qu'un rééchantillonneur doit retenir, et ce qu'une interpolation linéaire
     * laisse largement passer.
     *
     * Le seuil est chiffré parce qu'un seuil implicite ne se défend pas :
     * cinquante-cinq décibels placent ces images au-dessous du plancher de
     * quantification d'un signal de cette amplitude.
     */
    @Test
    fun `les images du spectre sont rejetees`() {
        val inputRate = 32768
        val outputRate = 48000
        val tone = 4000.0
        val r = BandLimitedResampler(inputRate, outputRate)
        val samples = sweepThrough(
            { input, count, output -> r.resample(input, count, output) },
            inputRate,
            tone,
            frames = 8192,
            outputCapacity = r.maxOutput(8192 * 2),
        )

        val fundamental = magnitudeAt(samples, outputRate, tone)
        assertTrue(fundamental > 1000.0, "la fondamentale est présente : $fundamental")

        for (image in listOf(inputRate - tone, inputRate + tone)) {
            val folded = fold(image, outputRate)
            val level = magnitudeAt(samples, outputRate, folded)
            val decibels = 20.0 * log10(level / fundamental)
            assertTrue(decibels < -55.0, "image à $folded Hz : $decibels dB")
        }
    }

    /**
     * La bande utile reste plate.
     *
     * Un filtre qui rejette bien les images mais mange l'aigu ne vaut guère
     * mieux : il rend le son terne. La mesure porte donc aussi sur ce qui doit
     * passer, à trois hauteurs réparties dans la bande d'un morceau de console.
     */
    @Test
    fun `la bande utile traverse sans perte`() {
        val inputRate = 32768
        val outputRate = 48000
        for (tone in listOf(220.0, 1000.0, 5000.0)) {
            val r = BandLimitedResampler(inputRate, outputRate)
            val samples = sweepThrough(
                { input, count, output -> r.resample(input, count, output) },
                inputRate,
                tone,
                frames = 8192,
                outputCapacity = r.maxOutput(8192 * 2),
            )
            val level = magnitudeAt(samples, outputRate, tone)
            // La fenêtre de Hann divise l'amplitude par deux : la référence en
            // tient compte, sinon le seuil mesurerait la fenêtre et non le
            // filtre.
            val expected = 28000.0 / 2.0 / 2.0
            val decibels = 20.0 * log10(level / expected)
            assertTrue(abs(decibels) < 1.0, "$tone Hz : $decibels dB")
        }
    }

    /**
     * Et une interpolation linéaire n'y arriverait pas.
     *
     * La droite qui relie deux échantillons est ce que cette classe remplace.
     * Elle est reproduite ici, en quelques lignes, pour que le seuil ci-dessus
     * ne soit pas un chiffre en l'air : il sépare deux choses réelles, et
     * quiconque simplifierait le noyau retomberait de ce côté-ci.
     */
    @Test
    fun `une interpolation lineaire ne tiendrait pas le seuil`() {
        val inputRate = 32768
        val outputRate = 48000
        val tone = 4000.0
        val step = inputRate.toDouble() / outputRate

        val frames = 8192
        val produced = ArrayList<Double>(frames * 2)
        var previous = 0.0
        var frac = 0.0
        for (f in 0 until frames) {
            val current = 28000.0 * sin(2.0 * PI * tone * f / inputRate)
            while (frac < 1.0) {
                produced.add(previous + (current - previous) * frac)
                frac += step
            }
            frac -= 1.0
            previous = current
        }
        val start = BandLimitedResampler.TAP_COUNT * 2
        val samples = DoubleArray(produced.size - start) { produced[start + it] }

        val fundamental = magnitudeAt(samples, outputRate, tone)
        val worst = listOf(inputRate - tone, inputRate + tone).maxOf { image ->
            val level = magnitudeAt(samples, outputRate, fold(image, outputRate))
            20.0 * log10(level / fundamental)
        }
        assertTrue(worst > -55.0, "la droite laisserait passer bien plus : $worst dB")
    }

    /** Replie une fréquence au-dessus de la moitié du débit dans la bande. */
    private fun fold(frequency: Double, rate: Int): Double {
        var value = frequency % rate
        if (value > rate / 2.0) value = rate - value
        return value
    }
}
