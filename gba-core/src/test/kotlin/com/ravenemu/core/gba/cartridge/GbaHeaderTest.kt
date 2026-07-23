package com.ravenemu.core.gba.cartridge

import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.emulation.api.RomLoadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GbaHeaderTest {

    @Test
    fun `analyse une ROM synthetique valide`() {
        val header = GbaHeader.parse(SyntheticRom.build(title = "RAVENTEST"))
        assertEquals("RAVENTEST", header.title)
        assertEquals("TEST", header.gameCode)
        assertEquals("RV", header.makerCode)
        assertTrue(header.fixedMarkerValid)
        assertTrue(header.headerChecksumValid)
    }

    @Test
    fun `le point d'entree est le premier mot de la ROM`() {
        val header = GbaHeader.parse(SyntheticRom.build())
        assertEquals(SyntheticRom.ARM_INFINITE_LOOP, header.entryPoint)
    }

    @Test
    fun `une ROM trop courte est rejetee`() {
        assertFailsWith<RomLoadException> { GbaHeader.parse(ByteArray(0x80)) }
    }

    @Test
    fun `un marqueur absent est signale sans exception`() {
        val rom = SyntheticRom.build()
        rom[0xB2] = 0x00 // efface le marqueur 0x96
        val header = GbaHeader.parse(rom)
        assertFalse(header.fixedMarkerValid)
    }

    @Test
    fun `une somme de controle incorrecte est signalee`() {
        val rom = SyntheticRom.build()
        rom[0xBD] = (rom[0xBD] + 1).toByte() // corrompt la somme
        val header = GbaHeader.parse(rom)
        assertFalse(header.headerChecksumValid)
    }

    @Test
    fun `la somme de controle suit la formule GBA`() {
        val rom = SyntheticRom.build()
        var expected = 0
        for (offset in 0xA0..0xBC) expected -= rom[offset].toInt() and 0xFF
        expected = (expected - 0x19) and 0xFF
        assertEquals(expected, GbaHeader.computeHeaderChecksum(rom))
    }
}
