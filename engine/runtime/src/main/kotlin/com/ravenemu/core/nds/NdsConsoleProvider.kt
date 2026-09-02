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
         * Plafond des ROM indexables, 128 Mio.
         *
         * Ce n'est **pas** la limite du matériel : la console adresse jusqu'à
         * 4 Gio, et la plus grosse cartouche produite fait 512 Mio. C'est la
         * limite de ce que l'application sait indexer. La bibliothèque lit une
         * ROM entière pour en calculer les empreintes, et la lit dans un tampon
         * qui grandit avant d'être recopié : le pic de mémoire vaut donc deux
         * fois le fichier, et une demi-cartouche de 512 Mio demanderait un Gio à
         * un téléphone.
         *
         * Ce plafond couvre la grande majorité des cartouches, dont les tailles
         * courantes vont de 16 à 128 Mio. Le relever suppose de calculer les
         * empreintes au fil de la lecture plutôt que sur une image complète en
         * mémoire ; c'est un changement de la bibliothèque, pas de ce cœur.
         */
        const val MAX_ROM_SIZE = 0x0800_0000
    }
}
