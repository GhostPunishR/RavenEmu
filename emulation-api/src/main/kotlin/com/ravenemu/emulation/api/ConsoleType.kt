package com.ravenemu.emulation.api

/**
 * Consoles connues de RavenEmu. Chaque console est servie par un module de
 * moteur dédié implémentant [EmulatorCore] ; l'application ne manipule que
 * cette énumération et les interfaces de ce module.
 */
enum class ConsoleType(
    /** Nom affichable de la console. */
    val displayName: String,
    /** Extensions de fichier ROM reconnues, en minuscules, sans point. */
    val romExtensions: Set<String>,
    /**
     * Identifiant écrit dans les formats persistés (états instantanés).
     *
     * Il remplace `ordinal`, qui dépend de l'ordre de déclaration : insérer une
     * console ou en retirer une décalerait silencieusement toutes les valeurs
     * déjà écrites dans les fichiers des utilisateurs, et un état Game Boy
     * serait alors accepté comme un état d'une autre console. Cet identifiant
     * est **figé** : une valeur attribuée ne change jamais et n'est jamais
     * réattribuée à une autre console. Les valeurs actuelles reprennent les
     * `ordinal` historiques, les états déjà enregistrés restent lisibles.
     */
    val storageId: Int,
) {
    // Le moteur Game Boy prend en charge les cartouches DMG et Game Boy
    // Color ; les deux extensions sont donc indexées par le même cœur.
    GAME_BOY("Game Boy", setOf("gb", "gbc"), storageId = 0),
    GAME_BOY_COLOR("Game Boy Color", setOf("gbc", "gb"), storageId = 1),

    // Game Boy Advance : moteur ARM7TDMI dédié (module gba-core).
    GAME_BOY_ADVANCE("Game Boy Advance", setOf("gba"), storageId = 2),
    ;

    companion object {
        /** Console portant [storageId], ou `null` si l'identifiant est inconnu. */
        fun fromStorageId(storageId: Int): ConsoleType? =
            entries.firstOrNull { it.storageId == storageId }
    }
}
