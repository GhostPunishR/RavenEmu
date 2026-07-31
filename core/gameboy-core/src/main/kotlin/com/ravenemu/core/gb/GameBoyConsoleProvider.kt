package com.ravenemu.core.gb

import com.ravenemu.core.gb.cartridge.CartridgeHeader
import com.ravenemu.emulation.api.ConsoleProvider
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorCore

/**
 * Déclaration de la console Game Boy par le module qui l'émule.
 *
 * Un seul fournisseur couvre toute la gamme, `.gb` comme `.gbc` : la
 * compatibilité couleur est une propriété de la cartouche, lue dans son
 * en-tête, pas un choix de moteur.
 */
class GameBoyConsoleProvider : ConsoleProvider {

    override val console: ConsoleType = ConsoleType.GAME_BOY

    override val maxRomSizeBytes: Int = CartridgeHeader.MAX_ROM_SIZE

    override fun createCore(): EmulatorCore = GameBoyCore()
}
