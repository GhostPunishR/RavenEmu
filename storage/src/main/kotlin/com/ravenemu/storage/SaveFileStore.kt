package com.ravenemu.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * Sauvegardes natives `.sav` (RAM de cartouche).
 *
 * Le fichier de référence vit dans l'espace privé de l'application
 * (`files/saves/<empreinte>/<nom>.sav`) et est écrit de manière atomique :
 * fichier temporaire puis remplacement, pour qu'une interruption ne corrompe
 * jamais la sauvegarde. Si l'utilisateur a choisi un dossier `.sav` via SAF,
 * une copie y est écrite à chaque sauvegarde et il sert de source à
 * l'importation ; le format reste le dump brut compatible avec les autres
 * émulateurs.
 */
class SaveFileStore(private val context: Context) {

    private fun privateDir(romSha256: String): File =
        File(File(context.filesDir, "saves"), romSha256).apply { mkdirs() }

    private fun savName(romFileName: String): String =
        romFileName.substringBeforeLast('.') + ".sav"

    /** Fichier `.sav` privé associé à une ROM (peut ne pas exister). */
    fun privateSaveFile(romSha256: String, romFileName: String): File =
        File(privateDir(romSha256), savName(romFileName))

    /**
     * Écrit la sauvegarde de manière atomique dans l'espace privé, puis la
     * recopie dans [externalDir] si fourni. Retourne `true` si l'écriture
     * privée a abouti.
     */
    fun write(
        romSha256: String,
        romFileName: String,
        data: ByteArray,
        externalDir: Uri? = null,
    ): Boolean {
        val target = privateSaveFile(romSha256, romFileName)
        val temp = File(target.parentFile, target.name + ".tmp")
        return try {
            temp.outputStream().use { stream ->
                stream.write(data)
                stream.fd.sync()
            }
            if (!temp.renameTo(target)) {
                // Repli si le renommage échoue (systèmes de fichiers exotiques).
                target.writeBytes(data)
                temp.delete()
            }
            if (externalDir != null) copyToExternal(externalDir, savName(romFileName), data)
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    /**
     * Charge la sauvegarde : priorité au fichier privé, sinon au dossier SAF
     * (cas d'une sauvegarde importée d'un autre émulateur).
     */
    fun read(
        romSha256: String,
        romFileName: String,
        externalDir: Uri? = null,
    ): ByteArray? {
        val private = privateSaveFile(romSha256, romFileName)
        if (private.isFile) {
            return try {
                private.readBytes()
            } catch (_: Exception) {
                null
            }
        }
        if (externalDir != null) {
            return readFromExternal(externalDir, savName(romFileName))
        }
        return null
    }

    /** Exporte la sauvegarde privée vers un document choisi par l'utilisateur. */
    fun exportTo(romSha256: String, romFileName: String, destination: Uri): Boolean {
        val data = read(romSha256, romFileName) ?: return false
        return try {
            context.contentResolver.openOutputStream(destination, "wt")?.use {
                it.write(data)
            } != null
        } catch (_: Exception) {
            false
        }
    }

    /** Importe un `.sav` externe comme sauvegarde privée (écriture atomique). */
    fun importFrom(romSha256: String, romFileName: String, source: Uri): Boolean {
        val data = try {
            context.contentResolver.openInputStream(source)?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } ?: return false
        if (data.size > MAX_SAVE_SIZE) return false
        return write(romSha256, romFileName, data)
    }

    private fun copyToExternal(dirUri: Uri, fileName: String, data: ByteArray) {
        try {
            val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return
            val existing = dir.findFile(fileName)
            val doc = existing ?: dir.createFile("application/octet-stream", fileName)
            doc?.let { file ->
                context.contentResolver.openOutputStream(file.uri, "wt")?.use {
                    it.write(data)
                }
            }
        } catch (_: Exception) {
            // La copie externe est best-effort : la version privée fait foi.
        }
    }

    private fun readFromExternal(dirUri: Uri, fileName: String): ByteArray? {
        return try {
            val dir = DocumentFile.fromTreeUri(context, dirUri) ?: return null
            val doc = dir.findFile(fileName) ?: return null
            context.contentResolver.openInputStream(doc.uri)?.use { stream ->
                val data = stream.readBytes()
                if (data.size > MAX_SAVE_SIZE) null else data
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** 128 KiB de RAM + pied RTC : au-delà, fichier considéré invalide. */
        const val MAX_SAVE_SIZE = 128 * 1024 + 48
    }
}
