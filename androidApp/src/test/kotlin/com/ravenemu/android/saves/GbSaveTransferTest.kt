package com.ravenemu.android.saves

import com.ravenemu.shared.saves.BatteryFileFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GbSaveTransferTest {
    private val romId = GbBatteryStore.keyForRom(byteArrayOf(11, 22, 33))
    private val otherRomId = GbBatteryStore.keyForRom(byteArrayOf(11, 22, 34))
    private val transfer = GbSaveTransfer(File("unused-save-import-tests"))

    @Test
    fun acceptsSingleSavButMarksRomIdentityUnverifiable() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val payload = transfer.previewImport(bytes.inputStream(), romId, "pokemon.sav")
        assertEquals(true, payload.rawBattery)
        assertEquals(romId, payload.romKey)
        assertArrayEquals(bytes, payload.bytes["battery.sav"])
        assertEquals(emptyList<Int>(), payload.stateSlots)
    }

    @Test
    fun acceptsRetroArchSrmLikeASav() {
        val bytes = byteArrayOf(9, 8, 7)
        val payload = transfer.previewImport(bytes.inputStream(), romId, "Pokemon Emeraude.SRM")
        assertEquals(true, payload.rawBattery)
        assertArrayEquals(bytes, payload.bytes["battery.sav"])
    }

    @Test
    fun exportNamesKeepTheRomNameWithTheChosenExtension() {
        assertEquals("Pokémon - Version Cristal (France).sav",
            batteryExportName("Pokémon - Version Cristal (France).gbc"))
        assertEquals("Pokémon - Version Cristal (France).srm",
            batteryExportName("Pokémon - Version Cristal (France).gbc", BatteryFileFormat.SRM))
    }

    @Test
    fun libraryArchiveImportsOnlyTheTargetGamesFolder() {
        val archive = library(linkedMapOf(
            "Autre jeu (abcdef12)" to (otherRomId to mapOf("battery.sav" to byteArrayOf(7, 7))),
            "Jeu (01234567)" to (romId to mapOf(
                "battery.sav" to byteArrayOf(1, 2, 3),
                "slot1.state" to byteArrayOf(4, 5),
            )),
        ))
        val parsed = transfer.previewImport(archive.inputStream(), romId, "RavenEmu-sauvegardes.zip")
        assertEquals(false, parsed.rawBattery)
        assertEquals(listOf(1), parsed.stateSlots)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed.bytes["battery.sav"])
        val other = transfer.previewImport(archive.inputStream(), otherRomId, "RavenEmu-sauvegardes.zip")
        assertArrayEquals(byteArrayOf(7, 7), other.bytes["battery.sav"])
    }

    @Test
    fun libraryArchiveWithoutTheGameOrWithUnsafeFoldersIsRejected() {
        val missing = library(linkedMapOf(
            "Autre jeu" to (otherRomId to mapOf("battery.sav" to byteArrayOf(7))),
        ))
        val error = assertThrows(IllegalArgumentException::class.java) {
            transfer.previewImport(missing.inputStream(), romId, "saves.zip")
        }
        assertEquals("Cette archive ne contient aucune sauvegarde de ce jeu.", error.message)
        for (folder in listOf("..", "a/b")) {
            val unsafe = library(linkedMapOf(folder to (romId to mapOf("battery.sav" to byteArrayOf(1)))))
            assertThrows(IllegalArgumentException::class.java) {
                transfer.previewImport(unsafe.inputStream(), romId, "saves.zip")
            }
        }
        val mixed = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("battery.sav")); zip.write(byteArrayOf(1)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("Jeu/battery.sav")); zip.write(byteArrayOf(1)); zip.closeEntry()
            }
        }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            transfer.previewImport(mixed.inputStream(), romId, "saves.zip")
        }
    }

    @Test
    fun refusesRawFileDisguisedAsZipOrUnrelatedExtension() {
        for (name in listOf("pokemon.zip", "pokemon.txt")) {
            assertThrows(IllegalArgumentException::class.java) {
                transfer.previewImport(byteArrayOf(1, 2).inputStream(), romId, name)
            }
        }
    }

    @Test
    fun archiveBelongingToAnotherRomIsRejected() {
        val archive = bundle(otherRomId, mapOf("battery.sav" to byteArrayOf(1, 2, 3)))
        assertThrows(IllegalArgumentException::class.java) {
            transfer.previewImport(archive.inputStream(), romId, "backup.zip")
        }
    }

    @Test
    fun correctArchiveReadsSavAndIndependentSlots() {
        val data = linkedMapOf(
            "battery.sav" to byteArrayOf(1, 2, 3),
            "slot0.state" to byteArrayOf(10, 11),
            "slot0.png" to GbStatePreviewTest.png,
            "slot3.state" to byteArrayOf(44, 55),
        )
        val parsed = transfer.previewImport(
            bundle(romId, data).inputStream(), romId, "backup.zip",
        )
        assertEquals(false, parsed.rawBattery)
        assertEquals(listOf(0, 3), parsed.stateSlots)
        assertArrayEquals(data.getValue("slot0.png"), parsed.bytes["slot0.png"])
        assertArrayEquals(data.getValue("slot3.state"), parsed.bytes["slot3.state"])
    }

    @Test
    fun historicalExportRemainsImportableWithRomIdentityChecks() {
        val archive = requireNotNull(javaClass.getResourceAsStream("/legacy-gb-saves.zip"))
            .use { it.readBytes() }
        val parsed = transfer.previewImport(archive.inputStream(), romId, "backup.zip")
        assertEquals(false, parsed.rawBattery)
        assertEquals(listOf(0, 3), parsed.stateSlots)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed.bytes["battery.sav"])
        assertArrayEquals(byteArrayOf(44, 55), parsed.bytes["slot3.state"])
        assertThrows(IllegalArgumentException::class.java) {
            transfer.previewImport(archive.inputStream(), otherRomId, "backup.zip")
        }
    }

    @Test
    fun rejectsCorruptedChecksumAndDisallowedFiles() {
        val corrupt = bundle(
            romId,
            mapOf("battery.sav" to byteArrayOf(5)),
            corruptHash = true,
        )
        assertThrows(IllegalArgumentException::class.java) {
            transfer.previewImport(corrupt.inputStream(), romId, "backup.zip")
        }
        val traversal = bundle(romId, mapOf("../battery.sav" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) {
            transfer.previewImport(traversal.inputStream(), romId, "backup.zip")
        }
    }

    @Test
    fun rejectsOrphanPreviewAndDuplicateZipEntries() {
        val previewOnly = bundle(romId, mapOf("slot2.png" to byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) {
            transfer.previewImport(previewOnly.inputStream(), romId, "backup.zip")
        }
    }

    @Test
    fun unsafeOrMalformedThumbnailsAreDroppedWithoutLosingTheirSave() {
        val state = byteArrayOf(10, 11)
        for (preview in listOf(GbStatePreviewTest.header(10000, 10000), byteArrayOf(31, 32))) {
            val parsed = transfer.previewImport(bundle(romId, mapOf(
                "slot0.state" to state, "slot0.png" to preview,
            )).inputStream(), romId, "backup.zip")
            assertArrayEquals(state, parsed.bytes["slot0.state"])
            assertEquals(false, parsed.bytes.containsKey("slot0.png"))
        }
    }

    /** A library archive as RavenEmu writes it: a folder per game, manifest first. */
    private fun library(folders: Map<String, Pair<String, Map<String, ByteArray>>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((folder, game) in folders) {
                java.util.zip.ZipInputStream(bundle(game.first, game.second).inputStream()).use { source ->
                    while (true) {
                        val entry = source.nextEntry ?: break
                        zip.putNextEntry(ZipEntry("$folder/${entry.name}"))
                        zip.write(source.readBytes())
                        zip.closeEntry()
                    }
                }
            }
        }
        return output.toByteArray()
    }

    private fun bundle(
        key: String,
        files: Map<String, ByteArray>,
        corruptHash: Boolean = false,
    ): ByteArray {
        val manifestEntries = JSONObject()
        for ((name, bytes) in files) {
            manifestEntries.put(
                name,
                if (corruptHash) "0".repeat(64) else MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
        }
        val manifest = JSONObject()
            .put("format", "RavenEmu-GB-Saves")
            .put("version", 1)
            .put("romSha256", key)
            .put("entries", manifestEntries)
            .toString().toByteArray(Charsets.UTF_8)
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("manifest.json", manifest)
            files.forEach { (name, bytes) -> put(name, bytes) }
        }
        return output.toByteArray()
    }
}
