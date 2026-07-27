package com.ravenemu.core.gb.cartridge

/**
 * Compatibilité déclarée par une cartouche de la gamme Game Boy, lue à l'octet
 * `0x0143` de l'en-tête.
 *
 * C'est une propriété **de la cartouche**, pas de la console émulée : RavenEmu
 * n'a qu'un seul cœur Game Boy, qui couvre la gamme entière et se règle sur ce
 * que la cartouche annonce. Distinguer les deux évite la confusion entretenue
 * par une énumération de consoles où « Game Boy » et « Game Boy Color » étaient
 * deux entrées alors qu'un unique moteur les sert.
 *
 * L'extension du fichier n'est pas une source fiable : des `.gbc` contiennent
 * des cartouches monochromes, des `.gb` des cartouches couleur. Seul l'octet
 * d'en-tête fait foi.
 */
enum class GameBoyCartridgeMode(
    val displayName: String,
    /** Abréviation pour les affichages contraints (vignette de bibliothèque). */
    val shortLabel: String,
) {
    /** Monochrome. Fonctionne sur toute Game Boy, sans fonctions couleur. */
    DMG_ONLY("Game Boy", "GB"),

    /** `0x80` : fonctions couleur sur GBC, repli monochrome sur Game Boy. */
    DMG_AND_CGB("Game Boy / Game Boy Color", "GB/GBC"),

    /** `0xC0` : exige une Game Boy Color, ne démarre pas sur une Game Boy. */
    CGB_ONLY("Game Boy Color", "GBC"),
    ;

    /**
     * `true` si RavenEmu doit activer les fonctions couleur pour cette
     * cartouche. Une cartouche `0x80` est jouée en couleur : l'émulateur se
     * comporte en Game Boy Color, la console que l'utilisateur possède
     * virtuellement, et non en Game Boy d'origine.
     */
    val usesColor: Boolean
        get() = this == DMG_AND_CGB || this == CGB_ONLY

    companion object {
        /** Mode déclaré par l'octet `0x0143` d'un en-tête de cartouche. */
        fun fromCgbFlag(cgbFlag: Int): GameBoyCartridgeMode = when (cgbFlag and 0xFF) {
            0xC0 -> CGB_ONLY
            0x80 -> DMG_AND_CGB
            // Toute autre valeur appartient au titre de la cartouche : sur
            // matériel d'origine, cet octet n'avait pas de rôle.
            else -> DMG_ONLY
        }
    }
}
