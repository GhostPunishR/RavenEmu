package com.ravenemu.android

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Transfer RavenEmu saves without ever including a copyrighted cartridge ROM.
 *
 * Packages are bound to the exact SHA-256 of the selected cartridge, and
 * validated in memory before any existing save is touched. Missing files in
 * the archive do NOT delete existing saves.
 */
internal class GbSaveTransfer(filesDir: File) {
    private val battery = GbBatteryStore(File(filesDir, "gb-battery"))
    private val states = GbSaveStateStore(File(filesDir, "gb-states"))

    data class ImportPayload(
        val romKey: String,
        val bytes: Map<String, ByteArray>,
        val rawBattery: Boolean,
    ) {
        val hasBattery: Boolean get() = "battery.sav" in bytes
        val stateSlots: List<Int> get() = (0..3).filter { "slot$it.state" in bytes }
    }

    fun hasBattery(key: String): Boolean = battery.read(validKey(key)) != null

    fun hasAnySave(key: String): Boolean {
        validKey(key)
        return battery.read(key) != null ||
            (0 until GbSaveStateStore.SLOT_COUNT).any { states.read(key, it) != null }
    }

    fun exportBattery(key: String, output: OutputStream) {
        val bytes = battery.read(validKey(key))
            ?: error("Ce jeu n'a pas encore de sauvegarde .sav.")
        output.write(bytes)
        output.flush()
    }

    fun exportBundle(key: String, output: OutputStream) {
        validKey(key)
        val data = linkedMapOf<String, ByteArray>()
        battery.read(key)?.let { data["battery.sav"] = it }
        for (slot in 0 until GbSaveStateStore.SLOT_COUNT) {
            val state = states.read(key, slot) ?: continue
            data["slot$slot.state"] = state
            states.readPreview(key, slot)?.let { data["slot$slot.png"] = it }
        }
        require(data.isNotEmpty()) { "Aucune sauvegarde à exporter pour ce jeu." }
        val checksums = JSONObject()
        data.forEach { (name, content) ->
            checksums.put(name, sha256(content))
        }
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("romSha256", key)
            .put("entries", checksums)
            .toString().toByteArray(Charsets.UTF_8)
        ZipOutputStream(output).use { zip ->
            fun write(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            write("manifest.json", manifest)
            data.forEach { (name, bytes) -> write(name, bytes) }
            zip.finish()
        }
    }

    /** This must run on Dispatchers.IO. Source is capped even for ZIP bombs. */
    fun previewImport(stream: InputStream, targetKey: String, filename: String): ImportPayload {
        validKey(targetKey)
        val archive = filename.endsWith(".zip", ignoreCase = true)
        val raw = filename.endsWith(".sav", ignoreCase = true)
        require(archive || raw) {
            "Sélectionnez une sauvegarde .sav ou une archive .zip RavenEmu."
        }
        val source = readLimited(stream, MAX_ARCHIVE_BYTES)
        require(source.isNotEmpty()) { "Le fichier est vide." }
        val magic = source.size >= 4 && source[0] == 'P'.code.toByte() &&
            source[1] == 'K'.code.toByte() && source[2] == 3.toByte() &&
            source[3] == 4.toByte()
        require(magic == archive) {
            "Le contenu du fichier ne correspond pas à son extension."
        }
        if (!magic) {
            // A raw .sav has no embedded ROM ID: caller explicitly confirms
            // the selected target game before writing it.
            require(source.size in 1..MAX_BATTERY_BYTES) {
                "Ce fichier n'est ni une archive RavenEmu ZIP ni un .sav valide."
            }
            return ImportPayload(targetKey, mapOf("battery.sav" to source), true)
        }

        val entries = linkedMapOf<String, ByteArray>()
        var total = 0
        ZipInputStream(source.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Dossier inattendu dans l'archive." }
                val name = entry.name
                require(name in allowedNames() && !entries.containsKey(name)) {
                    "Fichier inattendu ou doublon dans l'archive."
                }
                val maxSize = maxFor(name)
                val bytes = readLimited(zip, maxSize)
                require(bytes.isNotEmpty()) { "Fichier vide dans l'archive : $name." }
                total += bytes.size
                require(total <= MAX_UNPACKED_BYTES) {
                    "Archive trop volumineuse une fois décompressée."
                }
                entries[name] = bytes
                zip.closeEntry()
            }
        }
        val manifestBytes = entries.remove("manifest.json")
            ?: error("Cette archive n'est pas une sauvegarde de cette version de RavenEmu.")
        val manifest = JSONObject(manifestBytes.toString(Charsets.UTF_8))
        require(isSupportedFormat(manifest.getString("format")) && manifest.getInt("version") == VERSION) {
            "Format de sauvegarde non pris en charge."
        }
        require(manifest.getString("romSha256") == targetKey) {
            "Cette sauvegarde appartient à une autre ROM : import refusé."
        }
        val declared = manifest.getJSONObject("entries")
        require(entries.isNotEmpty() && declared.length() == entries.size) {
            "Contenu de sauvegarde incomplet."
        }
        for ((name, bytes) in entries) {
            require(declared.has(name) && declared.getString(name) == sha256(bytes)) {
                "Contrôle d'intégrité échoué pour $name."
            }
            if (name.endsWith(".png")) {
                require("slot${name.removePrefix("slot").substringBefore('.')}.state" in entries) {
                    "Une miniature ne peut pas exister sans save state."
                }
            }
        }
        return ImportPayload(targetKey, entries, false)
    }

