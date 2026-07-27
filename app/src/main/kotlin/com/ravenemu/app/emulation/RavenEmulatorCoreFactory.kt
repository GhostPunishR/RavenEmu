package com.ravenemu.app.emulation

import com.ravenemu.core.gb.GameBoyCore
import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.EmulatorCoreFactory

/**
 * Fabrique de moteurs de production : c'est la **racine de composition** de
 * RavenEmu, le seul point du code qui connaît tous les moteurs concrets.
 * L'application passe toujours par cette fabrique et n'instancie plus aucun
 * moteur directement, ce qui permet d'ajouter une console sans modifier les
 * écrans d'émulation.
 */
class RavenEmulatorCoreFactory(
    /**
     * Type de mémoire de sauvegarde imposé aux moteurs Game Boy Advance
     * (nom d'énumération `GbaSaveType`), ou `null` pour la détection
     * automatique. Réglage par jeu de la bibliothèque.
     */
    private val forcedGbaSaveType: String? = null,
) : EmulatorCoreFactory {

    override val supportedConsoles: Set<ConsoleType> = setOf(
        ConsoleType.GAME_BOY,
        ConsoleType.GAME_BOY_ADVANCE,
    )

    // Le cœur Game Boy sert toute la gamme : c'est l'en-tête de la cartouche,
    // et non la console demandée, qui décide des fonctions couleur.
    override fun create(console: ConsoleType): EmulatorCore = when (console) {
        ConsoleType.GAME_BOY -> GameBoyCore()
        ConsoleType.GAME_BOY_ADVANCE -> GbaCore(
            forcedSaveType = forcedGbaSaveType
                ?.let { name -> runCatching { GbaSaveType.valueOf(name) }.getOrNull() },
        )
    }
}
