package com.ravenemu.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ravenemu.romlibrary.RomEntry

/**
 * Résolution locale des pochettes, sans jamais distribuer ni télécharger de
 * contenu : image choisie manuellement (mémorisée dans l'entrée d'index),
 * image voisine de la ROM, ou dossier de pochettes de l'utilisateur, par nom
 * de fichier ou par empreinte SHA-256. En dernier recours l'interface
 * affiche une jaquette générée à partir du titre.
 */
class CoverResolver(private val context: Context) {

    private val imageExtensions = listOf("png", "jpg", "jpeg", "webp")

    /**
     * Cherche la pochette d'une ROM dans l'ordre de priorité :
     * 1. choix manuel enregistré ; 2. image de même nom à côté de la ROM ;
     * 3. dossier de pochettes par nom ; 4. dossier de pochettes par empreinte.
     */
    fun resolve(entry: RomEntry, romParentUri: Uri?, coversDirUri: Uri?): Uri? {
        entry.coverUri?.let { return Uri.parse(it) }

        val baseName = entry.fileName.substringBeforeLast('.')
        romParentUri?.let { parent ->
            findImage(parent, baseName)?.let { return it }
        }
        coversDirUri?.let { covers ->
            findImage(covers, baseName)?.let { return it }
            findImage(covers, entry.fingerprints.sha256)?.let { return it }
        }
        return null
    }

    private fun findImage(dirUri: Uri, baseName: String): Uri? {
        val dir = try {
            DocumentFile.fromTreeUri(context, dirUri)
        } catch (_: Exception) {
            null
        } ?: return null
        for (extension in imageExtensions) {
            val doc = dir.findFile("$baseName.$extension")
            if (doc != null && doc.isFile) return doc.uri
        }
        return null
    }
}
