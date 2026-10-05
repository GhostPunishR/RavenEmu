package com.ravenemu.emulation.api

/**
 * Tout ce que RavenEmu doit savoir d'une console, en un seul objet fourni par
 * le module qui l'émule.
 *
 * Cette connaissance était jusqu'ici éparpillée : la fabrique de moteurs
 * énumérait les consoles d'un côté, la bibliothèque redécidait de son côté
 * quelles extensions elle savait lire et quelle taille de ROM elle acceptait.
 * Ajouter une console demandait donc de retrouver et de modifier chacun de ces
 * endroits, sans que rien ne signale un oubli.
 *
 * Un module de moteur publie désormais son [ConsoleProvider] ; la racine de
 * composition les rassemble dans un [ConsoleRegistry] et le reste de
 * l'application n'interroge plus que ce registre.
 */
interface ConsoleProvider {

    /** Console servie par ce module. Unique dans un [ConsoleRegistry]. */
    val console: ConsoleType

    /**
     * Extensions de fichier reconnues, en minuscules et sans point. Par défaut
     * celles déclarées par [console].
     *
     * Une extension est une **indication**, pas une preuve : le contenu du
     * fichier reste seul juge de ce qu'il contient. Elle sert à ne pas lire
     * intégralement des fichiers manifestement étrangers.
     */
    val romExtensions: Set<String> get() = console.romExtensions

    /**
     * Taille maximale acceptée par le moteur, en octets. Au-delà, le fichier
     * est refusé sans être chargé en mémoire.
     */
    val maxRomSizeBytes: Int

    /**
     * `true` si les moteurs de cette console savent enregistrer un instantané.
     *
     * La même réponse que [EmulatorCore.supportsSaveState], mais donnée **sans
     * construire de moteur** : la bibliothèque décide d'offrir ou non les états
     * d'un jeu qu'elle n'a pas lancé, et allouer un cœur natif pour poser la
     * question coûterait plus que la question elle-même.
     */
    val supportsSaveState: Boolean get() = true

    /** `true` si l'extension de [fileName] est reconnue par cette console. */
    fun handles(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in romExtensions

    /** Crée un moteur neuf, sans ROM chargée. */
    fun createCore(): EmulatorCore
}
