package com.ravenemu.core.nds

import com.ravenemu.emulation.api.ConsoleProvider
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorCore

/**
 * Déclaration de la console Nintendo DS par le module qui l'émule.
 */
class NdsConsoleProvider : ConsoleProvider {

    override val console: ConsoleType = ConsoleType.NINTENDO_DS

    override val maxRomSizeBytes: Int = MAX_ROM_SIZE

    override val supportsSaveState: Boolean = NdsCore.SUPPORTS_SAVE_STATE

    override fun createCore(): EmulatorCore = NdsCore()

    companion object {
        /**
         * Plafond des ROM chargeables, 512 Mio.
         *
         * Ce n'est **pas** la limite du matériel : la console adresse jusqu'à
         * 4 Gio. C'est la plus grosse cartouche qui ait réellement existé, et
         * ce cœur garde l'image entière en mémoire native pour que le bus de
         * cartouche la relise à la demande.
         *
         * Ce plafond valait 128 Mio tant que l'image traversait le tas Java :
         * ce n'était pas la console qui l'imposait, mais le tas d'une
         * application Android, plafonné bien en dessous de la mémoire de
         * l'appareil. `loadRomFromDescriptor` ne l'emprunte plus.
         */
        const val MAX_ROM_SIZE = 0x2000_0000
    }
}
