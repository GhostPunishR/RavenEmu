package com.ravenemu.core.gba

import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.emulation.api.ConsoleProvider
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorCore

/**
 * Déclaration de la console Game Boy Advance par le module qui l'émule.
 */
class GbaConsoleProvider(
    /**
     * Type de mémoire de sauvegarde imposé aux moteurs créés, ou `null` pour
     * la détection automatique. Réglage par jeu, décidé par l'appelant : le
     * fournisseur ne fait que le transmettre.
     */
    private val forcedSaveType: GbaSaveType? = null,
) : ConsoleProvider {

    override val console: ConsoleType = ConsoleType.GAME_BOY_ADVANCE

    override val maxRomSizeBytes: Int = GbaCartridge.MAX_ROM_SIZE

    override fun createCore(): EmulatorCore = GbaCore(forcedSaveType = forcedSaveType)
}
