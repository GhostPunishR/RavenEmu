package com.ravenemu.android.saves

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Battery-backed cartridge data (the game's own SAVE command), not a save state.
 * Private app storage, keyed by ROM content rather than its display filename.
 */
internal class GbBatteryStore(private val directory: File) {
    fun read(romKey: String): ByteArray? {
        val atomic = AtomicFile(fileFor(romKey))
        return try {
            atomic.openRead().use { stream ->
                val content = stream.readBytes()
                require(content.size <= MAX_BATTERY_BYTES) {
                    "La sauvegarde dépasse la taille maximale autorisée."
                }
                content
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    fun write(romKey: String, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_BATTERY_BYTES) {
            "Taille de sauvegarde batterie invalide."
        }
        check(directory.isDirectory || directory.mkdirs()) {
            "Impossible de créer le dossier des sauvegardes."
        }
        val atomic = AtomicFile(fileFor(romKey))
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    /** Only used to roll back an interrupted import of a previously absent save. */
    fun delete(romKey: String) {
        AtomicFile(fileFor(romKey)).delete()
    }

    private fun fileFor(key: String): File {
        require(key.length == 64 && key.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Identifiant de ROM invalide."
        }
        return File(directory, "$key.sav")
    }

    companion object {
        private const val MAX_BATTERY_BYTES = 2 * 1024 * 1024

        /** SHA-256 of the ROM bytes: renamed ROMs share their save, different games do not. */
        fun keyForRom(rom: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(rom)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
