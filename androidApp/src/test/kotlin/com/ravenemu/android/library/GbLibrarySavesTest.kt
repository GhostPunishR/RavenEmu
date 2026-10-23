package com.ravenemu.android.library

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.ravenemu.android.saves.GbBatteryStore
import com.ravenemu.android.saves.GbSaveStateStore
import com.ravenemu.android.saves.GbSaveTransfer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GbLibrarySavesTest {
    private val filesDir: File = Files.createTempDirectory("ravenemu-library-saves").toFile()
    private val transfer = GbSaveTransfer(filesDir)
    private val battery = GbBatteryStore(File(filesDir, "gb-battery"))
    private val states = GbSaveStateStore(File(filesDir, "gb-states"))
    private val first = GbBatteryStore.keyForRom(byteArrayOf(1))
    private val second = GbBatteryStore.keyForRom(byteArrayOf(2))
    private val unsaved = GbBatteryStore.keyForRom(byteArrayOf(3))

    @After
    fun clean() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `l'archive de la bibliotheque garde chaque jeu sauvegarde et se reimporte jeu par jeu`() {
        battery.write(first, byteArrayOf(10, 11, 12))
        states.write(second, byteArrayOf(20, 21), slot = 2)
        val output = ByteArrayOutputStream()
        val written = transfer.exportLibrary(listOf(
            GbSaveTransfer.LibraryGame(first, "Pokémon Cristal: édition.gbc"),
            GbSaveTransfer.LibraryGame(unsaved, "Sans sauvegarde.gb"),
            GbSaveTransfer.LibraryGame(second, "Zelda.gba"),
        ), output)
        assertEquals(2, written)
        val archive = output.toByteArray()

        val folders = ZipInputStream(archive.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map { it.name.substringBefore('/') }.toSet()
        }
        assertEquals(setOf("Pokémon Cristal_ édition (${first.take(8)})", "Zelda (${second.take(8)})"), folders)

        val firstSave = transfer.previewImport(archive.inputStream(), first, "RavenEmu-sauvegardes.zip")
        assertArrayEquals(byteArrayOf(10, 11, 12), firstSave.bytes["battery.sav"])
        assertEquals(emptyList<Int>(), firstSave.stateSlots)
        val secondSave = transfer.previewImport(archive.inputStream(), second, "RavenEmu-sauvegardes.zip")
        assertEquals(listOf(2), secondSave.stateSlots)
        assertArrayEquals(byteArrayOf(20, 21), secondSave.bytes["slot2.state"])
        assertThrows(IllegalArgumentException::class.java) {
            transfer.previewImport(archive.inputStream(), unsaved, "RavenEmu-sauvegardes.zip")
        }
    }

    @Test
    fun `sans aucune sauvegarde l'export de la bibliotheque est refuse`() {
        assertThrows(IllegalArgumentException::class.java) {
            transfer.exportLibrary(listOf(GbSaveTransfer.LibraryGame(unsaved, "Jeu.gb")), ByteArrayOutputStream())
        }
    }
}
