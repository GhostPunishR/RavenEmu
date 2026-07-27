package com.ravenemu.app.emulation

import com.ravenemu.core.gb.GameBoyConsoleProvider
import com.ravenemu.core.gba.GbaConsoleProvider
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.emulation.api.ConsoleRegistry

/**
 * Racine de composition de RavenEmu : le seul point du code qui nomme les
 * modules de moteur concrets.
 *
 * Ajouter une console consiste désormais à ajouter son
 * [com.ravenemu.emulation.api.ConsoleProvider] à cette liste. Le reste de
 * l'application n'interroge que le registre obtenu — qui reste une
 * [com.ravenemu.emulation.api.EmulatorCoreFactory] — et aucun écran n'a plus à
 * énumérer les consoles ni à choisir un moteur.
 */
object RavenConsoles {

    /**
     * Registre des consoles disponibles.
     *
     * @param forcedGbaSaveType type de mémoire de sauvegarde imposé aux moteurs
     *   Game Boy Advance (nom d'énumération `GbaSaveType`), ou `null` pour la
     *   détection automatique. Réglage par jeu de la bibliothèque ; un nom
     *   inconnu est ignoré au profit de la détection.
     */
    fun registry(forcedGbaSaveType: String? = null): ConsoleRegistry = ConsoleRegistry(
        listOf(
            GameBoyConsoleProvider(),
            GbaConsoleProvider(
                forcedSaveType = forcedGbaSaveType
                    ?.let { name -> runCatching { GbaSaveType.valueOf(name) }.getOrNull() },
            ),
        )
    )
}
