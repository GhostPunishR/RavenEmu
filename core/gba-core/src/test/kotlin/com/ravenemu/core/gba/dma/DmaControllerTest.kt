package com.ravenemu.core.gba.dma

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.interrupt.Interrupt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DmaControllerTest {

    private fun machine() = GbaMachine(SyntheticRom.build())

    @Test
    fun `transfert immédiat de mots 32 bits`() {
        val m = machine()
        val bus = m.bus
        for (i in 0 until 4) bus.write32(0x0200_0000 + i * 4, 0x1111_1111 * (i + 1))
        bus.write32(0x0400_00B0, 0x0200_0000) // SAD
        bus.write32(0x0400_00B4, 0x0200_1000) // DAD
        bus.write16(0x0400_00B8, 4)           // CNT_L : 4 mots
        bus.write16(0x0400_00BA, 0x8400)      // activé + 32 bits + immédiat

        for (i in 0 until 4) {
            assertEquals(0x1111_1111 * (i + 1), bus.read32(0x0200_1000 + i * 4))
        }
    }

    @Test
    fun `l'activation est effacée après un transfert immédiat non répété`() {
        val m = machine()
        val bus = m.bus
        bus.write32(0x0400_00B0, 0x0200_0000)
        bus.write32(0x0400_00B4, 0x0200_1000)
        bus.write16(0x0400_00B8, 1)
        bus.write16(0x0400_00BA, 0x8400)
        // Le bit d'activation (0x8000) doit être retombé.
        assertEquals(0, bus.read16(0x0400_00BA) and 0x8000)
    }

    @Test
    fun `transfert de demi-mots avec destination fixe`() {
        val m = machine()
        val bus = m.bus
        bus.write16(0x0200_0000, 0xABCD)
        bus.write16(0x0200_0002, 0x1234)
        bus.write32(0x0400_00B0, 0x0200_0000)
        bus.write32(0x0400_00B4, 0x0200_1000)
        bus.write16(0x0400_00B8, 2)
        // Contrôle : activé + destination fixe (bits 5-6 = 10 → 0x40) + 16 bits + immédiat.
        bus.write16(0x0400_00BA, 0x8000 or 0x0040)
        // Source incrémentée, destination fixe : les deux demi-mots vont à la
        // même adresse, le dernier lu subsiste.
        assertEquals(0x1234, bus.read16(0x0200_1000))
    }

    @Test
    fun `l'IRQ de fin de DMA est levée`() {
        val m = machine()
        val bus = m.bus
        bus.write32(0x0400_00B0, 0x0200_0000)
        bus.write32(0x0400_00B4, 0x0200_1000)
        bus.write16(0x0400_00B8, 1)
        bus.write16(0x0400_00BA, 0x8400 or 0x4000) // + IRQ de fin
        assertTrue(m.interrupts.flags and (1 shl Interrupt.DMA0) != 0)
    }
}
