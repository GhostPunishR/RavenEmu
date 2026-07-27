package com.ravenemu.romlibrary

import com.ravenemu.emulation.api.ConsoleType

/**
 * Filtres de la bibliothèque, exprimés indépendamment de l'interface pour être
 * testables sans Android.
 *
 * La distinction Game Boy / Game Boy Color est ici un filtre **sur la
 * cartouche**, pas sur le moteur : un seul cœur sert les deux, et une entrée
 * couleur est un sous-ensemble des entrées Game Boy. Filtrer sur « Game Boy »
 * montre donc bien toute la gamme, monochrome et couleur.
 */
object LibraryFilter {

    /** Aucune restriction. */
    const val ALL = "all"

    /** Cartouches Game Boy déclarant des fonctions couleur. */
    const val GAME_BOY_COLOR_CARTRIDGES = "GAME_BOY_CGB"

    /** Cartouches Game Boy strictement monochromes. */
    const val GAME_BOY_MONOCHROME_CARTRIDGES = "GAME_BOY_DMG"

    /**
     * Valeurs enregistrées par des versions antérieures et leur équivalent
     * actuel. `GAME_BOY_COLOR` était un nom de console ; c'est aujourd'hui un
     * filtre sur la cartouche.
     */
    private val LEGACY_ALIASES = mapOf("GAME_BOY_COLOR" to GAME_BOY_COLOR_CARTRIDGES)

    /** Filtres reconnus, en plus des noms de [ConsoleType]. */
    private val CARTRIDGE_FILTERS = setOf(
        GAME_BOY_COLOR_CARTRIDGES,
        GAME_BOY_MONOCHROME_CARTRIDGES,
    )

    /**
     * Ramène [filter] à une valeur reconnue. Un filtre inconnu — réglage d'une
     * version future, fichier de préférences abîmé — devient [ALL] : mieux vaut
     * afficher toute la bibliothèque qu'une liste vide dont l'utilisateur ne
     * comprendrait pas la cause.
     */
    fun normalize(filter: String): String {
        LEGACY_ALIASES[filter]?.let { return it }
        if (filter == ALL || filter in CARTRIDGE_FILTERS) return filter
        if (ConsoleType.entries.any { it.name == filter }) return filter
        return ALL
    }

    fun matches(entry: RomEntry, filter: String): Boolean =
        when (val normalized = normalize(filter)) {
            ALL -> true
            GAME_BOY_COLOR_CARTRIDGES ->
                entry.console == ConsoleType.GAME_BOY && entry.supportsCgb
            GAME_BOY_MONOCHROME_CARTRIDGES ->
                entry.console == ConsoleType.GAME_BOY && !entry.supportsCgb
            else -> entry.console.name == normalized
        }

    fun apply(entries: List<RomEntry>, filter: String): List<RomEntry> =
        if (normalize(filter) == ALL) entries else entries.filter { matches(it, filter) }
}
