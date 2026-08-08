package com.ravenemu.emulation.api.display

/**
 * Profil d'écran d'une console monochrome à quatre niveaux : il associe une
 * **palette visuelle** (quatre couleurs ARGB) aux quatre niveaux logiques
 * produits par le moteur, indépendamment de celui-ci.
 *
 * Le moteur n'écrit que les niveaux `0..3` ; le renderer applique ce profil au
 * moment de l'affichage. Un changement de profil n'affecte donc jamais l'état
 * de l'émulation et prend effet immédiatement.
 *
 * Les couleurs sont des **simulations visuelles calibrables**, non des valeurs
 * officielles : aucune palette numérique n'a été publiée pour les panneaux LCD
 * monochromes, dont la teinte réelle varie selon le panneau, son
 * vieillissement et l'éclairage ambiant.
 */
interface MonochromeDisplayProfile {
    /** Identifiant stable, persisté dans les paramètres. */
    val id: String

    /** Nom affiché dans les paramètres. */
    val displayName: String

    /** Description courte présentée à l'utilisateur. */
    val description: String

    /**
     * Exactement quatre couleurs ARGB, du niveau le plus clair au plus sombre :
     * `colors[0]` = niveau 0 (le plus clair) … `colors[3]` = niveau 3 (le plus
     * sombre). Chaque accès retourne une copie afin d'empêcher toute mutation.
     */
    val colors: IntArray
}
