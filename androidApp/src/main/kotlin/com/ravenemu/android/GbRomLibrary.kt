package com.ravenemu.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import com.ravenemu.shared.GamePlatform
import com.ravenemu.shared.RomImportPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONArray
import org.json.JSONObject

data class StoredGbRom(
    val id: String,
    val filename: String,
    val platform: GamePlatform = GamePlatform.GB,
)

/**
 * Persistent GB/GBC/GBA library, independent of the document picker's permission.
 * An app-private ROM copy is addressed by the same SHA-256 as its SRAM/state.
 * All methods must run on a background dispatcher; index and ROM writes are
 * atomic, and the host serializes imports/loads.
 */
internal class GbRomLibrary(
    private val directory: File,
    private val resolver: ContentResolver,
) {
    private val index = AtomicFile(File(directory, "index.json"))
    private val romFolder = File(directory, "roms")
    // list() may rewrite the index while classifying older games: never let
    // that race an import, or the imported entry would be dropped.
    private val indexLock = Any()

    fun list(): List<StoredGbRom> = synchronized(indexLock) {
        val array = try {
            val bytes = index.openRead().use { it.readBytes() }
            require(bytes.size <= MAX_INDEX_BYTES) { "L'index des jeux est trop volumineux." }
            JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("games")
        } catch (_: FileNotFoundException) {
            return@synchronized emptyList()
        }
        require(array.length() <= MAX_GAMES) { "La bibliothèque dépasse sa limite." }
        val entries = ArrayList<StoredGbRom>(array.length())
        val ids = HashSet<String>()
        var classified = false
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val id = validateKey(item.getString("id"))
            val name = item.getString("name")
            require(name.isNotBlank() && name.length <= 256) { "Nom de ROM invalide." }
            if (!ids.add(id)) continue
            val platform = when (item.optString("platform")) {
                "gb" -> GamePlatform.GB
                "gbc" -> GamePlatform.GBC
                "gba" -> GamePlatform.GBA
                // Indexes written before GBC support have no console field:
                // read it once from the private copy's header.
                else -> platformFromStoredHeader(id, name).also { classified = true }
            }
            entries.add(StoredGbRom(id, name, platform))
        }
        if (classified) {
            try {
                writeIndex(entries)
            } catch (error: Exception) {
                // Still usable: the next listing classifies them again.
                Log.w(TAG, "Unable to record the console of older games", error)
            }
        }
        entries
    }

    private fun platformFromStoredHeader(id: String, name: String): GamePlatform = try {
        val header = ByteArray(HEADER_BYTES)
        val read = AtomicFile(fileFor(id)).openRead().use { input ->
            var total = 0
            while (total < header.size) {
                val next = input.read(header, total, header.size - total)
                if (next < 0) break
                total += next
            }
            total
        }
        RomImportPolicy.platformOf(name, header.copyOf(read))
    } catch (error: Exception) {
        // A missing copy is reported when the game is launched; until then
        // the game stays under Game Boy, where every older import was.
        Log.w(TAG, "Unable to read the header of ${id.take(12)}", error)
        GamePlatform.GB
    }

    /** The selected file is read once and can be passed directly to the core. */
    fun readSelected(uri: Uri): Pair<String, ByteArray> {
        val name = resolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } ?: throw IllegalArgumentException("Impossible de lire le nom du fichier.")
        require(RomImportPolicy.acceptsRomFile(name)) {
            "Sélectionnez une ROM Game Boy (.gb), Game Boy Color (.gbc) ou Game Boy Advance (.gba)."
        }
        val stream = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Impossible d'ouvrir la ROM.")
        return name.take(256) to stream.use { readBounded(it) }
    }

    /** Add a ROM only after native validation succeeded. Duplicate hashes stay unique. */
    fun add(filename: String, bytes: ByteArray): StoredGbRom = synchronized(indexLock) {
        require(RomImportPolicy.acceptsRomFile(filename))
        require(bytes.size in 1..RomImportPolicy.maxRomBytes)
        val id = GbBatteryStore.keyForRom(bytes)
        val existing = list()
        val already = existing.firstOrNull { it.id == id }
        if (already != null) {
            // Re-importing a known cartridge can repair a missing or corrupt
            // private ROM copy without touching its .sav or save state.
            if (runCatching { readRom(id).contentEquals(bytes) }.getOrDefault(false).not()) {
                writeRom(id, bytes)
            }
            return already
        }
        require(existing.size < MAX_GAMES) { "Bibliothèque pleine ($MAX_GAMES jeux)." }
        writeRom(id, bytes)
        val added = StoredGbRom(id, filename.take(256), RomImportPolicy.platformOf(filename, bytes))
        writeIndex(existing + added)
        return added
    }

    private fun writeRom(id: String, bytes: ByteArray) {
        check(romFolder.isDirectory || romFolder.mkdirs()) {
            "Impossible de créer le dossier des ROM."
        }
        val file = AtomicFile(fileFor(id))
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun readRom(id: String): ByteArray {
        val entry = list().firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Jeu absent de la bibliothèque.")
        // AtomicFile.openRead recovers an interrupted write from its backup.
        val rom = AtomicFile(fileFor(entry.id)).openRead().use { readBounded(it) }
        require(GbBatteryStore.keyForRom(rom) == entry.id) {
            "La ROM ne correspond plus à son empreinte."
        }
        return rom
    }

    private fun writeIndex(entries: List<StoredGbRom>) {
        check(directory.isDirectory || directory.mkdirs())
        val games = JSONArray()
        for (entry in entries) {
            games.put(JSONObject().put("id", entry.id).put("name", entry.filename)
                .put("platform", entry.platform.name.lowercase()))
        }
        val data = JSONObject().put("version", 2).put("games", games)
            .toString().toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_INDEX_BYTES)
        val output = index.startWrite()
        try {
            output.write(data)
            index.finishWrite(output)
        } catch (error: Exception) {
            index.failWrite(output)
            throw error
        }
    }

    private fun fileFor(id: String): File = File(romFolder, "${validateKey(id)}.gb")

    private fun validateKey(id: String): String {
        require(id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Identifiant de ROM invalide."
        }
        return id
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val result = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var count = 0
        while (true) {
            val next = input.read(chunk)
            if (next < 0) break
            if (next == 0) continue
            require(next <= RomImportPolicy.maxRomBytes - count) {
                "La ROM dépasse la limite de 32 Mio."
            }
            result.write(chunk, 0, next)
            count += next
        }
        require(count > 0) { "La ROM est vide." }
        return result.toByteArray()
    }

    private companion object {
        const val TAG = "RavenEmuLibrary"
        const val HEADER_BYTES = 0x150
        const val MAX_GAMES = 256
        const val MAX_INDEX_BYTES = 256 * 1024
    }
}
