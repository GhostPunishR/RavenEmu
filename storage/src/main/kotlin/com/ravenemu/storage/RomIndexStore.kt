package com.ravenemu.storage

import android.content.Context
import com.ravenemu.romlibrary.RomIndex
import java.io.File

/**
 * Persistance de l'index de bibliothèque (JSON dans l'espace privé). Un
 * fichier corrompu ou d'une version inconnue redonne simplement un index
 * vide : la bibliothèque se reconstruit à l'actualisation suivante.
 */
class RomIndexStore(private val context: Context) {

    private val indexFile: File get() = File(context.filesDir, "rom-index.json")

    fun load(): RomIndex = try {
        if (indexFile.isFile) RomIndex.fromJson(indexFile.readText()) else RomIndex()
    } catch (_: Exception) {
        RomIndex()
    }

    fun save(index: RomIndex): Boolean {
        val temp = File(indexFile.parentFile, indexFile.name + ".tmp")
        return try {
            temp.writeText(index.toJson())
            if (!temp.renameTo(indexFile)) {
                indexFile.writeText(index.toJson())
                temp.delete()
            }
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    /** Nettoyage complet demandé depuis les paramètres. */
    fun clear() {
        indexFile.delete()
    }
}
