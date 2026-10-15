package com.ravenemu.android

import com.ravenemu.shared.RomImportPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GbFolderImportPolicyTest {
    @Test
    fun gameBoyAndColorFilesAreCandidatesNotOtherFiles() {
        assertTrue(GbFolderImportPolicy.isRomFilename("Pokemon - Version Bleue.gb"))
        assertTrue(GbFolderImportPolicy.isRomFilename("tetris.GB"))
        assertTrue(GbFolderImportPolicy.isRomFilename("Zelda - Link's Awakening DX.gbc"))
        assertFalse(GbFolderImportPolicy.isRomFilename("save.sav"))
        assertFalse(GbFolderImportPolicy.isRomFilename("cover.png"))
        assertFalse(GbFolderImportPolicy.isRomFilename("archive.zip"))
        assertTrue(GbFolderImportPolicy.isRomFilename("Metroid Fusion (Europe).gba"))
        assertFalse(GbFolderImportPolicy.isRomFilename("game.nds"))
    }

    @Test
    fun reportedSizesMustBePositiveAndBounded() {
        val max = RomImportPolicy.maxRomBytes.toLong()
        assertTrue(GbFolderImportPolicy.isSupportedSize(null))
        assertTrue(GbFolderImportPolicy.isSupportedSize(1))
        assertTrue(GbFolderImportPolicy.isSupportedSize(max))
        assertFalse(GbFolderImportPolicy.isSupportedSize(0))
        assertFalse(GbFolderImportPolicy.isSupportedSize(-1))
        assertFalse(GbFolderImportPolicy.isSupportedSize(max + 1))
    }

    @Test
    fun traversalCannotExceedFolderAndDepthCaps() {
        assertTrue(GbFolderImportPolicy.mayDescend(0, 1))
        assertTrue(GbFolderImportPolicy.mayDescend(
            GbFolderImporter.MAX_DEPTH - 1, GbFolderImporter.MAX_DIRECTORIES - 1,
        ))
        assertFalse(GbFolderImportPolicy.mayDescend(GbFolderImporter.MAX_DEPTH, 1))
        assertFalse(GbFolderImportPolicy.mayDescend(
            0, GbFolderImporter.MAX_DIRECTORIES,
        ))
    }
}
