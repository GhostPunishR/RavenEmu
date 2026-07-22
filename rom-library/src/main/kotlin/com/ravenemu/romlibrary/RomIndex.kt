package com.ravenemu.romlibrary

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Index local de la bibliothèque, sérialisable en JSON. Le stockage physique
 * (fichier dans l'espace privé de l'application) est du ressort du module
 * `storage` ; ce modèle reste pur JVM et testable.
 */
@Serializable
data class RomIndex(
    val version: Int = FORMAT_VERSION,
    val entries: List<RomEntry> = emptyList(),
) {

    fun byUri(uri: String): RomEntry? = entries.firstOrNull { it.uri == uri }

    /** Ajoute ou remplace l'entrée de même URI. */
    fun upsert(entry: RomEntry): RomIndex {
        val remaining = entries.filterNot { it.uri == entry.uri }
        return copy(entries = remaining + entry)
    }

    fun remove(uri: String): RomIndex =
        copy(entries = entries.filterNot { it.uri == uri })

    /**
     * Réconcilie l'index avec la liste des URI encore présents : les entrées
     * dont le fichier a disparu sont retirées.
     */
    fun retainAll(existingUris: Set<String>): RomIndex =
        copy(entries = entries.filter { it.uri in existingUris })

    /**
     * Vrai si le fichier doit être ré-analysé (nouveau, déplacé ou modifié).
     */
    fun needsRefresh(uri: String, sizeBytes: Long, lastModified: Long): Boolean {
        val entry = byUri(uri) ?: return true
        return entry.sizeBytes != sizeBytes || entry.lastModified != lastModified
    }

    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        const val FORMAT_VERSION = 1

        private val codec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Index vide en cas de JSON absent, corrompu ou d'une autre version. */
        fun fromJson(json: String): RomIndex = try {
            val index = codec.decodeFromString(serializer(), json)
            if (index.version == FORMAT_VERSION) index else RomIndex()
        } catch (_: Exception) {
            RomIndex()
        }
    }
}
