package com.ravenemu.android

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
            "slot0.png" to byteArrayOf(31, 32),
            "slot3.state" to byteArrayOf(44, 55),
        )
        val parsed = transfer.previewImport(
            bundle(romId, data).inputStream(), romId, "backup.zip",
        )
        assertEquals(false, parsed.rawBattery)
        assertEquals(listOf(0, 3), parsed.stateSlots)
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
