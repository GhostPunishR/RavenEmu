package com.ravenemu.core.gb.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApuTest {

    /** Avance l'APU par petits pas pour laisser vivre les formes d'onde. */
    private fun Apu.run(cycles: Int) {
        var remaining = cycles
        while (remaining > 0) {
            val step = minOf(remaining, 8)
            tick(step)
            remaining -= step
        }
    }

    private fun Apu.drain(): ShortArray {
        val buffer = ShortArray(pendingSamples())
        readSamples(buffer)
        return buffer
    }

    private fun maxAmplitude(samples: ShortArray): Int =
        samples.maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0

    /** Amplitude crête-à-crête, insensible au décalage continu du DAC. */
    private fun peakToPeak(samples: ShortArray): Int {
        if (samples.isEmpty()) return 0
        return samples.max() - samples.min()
    }

    // ---- Registres ----

    @Test
    fun `nr52 au boot - apu allume sans canal actif`() {
        assertEquals(0xF0, Apu().read(0xFF26))
    }

    @Test
    fun `bits non cables lus a 1`() {
        val apu = Apu()
        apu.write(0xFF10, 0x00)
        assertEquals(0x80, apu.read(0xFF10)) // NR10
        apu.write(0xFF14, 0x00)
        assertEquals(0xBF, apu.read(0xFF14)) // NR14 : fréquence non relue
        assertEquals(0xFF, apu.read(0xFF15)) // registre inexistant
        assertEquals(0xFF, apu.read(0xFF27))
    }

    @Test
    fun `nr12 relu tel quel`() {
        val apu = Apu()
        apu.write(0xFF12, 0xF3)
        assertEquals(0xF3, apu.read(0xFF12))
    }

    @Test
    fun `wave ram accessible en lecture-ecriture`() {
        val apu = Apu()
        apu.write(0xFF30, 0xAB)
        apu.write(0xFF3F, 0x12)
        assertEquals(0xAB, apu.read(0xFF30))
        assertEquals(0x12, apu.read(0xFF3F))
    }

    @Test
    fun `extinction efface les registres et ignore les ecritures`() {
        val apu = Apu()
        apu.write(0xFF12, 0xF0)
        apu.write(0xFF26, 0x00) // extinction
        assertEquals(0x70, apu.read(0xFF26))
        apu.write(0xFF12, 0xF0) // ignorée
        assertEquals(0x00, apu.read(0xFF12))
        apu.write(0xFF26, 0x80) // rallumage
        assertEquals(0xF0, apu.read(0xFF26))
    }

    // ---- Cadence d'échantillonnage ----

    @Test
    fun `une trame produit environ 549 paires stereo`() {
        val apu = Apu()
        apu.run(70224)
        assertTrue(apu.pendingSamples() in 1096..1098, "obtenu ${apu.pendingSamples()}")
    }

    @Test
    fun `silence sans canal declenche`() {
        val apu = Apu()
        apu.run(70224)
        val samples = apu.drain()
        assertTrue(samples.isNotEmpty())
        assertEquals(0, maxAmplitude(samples))
    }

    @Test
    fun `readSamples vide le tampon`() {
        val apu = Apu()
        apu.run(70224)
        apu.drain()
        assertEquals(0, apu.pendingSamples())
    }

    // ---- Canaux ----

    private fun triggerSquare1(apu: Apu) {
        apu.write(0xFF12, 0xF0) // volume 15, DAC allumé
        apu.write(0xFF13, 0x00)
        apu.write(0xFF14, 0x87) // déclenchement, fréquence 0x700
    }

    @Test
    fun `canal carre produit un signal`() {
        val apu = Apu()
        triggerSquare1(apu)
        assertEquals(0x01, apu.read(0xFF26) and 0x0F) // canal 1 actif
        apu.run(70224)
        assertTrue(maxAmplitude(apu.drain()) > 0)
    }

    @Test
    fun `compteur de longueur eteint le canal`() {
        val apu = Apu()
        apu.write(0xFF12, 0xF0)
        apu.write(0xFF11, 0x3F) // longueur restante = 1
        apu.write(0xFF14, 0xC7) // déclenchement + longueur activée
        assertEquals(0x01, apu.read(0xFF26) and 0x0F)
        apu.run(Apu.FRAME_SEQUENCER_PERIOD * 2) // ≥ 1 horloge de longueur
        assertEquals(0x00, apu.read(0xFF26) and 0x0F)
    }

    @Test
    fun `dac eteint coupe le canal`() {
        val apu = Apu()
        triggerSquare1(apu)
        apu.write(0xFF12, 0x00) // DAC coupé
        assertEquals(0x00, apu.read(0xFF26) and 0x0F)
    }

    @Test
    fun `enveloppe decroit le volume`() {
        val apu = Apu()
        apu.write(0xFF12, 0xF1) // volume 15, décroissance, période 1
        apu.write(0xFF13, 0x00)
        apu.write(0xFF14, 0x87)
        apu.run(70224 * 2)
        val early = peakToPeak(apu.drain())
        apu.run(70224 * 10)
        val late = peakToPeak(apu.drain())
        assertTrue(late < early, "attendu $late < $early")
    }

    @Test
    fun `balayage augmente la frequence puis coupe sur debordement`() {
        val apu = Apu()
        apu.write(0xFF10, 0x11) // période 1, addition, décalage 1
        apu.write(0xFF12, 0xF0)
        apu.write(0xFF13, 0x00)
        apu.write(0xFF14, 0x84) // fréquence initiale 0x400
        assertEquals(0x01, apu.read(0xFF26) and 0x0F)
        // 0x400 → 0x600 → 0x900 : débordement au second pas → canal coupé.
        apu.run(Apu.FRAME_SEQUENCER_PERIOD * 8)
        assertEquals(0x00, apu.read(0xFF26) and 0x0F)
    }

    @Test
    fun `canal bruit produit un signal`() {
        val apu = Apu()
        apu.write(0xFF21, 0xF0)
        apu.write(0xFF22, 0x00)
        apu.write(0xFF23, 0x80)
        assertEquals(0x08, apu.read(0xFF26) and 0x0F)
        apu.run(70224)
        assertTrue(maxAmplitude(apu.drain()) > 0)
    }

    @Test
    fun `canal onde lit la wave ram`() {
        val apu = Apu()
        for (i in 0 until 16) apu.write(0xFF30 + i, 0xFF)
        apu.write(0xFF1A, 0x80) // DAC allumé
        apu.write(0xFF1C, 0x20) // volume 100 %
        apu.write(0xFF1D, 0x00)
        apu.write(0xFF1E, 0x87)
        assertEquals(0x04, apu.read(0xFF26) and 0x0F)
        apu.run(70224)
        assertTrue(maxAmplitude(apu.drain()) > 0)
    }

    @Test
    fun `panoramique nr51 route les canaux`() {
        val apu = Apu()
        apu.write(0xFF25, 0x01) // canal 1 à droite uniquement
        triggerSquare1(apu)
        apu.run(70224)
        val samples = apu.drain()
        var left = 0
        var right = 0
        for (i in samples.indices step 2) {
            left = maxOf(left, kotlin.math.abs(samples[i].toInt()))
            right = maxOf(right, kotlin.math.abs(samples[i + 1].toInt()))
        }
        assertTrue(right > 0)
        assertEquals(0, left) // aucun canal routé à gauche
    }
}
