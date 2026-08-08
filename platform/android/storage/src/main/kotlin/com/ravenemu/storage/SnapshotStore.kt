package com.ravenemu.storage

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Métadonnées d'un état instantané présent sur disque. */
data class SnapshotInfo(
    val slot: Int,
    val file: File,
    val savedAt: Long,
    val sizeBytes: Long,
)

/**
 * États instantanés RavenEmu (format binaire versionné `RVNS` produit par le
 * moteur). Stockés dans l'espace privé, classés par empreinte de ROM et par
 * emplacement. Écriture atomique comme pour les `.sav`.
 */
class SnapshotStore(private val context: Context) {

    private fun dir(romSha256: String): File =
        File(File(context.filesDir, "states"), romSha256).apply { mkdirs() }

    private fun file(romSha256: String, slot: Int): File =
        File(dir(romSha256), "slot$slot.rvns")

    fun write(romSha256: String, slot: Int, state: ByteArray): Boolean {
        val atomic = AtomicFile(file(romSha256, slot))
        var stream: java.io.FileOutputStream? = null
        return try {
            val output = atomic.startWrite()
            stream = output
            output.write(state)
            atomic.finishWrite(output)
            stream = null
            true
        } catch (_: Exception) {
            stream?.let(atomic::failWrite)
            false
        }
    }

    fun read(romSha256: String, slot: Int): ByteArray? {
        val f = file(romSha256, slot)
        return try {
            if (f.isFile) f.readBytes() else null
        } catch (_: Exception) {
            null
        }
    }

    fun delete(romSha256: String, slot: Int) {
        file(romSha256, slot).delete()
    }

    fun list(romSha256: String): List<SnapshotInfo> {
        val files = dir(romSha256).listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            val slot = f.name.removePrefix("slot").removeSuffix(".rvns").toIntOrNull()
                ?: return@mapNotNull null
            SnapshotInfo(slot, f, f.lastModified(), f.length())
        }.sortedBy { it.slot }
    }

    companion object {
        /** Emplacement réservé à la sauvegarde de secours du cycle de vie. */
        const val AUTO_SLOT = 0
    }
}
