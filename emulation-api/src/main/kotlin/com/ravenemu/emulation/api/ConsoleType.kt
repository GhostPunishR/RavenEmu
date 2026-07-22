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
    GAME_BOY("Game Boy", setOf("gb")),
    GAME_BOY_COLOR("Game Boy Color", setOf("gbc", "gb")),
}
