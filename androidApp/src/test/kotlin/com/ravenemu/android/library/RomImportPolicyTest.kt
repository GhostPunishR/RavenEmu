package com.ravenemu.android.library

import com.ravenemu.shared.RomImportPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomImportPolicyTest {
    @Test
    fun acceptsGbGbcAndGbaFilesRegardlessOfCase() {
        assertTrue(RomImportPolicy.acceptsRomFile("jeu.gb"))
        assertTrue(RomImportPolicy.acceptsRomFile("JEU.GB"))
        assertTrue(RomImportPolicy.acceptsRomFile("jeu.gbc"))
        assertTrue(RomImportPolicy.acceptsRomFile("JEU.GBC"))
        assertTrue(RomImportPolicy.acceptsRomFile("jeu.gba"))
    }

    @Test
    fun rejectsOtherFormatsAndCompressedFiles() {
        assertFalse(RomImportPolicy.acceptsRomFile("jeu.gbc.zip"))
        assertFalse(RomImportPolicy.acceptsRomFile("jeu.gba.7z"))
        assertFalse(RomImportPolicy.acceptsRomFile("jeu.gb.zip"))
        assertFalse(RomImportPolicy.acceptsRomFile("jeu.txt"))
        assertFalse(RomImportPolicy.acceptsRomFile(""))
    }

    @Test
    fun theMaximumSizeAllowsTheLargestGbaButNotUnlimitedFiles() {
        assertTrue(RomImportPolicy.maxRomBytes >= 32 * 1024 * 1024)
        assertTrue(RomImportPolicy.maxRomBytes <= 32 * 1024 * 1024)
    }
}
