package com.ravenemu.app.emulation

import com.ravenemu.core.gb.GameBoyConsoleProvider
import com.ravenemu.core.gba.GbaConsoleProvider
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.emulation.api.ConsoleRegistry
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.romlibrary.GameBoyRomAnalyzer
import com.ravenemu.romlibrary.GbaRomAnalyzer
import com.ravenemu.romlibrary.RomAnalyzer

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

    /**
     * Analyseurs de ROM de la bibliothèque, construits sur les fournisseurs du
     * registre.
     *
     * Les analyseurs construisaient eux-mêmes leur fournisseur, ce qui obligeait
     * `rom-library` à nommer les modules de moteur concrets. Ils le reçoivent
     * désormais d'ici, seul endroit qui les connaît déjà — et c'est le **même**
     * objet que celui qui créera le moteur, si bien que les extensions
     * reconnues et la taille maximale acceptée ne peuvent plus diverger entre
     * l'indexation et le chargement.
     *
     * Le couplage de `rom-library` n'est pas supprimé pour autant : `RomEntry`
     * nomme encore des types Game Boy, par compatibilité avec l'index déjà
     * persisté chez les utilisateurs.
     */
    fun romAnalyzers(): List<RomAnalyzer> {
        val registre = registry()
        return listOfNotNull(
            registre.providerFor(ConsoleType.GAME_BOY)?.let(::GameBoyRomAnalyzer),
            registre.providerFor(ConsoleType.GAME_BOY_ADVANCE)?.let(::GbaRomAnalyzer),
        )
    }
}
