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
     * Vrai si le fichier doit être ré-analysé (nouveau, déplacé, modifié, ou
     * indexé par une version antérieure dont les métadonnées sont incomplètes).
     */
    fun needsRefresh(uri: String, sizeBytes: Long, lastModified: Long): Boolean {
        val entry = byUri(uri) ?: return true
        if (entry.needsReanalysis) return true
        return entry.sizeBytes != sizeBytes || entry.lastModified != lastModified
    }

    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        /**
         * Version 2 : la compatibilité de cartouche Game Boy est portée par
         * `RomEntry.cartridgeMode` (trois valeurs) au lieu du booléen
         * `supportsCgb`, et la seconde console `GAME_BOY_COLOR` a disparu.
         */
        const val FORMAT_VERSION = 2

        /** Première version acceptée en lecture, migrée vers [FORMAT_VERSION]. */
        private const val OLDEST_MIGRATABLE_VERSION = 1

        private val codec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Index relu depuis son JSON, migré si nécessaire ; index vide si le
         * fichier est absent, corrompu ou d'une version qu'on ne sait pas lire.
         *
         * Une version 1 est **conservée**, pas jetée : les pochettes choisies à
         * la main et les entrées déjà connues survivent. Ses entrées Game Boy
         * perdent seulement leur compatibilité de cartouche, que le prochain
         * balayage relit dans l'en-tête (voir [needsRefresh]). Repartir d'un
         * index vide aurait été plus simple à écrire et bien plus coûteux pour
         * l'utilisateur.
         */
        fun fromJson(json: String): RomIndex = try {
            val index = codec.decodeFromString(serializer(), json)
            when (index.version) {
                FORMAT_VERSION -> index
                in OLDEST_MIGRATABLE_VERSION until FORMAT_VERSION ->
                    index.copy(version = FORMAT_VERSION)
                else -> RomIndex()
            }
        } catch (_: Exception) {
            RomIndex()
        }
    }
}
