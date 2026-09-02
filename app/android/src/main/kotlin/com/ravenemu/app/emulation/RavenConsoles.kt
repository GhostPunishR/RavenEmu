package com.ravenemu.app.emulation

import com.ravenemu.core.gb.GameBoyConsoleProvider
import com.ravenemu.core.gba.GbaConsoleProvider
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.core.nds.NdsConsoleProvider
import com.ravenemu.emulation.api.ConsoleRegistry
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.romlibrary.GameBoyRomAnalyzer
import com.ravenemu.romlibrary.GbaRomAnalyzer
import com.ravenemu.romlibrary.NdsRomAnalyzer
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
     * @param forcedGbaRtc présence imposée de l'horloge de cartouche Game Boy
     *   Advance, ou `null` pour la détection automatique. Également un réglage
     *   par jeu : la signature que cherche la détection manque à certaines ROM
     *   modifiées qui pilotent pourtant l'horloge.
     */
    fun registry(
        forcedGbaSaveType: String? = null,
        forcedGbaRtc: Boolean? = null,
    ): ConsoleRegistry = ConsoleRegistry(
        listOf(
            GameBoyConsoleProvider(),
            GbaConsoleProvider(
                forcedSaveType = forcedGbaSaveType
                    ?.let { name -> runCatching { GbaSaveType.valueOf(name) }.getOrNull() },
                forcedRtc = forcedGbaRtc,
            ),
            NdsConsoleProvider(),
        )
    )

    /**
     * `true` si les moteurs de [console] savent enregistrer un instantané.
     *
     * Posée au fournisseur, donc sans construire de moteur : la bibliothèque
     * décide d'offrir ou non les états d'un jeu qu'elle n'a pas lancé. Une
     * console absente du registre répond faux, ce qui est le comportement utile
     * — on ne propose pas d'enregistrer l'état d'un moteur qui n'existe pas.
     */
    fun supportsSaveState(console: ConsoleType): Boolean =
        registry().providerFor(console)?.supportsSaveState == true

    /**
     * Analyseurs de ROM de la bibliothèque, construits sur les fournisseurs du
     * registre.
     *
     * Les analyseurs construisaient eux-mêmes leur fournisseur, ce qui obligeait
     * `rom-library` à nommer les modules de moteur concrets. Ils le reçoivent
     * désormais d'ici, seul endroit qui les connaît déjà : les extensions
     * reconnues et la taille maximale acceptée viennent donc du contrat publié
     * par le module de moteur, et non d'une seconde déclaration tenue à jour en
     * parallèle.
     *
     * Chaque appel construit son propre registre : l'analyseur et le moteur
     * n'utilisent pas la même instance de fournisseur. Ce qu'ils partagent est
     * la règle, pas l'objet.
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
            registre.providerFor(ConsoleType.NINTENDO_DS)?.let(::NdsRomAnalyzer),
        )
    }
}
