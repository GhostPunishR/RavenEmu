package com.ravenemu.android.saves

import org.json.JSONObject
import java.io.BufferedInputStream
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
 * the archive do NOT delete existing saves. A library archive holds one such
 * package per game, each in its own folder.
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
            ?: error("Ce jeu n'a pas encore de sauvegarde.")
        output.write(bytes)
        output.flush()
    }

    /** A library game as the archive names it: its ROM key and file name. */
    data class LibraryGame(val key: String, val title: String)

    /**
     * Saves of every game of [games] that has one, in a single archive: one
     * folder per game, laid out like a single game's package with its
     * manifest first, so importing a game finds its own folder. Each game
     * is read and written in turn. Returns the number of games written.
     */
    fun exportLibrary(games: List<LibraryGame>, output: OutputStream): Int {
        require(games.any { hasAnySave(it.key) }) { "Aucune sauvegarde à exporter dans la bibliothèque." }
        val folders = HashSet<String>()
        var written = 0
        ZipOutputStream(output).use { zip ->
            for (game in games) {
                val data = gameFiles(validKey(game.key))
                if (data.isEmpty()) continue
                var folder = libraryFolder(game)
                var suffix = 2
                while (!folders.add(folder)) folder = "${libraryFolder(game)} ${suffix++}"
                writePackage(zip, game.key, data, "$folder/")
                written++
            }
            zip.finish()
        }
        return written
    }

    private fun gameFiles(key: String): Map<String, ByteArray> {
        val data = linkedMapOf<String, ByteArray>()
        battery.read(key)?.let { data["battery.sav"] = it }
        for (slot in 0 until GbSaveStateStore.SLOT_COUNT) {
            val state = states.read(key, slot) ?: continue
            data["slot$slot.state"] = state
            states.readPreview(key, slot)?.let { data["slot$slot.png"] = it }
        }
        return data
    }

    private fun writePackage(zip: ZipOutputStream, key: String, data: Map<String, ByteArray>, prefix: String) {
        val checksums = JSONObject()
        data.forEach { (name, content) -> checksums.put(name, sha256(content)) }
        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("romSha256", key)
            .put("entries", checksums)
            .toString().toByteArray(Charsets.UTF_8)
        fun write(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(prefix + name))
            zip.write(bytes)
            zip.closeEntry()
        }
        write("manifest.json", manifest)
        data.forEach { (name, bytes) -> write(name, bytes) }
    }

    /** This must run on Dispatchers.IO. Source is capped even for ZIP bombs. */
    fun previewImport(stream: InputStream, targetKey: String, filename: String): ImportPayload {
        validKey(targetKey)
        val archive = filename.endsWith(".zip", ignoreCase = true)
        val raw = RAW_EXTENSIONS.any { filename.endsWith(it, ignoreCase = true) }
        require(archive || raw) {
            "Sélectionnez une sauvegarde .sav ou .srm, ou une archive .zip RavenEmu."
        }
        val input = BufferedInputStream(stream)
        input.mark(4)
        val head = ByteArray(4)
        var read = 0
        while (read < head.size) {
            val n = input.read(head, read, head.size - read)
            if (n < 0) break
            read += n
        }
        input.reset()
        require(read > 0) { "Le fichier est vide." }
        val magic = read == 4 && head[0] == 'P'.code.toByte() &&
            head[1] == 'K'.code.toByte() && head[2] == 3.toByte() &&
            head[3] == 4.toByte()
        require(magic == archive) {
            "Le contenu du fichier ne correspond pas à son extension."
        }
        if (!magic) {
            // A raw .sav or .srm (RetroArch) is the cartridge memory alone,
            // with no ROM ID: caller explicitly confirms the target game.
            val source = readLimited(input, MAX_ARCHIVE_BYTES)
            require(source.size in 1..MAX_BATTERY_BYTES) {
                "Ce fichier n'est ni une archive RavenEmu ZIP ni une sauvegarde .sav ou .srm valide."
            }
            return ImportPayload(targetKey, mapOf("battery.sav" to source), true)
        }

        val entries = readArchive(input, targetKey)
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
        // A thumbnail is optional. Keep valid saves from older archives,
        // but never install an image that could allocate an unbounded bitmap.
        val safeEntries = entries.filter { (name, bytes) ->
            !name.endsWith(".png") || GbStatePreview.size(bytes) != null
        }
        return ImportPayload(targetKey, safeEntries, false)
    }

    /**
     * Entries of the target game's package, manifest included. A library
     * archive holds a folder per game, each starting with its manifest: only
     * the target's folder is kept, the others are read through, within a
     * bounded total. A single game's package has its files at the root.
     */
    private fun readArchive(input: InputStream, targetKey: String): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var library: Boolean? = null
        var selected: String? = null
        var kept = 0
        var scanned = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Dossier inattendu dans l'archive." }
                val folder = entry.name.substringBeforeLast('/', "")
                val name = entry.name.substringAfterLast('/')
                require(name in allowedNames()) { "Fichier inattendu ou doublon dans l'archive." }
                val inFolder = folder.isNotEmpty()
                require(library == null || library == inFolder) { "Archive de sauvegardes incohérente." }
                library = inFolder
                require(!inFolder || isLibraryFolder(folder)) { "Dossier inattendu dans l'archive." }
                val bytes = readLimited(zip, maxFor(name))
                if (inFolder && folder != selected) {
                    // Another game, or the target's folder before its manifest.
                    scanned += bytes.size
                    require(scanned <= MAX_LIBRARY_BYTES) { "Archive trop volumineuse une fois décompressée." }
                    if (selected != null || name != "manifest.json" || manifestKey(bytes) != targetKey) {
                        zip.closeEntry()
                        continue
                    }
                    selected = folder
                }
                require(!entries.containsKey(name)) { "Fichier inattendu ou doublon dans l'archive." }
                require(bytes.isNotEmpty()) { "Fichier vide dans l'archive : $name." }
                kept += bytes.size
                require(kept <= MAX_UNPACKED_BYTES) {
                    "Archive trop volumineuse une fois décompressée."
                }
                entries[name] = bytes
                zip.closeEntry()
            }
        }
        require(library != true || selected != null) {
            "Cette archive ne contient aucune sauvegarde de ce jeu."
        }
        return entries
    }

    fun conflicts(payload: ImportPayload): List<String> {
        val key = validKey(payload.romKey)
        val clashes = mutableListOf<String>()
        if (payload.hasBattery && battery.read(key) != null) clashes += "sauvegarde du jeu"
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
            require(!name.endsWith(".png") || GbStatePreview.size(bytes) != null) {
                "Miniature PNG invalide ou trop grande."
            }
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
        // Every game's saves read through to find one game's folder.
        private const val MAX_LIBRARY_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_FOLDER_CHARS = 120
        /** Raw cartridge memory: .sav for most emulators, .srm for RetroArch. */
        private val RAW_EXTENSIONS = listOf(".sav", ".srm")

        private fun manifestKey(bytes: ByteArray): String? = runCatching {
            JSONObject(bytes.toString(Charsets.UTF_8)).getString("romSha256")
        }.getOrNull()

        private fun isLibraryFolder(folder: String): Boolean =
            folder.length <= MAX_FOLDER_CHARS && folder != "." && folder != ".." &&
                folder.none { it == '/' || it == '\\' || it.isISOControl() }

        /** Readable and unique: the ROM name, then the start of its key. */
        private fun libraryFolder(game: LibraryGame): String {
            val stem = game.title.substringAfterLast('/').substringAfterLast('\\')
                .substringBeforeLast('.')
                .map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }
                .joinToString("").trim().trimEnd('.').take(80)
                .ifBlank { "Jeu" }
            return "$stem (${game.key.take(8)})"
        }

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
