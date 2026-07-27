package com.ravenemu.emulation.api

/**
 * Consoles connues de RavenEmu. Chaque entrée désigne **un cœur d'émulation**,
 * pas un modèle de console commercialisé : le module `gameboy-core` couvre à
 * lui seul la Game Boy et la Game Boy Color, et n'apparaît donc qu'une fois.
 *
 * Ce que la cartouche déclare — monochrome, compatible couleur, ou couleur
 * exigée — est une métadonnée distincte, portée par
 * `GameBoyCartridgeMode` dans `gameboy-core`. Confondre les deux amenait à
 * choisir un moteur d'après une extension de fichier, alors que des `.gbc`
 * contiennent des cartouches monochromes et des `.gb` des cartouches couleur.
 *
 * L'application ne manipule que cette énumération et les interfaces de ce
 * module.
 */
enum class ConsoleType(
    /** Nom affichable de la console. */
    val displayName: String,
    /** Extensions de fichier ROM reconnues, en minuscules, sans point. */
    val romExtensions: Set<String>,
    /**
     * Identifiant écrit dans les formats persistés (états instantanés).
     *
     * Il remplace `ordinal`, qui dépend de l'ordre de déclaration : insérer une
     * console ou en retirer une décalerait silencieusement toutes les valeurs
     * déjà écrites dans les fichiers des utilisateurs, et un état Game Boy
     * serait alors accepté comme un état d'une autre console. Cet identifiant
     * est **figé** : une valeur attribuée ne change jamais et n'est jamais
     * réattribuée à une autre console. Les valeurs actuelles reprennent les
     * `ordinal` historiques, les états déjà enregistrés restent lisibles.
     */
    val storageId: Int,
) {
    // Cœur Game Boy (module gameboy-core) : cartouches DMG et Game Boy Color,
    // `.gb` comme `.gbc`. Le mode exact vient de l'en-tête, pas de l'extension.
    GAME_BOY("Game Boy", setOf("gb", "gbc"), storageId = 0),

    // Game Boy Advance : moteur ARM7TDMI dédié (module gba-core).
    GAME_BOY_ADVANCE("Game Boy Advance", setOf("gba"), storageId = 2),
    ;

    companion object {
        /**
         * Identifiants retirés, à ne jamais réattribuer.
         *
         * `1` désignait `GAME_BOY_COLOR`, une seconde entrée pour le cœur Game
         * Boy. Elle a disparu au profit de la métadonnée de cartouche, mais des
         * fichiers l'ayant enregistrée peuvent exister : réutiliser cette valeur
         * ferait accepter un ancien état pour la mauvaise console.
         */
        val RETIRED_STORAGE_IDS: Set<Int> = setOf(1)

        /** Console portant [storageId], ou `null` si l'identifiant est inconnu. */
        fun fromStorageId(storageId: Int): ConsoleType? =
            entries.firstOrNull { it.storageId == storageId }
    }
}
