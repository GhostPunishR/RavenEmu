package com.ravenemu.core.gba.memory

import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.cartridge.GbaCartridge
import kotlin.test.Test
import kotlin.test.assertEquals

class GbaBusTest {

    private fun bus() = GbaBus(GbaCartridge.create(SyntheticRom.build()))

    @Test
    fun `ecrit et relit un mot en EWRAM`() {
        val bus = bus()
        bus.write32(0x0200_0000, 0x1234_5678)
        assertEquals(0x1234_5678, bus.read32(0x0200_0000))
    }

    @Test
    fun `octets d'un mot en petit-boutiste`() {
        val bus = bus()
        bus.write32(0x0300_0000, 0x11223344)
        assertEquals(0x44, bus.read8(0x0300_0000))
        assertEquals(0x33, bus.read8(0x0300_0001))
        assertEquals(0x22, bus.read8(0x0300_0002))
        assertEquals(0x11, bus.read8(0x0300_0003))
    }

    @Test
    fun `lecture 32 bits ignore les bits d'alignement`() {
        val bus = bus()
        bus.write32(0x0200_0010, 0xDEAD_BEEF.toInt())
        // Une adresse non alignée est ramenée au mot : même valeur brute.
        assertEquals(0xDEAD_BEEF.toInt(), bus.read32(0x0200_0012))
    }

    @Test
    fun `l'EWRAM se replie tous les 256 Kio`() {
        val bus = bus()
        bus.write32(0x0200_0000, 0xCAFEBABE.toInt())
        assertEquals(0xCAFEBABE.toInt(), bus.read32(0x0204_0000)) // +256 Kio
    }

    @Test
    fun `l'IWRAM se replie tous les 32 Kio`() {
        val bus = bus()
        bus.write32(0x0300_0000, 0x0BADF00D)
        assertEquals(0x0BADF00D, bus.read32(0x0300_8000)) // +32 Kio
    }

    @Test
    fun `un octet ecrit en palette est duplique sur le demi-mot`() {
        val bus = bus()
        bus.write8(0x0500_0000, 0xAB)
        assertEquals(0xABAB, bus.read16(0x0500_0000))
    }

    @Test
    fun `un octet ecrit en VRAM est duplique sur le demi-mot`() {
        val bus = bus()
        bus.write8(0x0600_0004, 0x3C)
        assertEquals(0x3C3C, bus.read16(0x0600_0004))
    }

    @Test
    fun `les ecritures 8 bits vers l'OAM sont ignorees`() {
        val bus = bus()
        bus.write16(0x0700_0000, 0x1111)
        bus.write8(0x0700_0000, 0xFF)
        assertEquals(0x1111, bus.read16(0x0700_0000)) // inchangé
    }

    @Test
    fun `la VRAM se replie sur les 32 derniers Kio du bloc de 128 Kio`() {
        val bus = bus()
        // 0x0601_0000 et 0x0601_8000 pointent la même zone physique.
        bus.write16(0x0601_0000, 0x7E7E)
        assertEquals(0x7E7E, bus.read16(0x0601_8000))
    }

    @Test
    fun `la ROM est en lecture seule`() {
        val bus = bus()
        val before = bus.read8(0x0800_0000)
        bus.write8(0x0800_0000, before xor 0xFF)
        assertEquals(before, bus.read8(0x0800_0000))
    }

    @Test
    fun `la SRAM est accessible par octet`() {
        val bus = bus()
        bus.write8(0x0E00_0001, 0x5A)
        assertEquals(0x5A, bus.read8(0x0E00_0001))
    }
}
