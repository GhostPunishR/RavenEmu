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
) {
    // Le moteur Game Boy prend en charge les cartouches DMG et Game Boy
    // Color ; les deux extensions sont donc indexées par le même cœur.
    GAME_BOY("Game Boy", setOf("gb", "gbc")),
    GAME_BOY_COLOR("Game Boy Color", setOf("gbc", "gb")),
}
