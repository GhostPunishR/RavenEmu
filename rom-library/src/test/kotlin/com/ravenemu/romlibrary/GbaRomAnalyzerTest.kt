package com.ravenemu.romlibrary

import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.cartridge.GbaHeader
import com.ravenemu.emulation.api.ConsoleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GbaRomAnalyzerTest {

    private fun gbaRom(
        title: String = "TESTGBA",
        gameCode: String = "AGBE",
        withMarker: Boolean = true,
        sizeBytes: Int = 1024,
    ): ByteArray {
        val rom = ByteArray(sizeBytes)
        title.take(12).forEachIndexed { i, c -> rom[0xA0 + i] = c.code.toByte() }
        gameCode.take(4).forEachIndexed { i, c -> rom[0xAC + i] = c.code.toByte() }
        if (withMarker) rom[0xB2] = 0x96.toByte()
        rom[0xBD] = GbaHeader.computeHeaderChecksum(rom).toByte()
        return rom
    }

    private val analyzer = GbaRomAnalyzer()

    @Test
    fun `reconnait l'extension gba`() {
        assertTrue(analyzer.canAnalyze("jeu.gba"))
        assertTrue(analyzer.canAnalyze("JEU.GBA"))
        assertFalse(analyzer.canAnalyze("jeu.gb"))
        assertFalse(analyzer.canAnalyze("jeu.gbc"))
        assertEquals(GbaCartridge.MAX_ROM_SIZE, analyzer.maxRomSizeBytes)
    }

    @Test
    fun `analyse une ROM GBA valide`() {
        val result = analyzer.analyze("content://jeu.gba", "jeu.gba", 0L, gbaRom())
        val entry = assertIs<AnalysisResult.Success>(result).entry
        assertEquals(ConsoleType.GAME_BOY_ADVANCE, entry.console)
        assertEquals("TESTGBA", entry.title)
        assertEquals("AGBE", entry.gameCode)
        assertTrue(entry.headerChecksumValid)
        // Les champs propres à la Game Boy gardent leurs valeurs neutres.
        assertEquals(0, entry.cartridgeTypeCode)
        assertFalse(entry.supportsCgb)
    }

    @Test
    fun `rejette une ROM sans marqueur GBA`() {
        val result = analyzer.analyze("uri", "jeu.gba", 0L, gbaRom(withMarker = false))
        assertIs<AnalysisResult.Invalid>(result)
    }

    @Test
    fun `rejette une ROM trop courte`() {
        val result = analyzer.analyze("uri", "jeu.gba", 0L, ByteArray(0x40))
        assertIs<AnalysisResult.Invalid>(result)
    }

    @Test
    fun `calcule des empreintes exploitables`() {
        val result = analyzer.analyze("uri", "jeu.gba", 0L, gbaRom())
        val entry = assertIs<AnalysisResult.Success>(result).entry
        assertTrue(entry.fingerprints.sha256.isNotBlank())
    }
}
