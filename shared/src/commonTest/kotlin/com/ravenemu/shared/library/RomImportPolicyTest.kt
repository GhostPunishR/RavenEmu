package com.ravenemu.shared.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RomImportPolicyTest {
    @Test
    fun acceptsCaseInsensitiveGameBoyFamilyExtensions() {
        assertTrue(RomImportPolicy.acceptsRomFile("Tetris.gb"))
        assertTrue(RomImportPolicy.acceptsRomFile("TETRIS.GB"))
        assertTrue(RomImportPolicy.acceptsRomFile("Tetris DX.gbc"))
        assertTrue(RomImportPolicy.acceptsRomFile("ZELDA.GBC"))
        assertTrue(RomImportPolicy.acceptsRomFile("Pokemon - Emeraude (France).gba"))
    }

    @Test
    fun otherSystemsAndUnknownFilesAreNotAccidentallyLoaded() {
        assertFalse(RomImportPolicy.acceptsRomFile("game.gbc.zip"))
        assertFalse(RomImportPolicy.acceptsRomFile("game.nds"))
        assertFalse(RomImportPolicy.acceptsRomFile("game.gb.zip"))
        assertFalse(RomImportPolicy.acceptsRomFile("game"))
    }

    @Test
    fun theCartridgeHeaderChoosesTheConsoleNotTheExtension() {
        fun rom(flag: Int) = ByteArray(0x150).also { it[0x143] = flag.toByte() }
        assertEquals(GamePlatform.GB, RomImportPolicy.platformOf("a.gbc", rom(0x00)))
        assertEquals(GamePlatform.GBC, RomImportPolicy.platformOf("a.gb", rom(0x80)))
        assertEquals(GamePlatform.GBC, RomImportPolicy.platformOf("a.gbc", rom(0xC0)))
        // Only the two documented values mark a color cartridge.
        assertEquals(GamePlatform.GB, RomImportPolicy.platformOf("a.gb", rom(0x88)))
        assertEquals(GamePlatform.GB, RomImportPolicy.platformOf("a.gb", ByteArray(0x100)))
    }

    @Test
    fun gbaFilesGoToTheGbaCoreWhateverTheirBytes() {
        val rom = ByteArray(0x200).also { it[0x143] = 0xC0.toByte() }
        assertEquals(GamePlatform.GBA, RomImportPolicy.platformOf("Emeraude.GBA", rom))
    }

    @Test
    fun retroAchievementsReceivesItsOwnConsoleIds() {
        assertEquals(4, RomImportPolicy.retroAchievementsConsole(GamePlatform.GB))
        assertEquals(6, RomImportPolicy.retroAchievementsConsole(GamePlatform.GBC))
        assertEquals(5, RomImportPolicy.retroAchievementsConsole(GamePlatform.GBA))
    }

    @Test
    fun romByteLimitIsExplicit() {
        assertEquals(32 * 1024 * 1024, RomImportPolicy.maxRomBytes)
    }
}
