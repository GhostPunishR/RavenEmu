package com.ravenemu.app.emulation

import com.ravenemu.core.gb.GameBoyCore
import com.ravenemu.core.gba.GbaCore
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
class RavenEmulatorCoreFactory : EmulatorCoreFactory {

    override val supportedConsoles: Set<ConsoleType> = setOf(
        ConsoleType.GAME_BOY,
        ConsoleType.GAME_BOY_COLOR,
        ConsoleType.GAME_BOY_ADVANCE,
    )

    override fun create(console: ConsoleType): EmulatorCore = when (console) {
        ConsoleType.GAME_BOY, ConsoleType.GAME_BOY_COLOR -> GameBoyCore()
        ConsoleType.GAME_BOY_ADVANCE -> GbaCore()
    }
}
