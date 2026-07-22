package com.ravenemu.storage

import android.content.Context
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
        val target = file(romSha256, slot)
        val temp = File(target.parentFile, target.name + ".tmp")
        return try {
            temp.outputStream().use { stream ->
                stream.write(state)
                stream.fd.sync()
            }
            if (!temp.renameTo(target)) {
                target.writeBytes(state)
                temp.delete()
            }
            true
        } catch (_: Exception) {
            temp.delete()
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
