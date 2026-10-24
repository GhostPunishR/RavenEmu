package com.ravenemu.android.saves

import android.content.ContentResolver
import android.net.Uri
import android.util.AtomicFile
import com.ravenemu.android.engine.NativeCore
import java.io.File
import java.io.FileNotFoundException

/**
 * The player's own GBA BIOS, dumped from their console: no BIOS ships with
 * RavenEmu. Kept in private storage once chosen, and in memory once read.
 */
internal class GbaBiosStore(directory: File) {
    private val file = AtomicFile(File(directory, "gba_bios.bin"))
    @Volatile private var cached: ByteArray? = null
    @Volatile private var known = false

    /** The BIOS, or null when none is installed (or the file is not one). */
    fun bytes(): ByteArray? {
        if (known) return cached
        cached = try {
            file.openRead().use { it.readBytes() }.takeIf { it.size == NativeCore.GBA_BIOS_SIZE }
        } catch (_: FileNotFoundException) {
            null
        }
        known = true
        return cached
    }

    /** Copies the chosen file; it must be a GBA BIOS, 16 KiB exactly. */
    fun install(resolver: ContentResolver, uri: Uri) {
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(NativeCore.GBA_BIOS_SIZE + 1)
            var total = 0
            while (total < buffer.size) {
                val read = input.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            buffer.copyOf(total)
        } ?: error("Fichier illisible.")
        require(bytes.size == NativeCore.GBA_BIOS_SIZE) {
            "Ce fichier n'est pas un BIOS GBA : il doit faire 16 Kio (16 384 octets)."
        }
        file.baseFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "Impossible de créer le dossier du BIOS." } }
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        cached = bytes
        known = true
    }

    fun remove() {
        file.delete()
        cached = null
        known = true
    }
}
