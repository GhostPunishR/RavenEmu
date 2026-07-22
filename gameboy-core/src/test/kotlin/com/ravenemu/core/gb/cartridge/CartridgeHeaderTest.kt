package com.ravenemu.core.gb.cartridge

import com.ravenemu.core.gb.TestRoms
import com.ravenemu.emulation.api.RomLoadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CartridgeHeaderTest {

    @Test
    fun `parse titre, region et drapeaux`() {
        val rom = TestRoms.build(title = "RAVEN", region = 0x00)
        val header = CartridgeHeader.parse(rom)
        assertEquals("RAVEN", header.title)
        assertEquals(RomRegion.JAPAN, header.region)
        assertFalse(header.supportsCgb)
        assertFalse(header.requiresCgb)
    }

    @Test
    fun `drapeau GBC`() {
        val header = CartridgeHeader.parse(TestRoms.build(cgbFlag = 0xC0))
        assertTrue(header.supportsCgb)
        assertTrue(header.requiresCgb)
        val compatible = CartridgeHeader.parse(TestRoms.build(cgbFlag = 0x80))
        assertTrue(compatible.supportsCgb)
        assertFalse(compatible.requiresCgb)
    }

    @Test
    fun `mapping des types de cartouche`() {
        val cases = mapOf(
            0x00 to MbcType.NONE,
            0x01 to MbcType.MBC1,
            0x03 to MbcType.MBC1,
            0x05 to MbcType.MBC2,
            0x06 to MbcType.MBC2,
            0x0F to MbcType.MBC3,
            0x13 to MbcType.MBC3,
            0x19 to MbcType.MBC5,
            0x1E to MbcType.MBC5,
            0x20 to MbcType.UNSUPPORTED,
        )
        for ((code, expected) in cases) {
            val header = CartridgeHeader.parse(TestRoms.build(type = code))
            assertEquals(expected, header.mbcType, "type 0x%02X".format(code))
        }
    }

    @Test
    fun `pile et RTC selon le type`() {
        val mbc3rtc = CartridgeHeader.parse(TestRoms.build(type = 0x10, ramSizeCode = 0x03))
        assertTrue(mbc3rtc.hasBattery)
        assertTrue(mbc3rtc.hasRtc)
        assertTrue(mbc3rtc.hasRam)

        val mbc1 = CartridgeHeader.parse(TestRoms.build(type = 0x01))
        assertFalse(mbc1.hasBattery)
        assertFalse(mbc1.hasRtc)
        assertFalse(mbc1.hasRam)
    }

    @Test
    fun `tailles ROM et RAM declarees`() {
        val header = CartridgeHeader.parse(
            TestRoms.build(type = 0x1B, romSizeCode = 0x02, ramSizeCode = 0x03)
        )
        assertEquals(128 * 1024, header.romSizeBytes)
        assertEquals(32 * 1024, header.ramSizeBytes)
    }

    @Test
    fun `MBC2 declare 512 octets de RAM interne`() {
        val header = CartridgeHeader.parse(TestRoms.build(type = 0x06))
        assertEquals(512, header.ramSizeBytes)
        assertTrue(header.hasRam)
    }

    @Test
    fun `checksums valides sur ROM generee`() {
        val header = CartridgeHeader.parse(TestRoms.build())
        assertTrue(header.headerChecksumValid)
        assertTrue(header.globalChecksumValid)
    }

    @Test
    fun `checksum d'en-tete invalide detecte`() {
        val rom = TestRoms.build()
        rom[0x0134] = 'X'.code.toByte() // casse le checksum après calcul
        val header = CartridgeHeader.parse(rom)
        assertFalse(header.headerChecksumValid)
    }

    @Test
    fun `ROM trop petite rejetee`() {
        assertFailsWith<RomLoadException> { CartridgeHeader.parse(ByteArray(0x4000)) }
    }

    @Test
    fun `ROM trop grande rejetee`() {
        assertFailsWith<RomLoadException> {
            CartridgeHeader.parse(ByteArray(CartridgeHeader.MAX_ROM_SIZE + 1))
        }
    }
}
