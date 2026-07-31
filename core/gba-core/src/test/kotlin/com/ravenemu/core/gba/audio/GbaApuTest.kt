package com.ravenemu.core.gba.audio

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GbaApuTest {

    private fun machine() = GbaMachine(SyntheticRom.build())

    /** Active la sortie audio maîtresse et le mixage PSG à pleine proportion. */
    private fun enableSound(bus: com.ravenemu.core.gba.memory.GbaBus) {
        bus.write16(0x0400_0084, 0x0080) // SOUNDCNT_X : audio activé
        bus.write16(0x0400_0082, 0x0002) // SOUNDCNT_H : PSG à 100 %
        bus.write16(0x0400_0080, 0x77FF) // SOUNDCNT_L : tous canaux, volume max
    }

    @Test
    fun `sans activation aucun son n'est produit`() {
        val m = machine()
        m.apu.tick(4 * 128 * 16) // seize échantillons
        val buffer = ShortArray(32)
        val count = m.apu.readSamples(buffer)
        assertTrue(count > 0, "des échantillons doivent être produits")
        assertTrue(buffer.take(count).all { it.toInt() == 0 }, "le silence est attendu")
    }

    @Test
    fun `un canal carre declenche produit un signal non nul`() {
        val m = machine()
        val bus = m.bus
        enableSound(bus)
        // Canal 1 : rapport cyclique 50 %, volume maximal, puis déclenchement.
        bus.write16(0x0400_0062, 0x0080 or 0xF000.toInt())
        bus.write16(0x0400_0064, 0x8400) // déclenchement, fréquence 0x400
        m.apu.tick(4 * 128 * 64)

        val buffer = ShortArray(256)
        val count = m.apu.readSamples(buffer)
        assertTrue(
            buffer.take(count).any { it.toInt() != 0 },
            "le canal carré doit produire un signal",
        )
    }

    @Test
    fun `la cadence d'echantillonnage est respectee`() {
        val m = machine()
        enableSound(m.bus)
        // Une trame complète doit produire environ 32768 / 59,73 échantillons
        // par canal, soit le double en stéréo entrelacé.
        m.apu.tick(GbaCore.CYCLES_PER_FRAME)
        val buffer = ShortArray(4096)
        val count = m.apu.readSamples(buffer)
        val expected = 2 * GbaApu.SAMPLE_RATE_HZ / 60
        assertTrue(count in (expected - 40)..(expected + 40), "obtenu $count, attendu ~$expected")
    }

    @Test
    fun `une ecriture FIFO empile les echantillons`() {
        val m = machine()
        m.bus.write16(0x0400_00A0, 0x1234)
        assertEquals(2, m.apu.fifoSize(0))
        m.bus.write16(0x0400_00A4, 0x5678)
        assertEquals(2, m.apu.fifoSize(1))
    }

    @Test
    fun `le debordement du timer consomme un echantillon Direct Sound`() {
        val m = machine()
        val bus = m.bus
        enableSound(bus)
        // Direct Sound A cadencé par le timer 0, panoramique gauche et droite.
        bus.write16(0x0400_0082, 0x0002 or 0x0300)
        repeat(4) { bus.write16(0x0400_00A0, 0x4040) } // 8 octets d'échantillons
        val before = m.apu.fifoSize(0)
        assertEquals(8, before)

        m.apu.onTimerOverflow(0)
        assertEquals(before - 1, m.apu.fifoSize(0), "un octet doit être consommé")
    }

    @Test
    fun `une FIFO presque vide demande un reapprovisionnement`() {
        val m = machine()
        val requested = mutableListOf<Int>()
        m.apu.onFifoRequest = { channel -> requested += channel }
        // SOUNDCNT_H à zéro : les deux canaux Direct Sound suivent le timer 0,
        // leurs deux FIFO vides réclament donc un réapprovisionnement.
        m.apu.onTimerOverflow(0)
        assertEquals(listOf(0, 1), requested)
    }

    @Test
    fun `le DMA en mode son remplit la FIFO`() {
        val m = machine()
        val bus = m.bus
        // Échantillons source en EWRAM.
        for (i in 0 until 4) bus.write32(0x0200_0000 + i * 4, 0x40404040)
        // Canal DMA 1 : source EWRAM, destination FIFO A, mode spécial.
        bus.write32(0x0400_00BC, 0x0200_0000)
        bus.write32(0x0400_00C0, 0x0400_00A0)
        bus.write16(0x0400_00C4, 4)
        bus.write16(0x0400_00C6, 0x8000 or 0x3000 or 0x0400) // activé, spécial, 32 bits

        m.dma.triggerSoundFifo(0)
        assertEquals(16, m.apu.fifoSize(0), "quatre mots doivent être transférés")
    }

    @Test
    fun `readAudio du coeur draine les echantillons`() {
        val core = GbaCore()
        core.loadRom(SyntheticRom.build())
        core.machine!!.bus.write16(0x0400_0084, 0x0080)
        val framebuffer = IntArray(core.video.pixelCount)
        core.runFrame(framebuffer)

        val buffer = ShortArray(4096)
        assertTrue(core.readAudio(buffer) > 0, "le cœur doit exposer des échantillons")
    }
}