    fun conflicts(payload: ImportPayload): List<String> {
        val key = validKey(payload.romKey)
        val clashes = mutableListOf<String>()
        if (payload.hasBattery && battery.read(key) != null) clashes += "sauvegarde .sav"
        for (slot in payload.stateSlots) {
            if (states.read(key, slot) != null) clashes += "emplacement ${slot + 1}"
        }
        return clashes
    }

    /**
     * Apply only supplied files. Original data is snapshotted first and rolled
     * back on a write failure; state and SRAM are never removed otherwise.
     * Caller must first stop an active matching core (to prevent stale SRAM
     * autosaves from overwriting imported data).
     */
    fun apply(payload: ImportPayload) {
        val key = validKey(payload.romKey)
        require(payload.bytes.isNotEmpty())
        require(payload.bytes.keys.all { it in allowedNames() && it != "manifest.json" })
        payload.bytes.forEach { (name, bytes) ->
            require(bytes.size in 1..maxFor(name)) { "Sauvegarde invalide : $name." }
        }
        // Apply states before their previews. If a new state has no preview,
        // remove the old, now-misleading thumbnail for that slot.
        val touched = mutableListOf<String>()
        if ("battery.sav" in payload.bytes) touched += "battery.sav"
        for (slot in 0 until GbSaveStateStore.SLOT_COUNT) {
            val state = "slot$slot.state"
            val preview = "slot$slot.png"
            require(preview !in payload.bytes || state in payload.bytes) {
                "Miniature sans save state."
            }
            if (state in payload.bytes) {
                touched += state
                touched += preview
            }
        }
        val originals = touched.associateWith { name -> getFile(key, name) }
        try {
            for (name in touched) {
                val bytes = payload.bytes[name]
                if (bytes == null) removeFile(key, name)
                else writeFile(key, name, bytes)
            }
        } catch (error: Exception) {
            try {
                // Restoring state first and preview second also handles the
                // store's delete(state) operation deleting its preview.
                for (name in touched) {
                    val before = originals[name]
                    if (before == null) removeFile(key, name)
                    else writeFile(key, name, before)
                }
            } catch (rollbackError: Exception) {
                error.addSuppressed(rollbackError)
            }
            throw error
        }
    }

    private fun getFile(key: String, name: String): ByteArray? = when {
        name == "battery.sav" -> battery.read(key)
        name.endsWith(".state") -> states.read(key, slotOf(name))
        name.endsWith(".png") -> states.readPreview(key, slotOf(name))
        else -> error("Nom de sauvegarde invalide.")
    }

    private fun writeFile(key: String, name: String, bytes: ByteArray) {
        when {
            name == "battery.sav" -> battery.write(key, bytes)
            name.endsWith(".state") -> states.write(key, bytes, slotOf(name))
            name.endsWith(".png") -> states.writePreview(key, slotOf(name), bytes)
            else -> error("Nom de sauvegarde invalide.")
        }
    }

    private fun removeFile(key: String, name: String) {
        when {
            name == "battery.sav" -> battery.delete(key)
            name.endsWith(".state") -> states.delete(key, slotOf(name))
            name.endsWith(".png") -> states.deletePreview(key, slotOf(name))
            else -> error("Nom de sauvegarde invalide.")
        }
    }

    private fun slotOf(name: String) = name.substringAfter("slot").substringBefore('.').toInt()

    companion object {
        private const val FORMAT = "RavenEmu-GB-Saves"
        // Old exported archives remain importable after the naming cleanup.
        // Accept only the exact historical generation, with all other manifest,
        // ROM identity, size and checksum checks still applied below.
        private fun isSupportedFormat(format: String): Boolean = format == FORMAT ||
            Regex("RavenEmu-V([0-9]+)-GB-Saves").matchEntire(format)
                ?.groupValues?.get(1) == "3"
        private const val VERSION = 1
        private const val MAX_BATTERY_BYTES = 2 * 1024 * 1024
        private const val MAX_STATE_BYTES = 4 * 1024 * 1024
        private const val MAX_PREVIEW_BYTES = 512 * 1024
        private const val MAX_MANIFEST_BYTES = 16 * 1024
        private const val MAX_ARCHIVE_BYTES = 24 * 1024 * 1024
        private const val MAX_UNPACKED_BYTES = 21 * 1024 * 1024

        private fun validKey(key: String): String {
            require(key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Identifiant de ROM invalide."
            }
            return key
        }

        private fun allowedNames(): Set<String> =
            setOf("manifest.json", "battery.sav") +
                (0..3).flatMap { listOf("slot$it.state", "slot$it.png") }

        private fun maxFor(name: String): Int = when {
            name == "manifest.json" -> MAX_MANIFEST_BYTES
            name == "battery.sav" -> MAX_BATTERY_BYTES
            name.matches(Regex("slot[0-3]\\.state")) -> MAX_STATE_BYTES
            name.matches(Regex("slot[0-3]\\.png")) -> MAX_PREVIEW_BYTES
            else -> throw IllegalArgumentException("Fichier interdit dans l'archive.")
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun readLimited(input: InputStream, limit: Int): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var used = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                require(n <= limit - used) { "Le fichier de sauvegarde est trop volumineux." }
                out.write(buffer, 0, n)
                used += n
            }
            return out.toByteArray()
        }
    }
}
