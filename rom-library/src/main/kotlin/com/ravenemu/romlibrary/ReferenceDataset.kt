package com.ravenemu.romlibrary

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Jeu de données de référence sérialisable (format natif RavenEmu). Ne
 * contient que des métadonnées et des empreintes, jamais de ROM. Plusieurs
 * jeux de données peuvent être fusionnés en une [ReferenceDatabase].
 */
@Serializable
data class ReferenceDataset(
    val version: Int = FORMAT_VERSION,
    /** Provenance libre (ex. « No-Intro », « homebrew perso »). */
    val source: String = "",
    val entries: List<ReferenceEntry> = emptyList(),
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        const val FORMAT_VERSION = 1

        private val codec = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /** Analyse un dataset JSON, ou `null` si le format est invalide. */
        fun fromJson(json: String): ReferenceDataset? = try {
            codec.decodeFromString(serializer(), json)
        } catch (_: Exception) {
            null
        }

        /** Fusionne plusieurs jeux de données en une base unique. */
        fun merge(datasets: List<ReferenceDataset>): ReferenceDatabase =
            ReferenceDatabase(datasets.flatMap { it.entries })
    }
}
