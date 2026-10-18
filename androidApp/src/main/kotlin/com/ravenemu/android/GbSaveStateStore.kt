package com.ravenemu.android

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayOutputStream

/** Four GB save-state slots per ROM (numbered 1 to 4 in the UI).
 *
 * Slot 1 deliberately retains the original `<hash>.slot0.state` filename:
 * no migration is needed, and previously validated saves remain readable.
 * Previews are stored separately and are never required to restore a state.
 */
internal class GbSaveStateStore(private val directory: File) {
    fun read(romKey: String, slot: Int = 0): ByteArray? =
        readAtomic(fileFor(romKey, slot), MAX_STATE_BYTES)

    fun write(romKey: String, bytes: ByteArray, slot: Int = 0) {
        writeAtomic(fileFor(romKey, slot), bytes, MAX_STATE_BYTES)
    }

    fun readPreview(romKey: String, slot: Int): ByteArray? =
        readAtomic(previewFileFor(romKey, slot), MAX_PREVIEW_BYTES)
            ?.takeIf { GbStatePreview.size(it) != null }

    fun writePreview(romKey: String, slot: Int, bytes: ByteArray) {
        require(GbStatePreview.size(bytes) != null) { "Miniature PNG invalide ou trop grande." }
        writeAtomic(previewFileFor(romKey, slot), bytes, MAX_PREVIEW_BYTES)
    }

    fun deletePreview(romKey: String, slot: Int) {
        AtomicFile(previewFileFor(romKey, slot)).delete()
    }

    fun delete(romKey: String, slot: Int) {
        // The preview is optional and cannot keep a deleted save state alive.
        AtomicFile(fileFor(romKey, slot)).delete()
        deletePreview(romKey, slot)
    }

    fun slots(romKey: String): List<GbSlotSummary> = (0 until SLOT_COUNT).map { slot ->
        val state = read(romKey, slot)
        val primary = fileFor(romKey, slot)
        val backup = File(primary.path + ".bak")
        GbSlotSummary(
            slot = slot,
            occupied = state != null,
            savedAtMillis = if (state == null) null else
                (if (primary.exists()) primary else backup).lastModified()
                    .takeIf { it > 0L },
            preview = if (state == null) null else try {
                readPreview(romKey, slot)
            } catch (_: Exception) {
                null // A corrupt optional preview must not hide a valid state.
            },
        )
    }

    internal fun fileFor(romKey: String, slot: Int = 0): File {
        validate(romKey, slot)
        return File(directory, "$romKey.slot$slot.state")
    }

    internal fun previewFileFor(romKey: String, slot: Int): File {
        validate(romKey, slot)
        return File(directory, "$romKey.slot$slot.png")
    }

    private fun validate(romKey: String, slot: Int) {
        require(slot in 0 until SLOT_COUNT) { "Emplacement de sauvegarde invalide." }
        require(romKey.length == 64 &&
            romKey.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Identifiant de ROM invalide."
        }
    }

    private fun readAtomic(file: File, maxBytes: Int): ByteArray? = try {
        AtomicFile(file).openRead().use { input ->
            // Compatible with Android 8: never read more than the limit.
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            var count = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                require(read <= maxBytes - count) {
                    "Fichier de sauvegarde trop volumineux."
                }
                output.write(buffer, 0, read)
                count += read
            }
            require(count > 0) { "Fichier de sauvegarde vide." }
            output.toByteArray()
        }
    } catch (_: FileNotFoundException) {
        null
    }

    private fun writeAtomic(file: File, bytes: ByteArray, maxBytes: Int) {
        require(bytes.size in 1..maxBytes) {
            "Fichier de sauvegarde invalide ou trop volumineux."
        }
        check(directory.isDirectory || directory.mkdirs()) {
            "Impossible de créer le dossier des save states."
        }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    companion object {
        const val SLOT_COUNT = 4
        private const val MAX_STATE_BYTES = 4 * 1024 * 1024
        private const val MAX_PREVIEW_BYTES = 512 * 1024
    }
}

internal data class GbSlotSummary(
    val slot: Int,
    val occupied: Boolean,
    val savedAtMillis: Long?,
    val preview: ByteArray?,
)
