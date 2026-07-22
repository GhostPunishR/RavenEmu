package com.ravenemu.emulation.api.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinearResamplerTest {

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

    @Test
    fun `identite recopie l'entree`() {
        val r = LinearResampler(48000, 48000)
        assertTrue(r.isIdentity)
        val input = stereo(10) { it to -it }
        val out = ShortArray(20)
        val n = r.resample(input, input.size, out)
        assertEquals(20, n)
        assertContentEquals(input, out.copyOf(n))
    }

    @Test
    fun `sur-echantillonnage augmente le nombre de trames`() {
        val r = LinearResampler(32768, 48000)
        val input = stereo(328) { 1000 to 1000 }
        val out = ShortArray(r.maxOutput(input.size))
        val n = r.resample(input, input.size, out)
        val outFrames = n / 2
        // 328 * 48000/32768 ≈ 480 trames.
        assertTrue(outFrames in 476..484, "obtenu $outFrames")
    }

    @Test
    fun `sous-echantillonnage reduit le nombre de trames`() {
        val r = LinearResampler(48000, 16000)
        val input = stereo(480) { 500 to 500 }
        val out = ShortArray(r.maxOutput(input.size))
        val n = r.resample(input, input.size, out)
        val outFrames = n / 2
        // 480 * 16000/48000 = 160 trames.
        assertTrue(outFrames in 158..162, "obtenu $outFrames")
    }

    @Test
    fun `un signal constant reste constant apres interpolation`() {
        val r = LinearResampler(32768, 48000)
        val input = stereo(400) { 2000 to -2000 }
        val out = ShortArray(r.maxOutput(input.size))
        val n = r.resample(input, input.size, out)
        // Hors toute première trame (amorçage depuis 0), le niveau est tenu.
        for (f in 5 until n / 2) {
            assertEquals(2000, out[f * 2].toInt())
            assertEquals(-2000, out[f * 2 + 1].toInt())
        }
    }

    @Test
    fun `continuite entre deux blocs`() {
        val r = LinearResampler(32768, 48000)
        val blockA = stereo(200) { 3000 to 3000 }
        val blockB = stereo(200) { 3000 to 3000 }
        val out = ShortArray(r.maxOutput(blockA.size))
        r.resample(blockA, blockA.size, out)
        val n = r.resample(blockB, blockB.size, out)
        // Le second bloc, précédé du même niveau, ne réintroduit pas d'amorçage :
        // toutes ses trames valent 3000.
        for (f in 0 until n / 2) {
            assertEquals(3000, out[f * 2].toInt(), "trame $f")
        }
    }

    @Test
    fun `le debit de sortie moyen suit le ratio sur une longue serie`() {
        val r = LinearResampler(32768, 44100)
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
    fun `pas de depassement si la sortie est trop petite`() {
        val r = LinearResampler(32768, 48000)
        val input = stereo(300) { 1000 to 1000 }
        val small = ShortArray(50)
        val n = r.resample(input, input.size, small)
        assertTrue(n <= 50)
    }
}
