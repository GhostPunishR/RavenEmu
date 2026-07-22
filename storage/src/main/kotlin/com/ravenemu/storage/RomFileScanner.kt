package com.ravenemu.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/** Fichier ROM candidat découvert dans un dossier choisi par l'utilisateur. */
data class ScannedFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** URI du dossier parent, utilisé pour chercher une pochette voisine. */
    val parentUri: Uri?,
)

/**
 * Parcourt récursivement les dossiers accordés via le Storage Access
 * Framework à la recherche de fichiers ROM. Aucune permission globale de
 * stockage n'est requise : seuls les arbres choisis par l'utilisateur sont
 * lus.
 */
class RomFileScanner(private val context: Context) {

    /**
     * Liste les fichiers dont l'extension figure dans [extensions]
     * (minuscules, sans point) sous chacun des arbres [treeUris].
     */
    fun scan(treeUris: List<Uri>, extensions: Set<String>): List<ScannedFile> {
        val results = mutableListOf<ScannedFile>()
        for (treeUri in treeUris) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            scanDirectory(root, extensions, results)
        }
        return results
    }

    private fun scanDirectory(
        directory: DocumentFile,
        extensions: Set<String>,
        results: MutableList<ScannedFile>,
    ) {
        for (child in directory.listFiles()) {
            if (child.isDirectory) {
                scanDirectory(child, extensions, results)
            } else if (child.isFile) {
                val name = child.name ?: continue
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension in extensions) {
                    results += ScannedFile(
                        uri = child.uri,
                        name = name,
                        sizeBytes = child.length(),
                        lastModified = child.lastModified(),
                        parentUri = directory.uri,
                    )
                }
            }
        }
    }

    /**
     * Lit le contenu complet d'un document, avec plafond de taille pour
     * écarter les fichiers aberrants avant analyse.
     */
    fun readAll(uri: Uri, maxBytes: Int): ByteArray? {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val data = stream.readBytes()
            if (data.size > maxBytes) null else data
        }
    }
}
