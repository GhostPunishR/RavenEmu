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
    /**
     * Résolution de l'écran tactile, ou `null` pour une console qui n'en a pas.
     *
     * Elle est ici et non dans le cœur parce que c'est l'interface qui en a
     * besoin : elle reçoit un doigt quelque part sur une dalle d'appareil et
     * doit le ramener en pixels de la console avant de le confier au moteur.
     * Le cœur, lui, ne reçoit que le résultat.
     */
    val touchScreen: TouchScreenSize? = null,
    /**
     * Touches que la console possède réellement.
     *
     * Elle est ici, et non dans les couches qui dessinent des commandes, parce
     * que c'est un fait de la console et non un choix d'interface. Deux
     * endroits en avaient besoin sans se parler : la disposition tactile
     * classique n'affichait ni gâchettes ni touches supplémentaires à la
     * Nintendo DS, quand les skins Delta les honoraient déjà. Une console
     * n'ayant qu'un jeu de touches, elle n'a qu'une déclaration.
     */
    val buttons: Set<EmulatorButton> = ConsoleButtons.FACE,
    /**
     * Suffixe des profils de commandes tactiles enregistrés.
     *
     * Il est **figé** au même titre que [storageId] : ce mot entre dans le nom
     * sous lequel une disposition personnalisée est rangée, et le changer
     * rendrait introuvable ce qu'un joueur a réglé. Il est court parce qu'il
     * n'est jamais montré ; le nom affiché est [displayName].
     */
    val layoutKey: String,
) {
    // Cœur Game Boy (module gameboy-core) : cartouches DMG et Game Boy Color,
    // `.gb` comme `.gbc`. Le mode exact vient de l'en-tête, pas de l'extension.
    GAME_BOY("Game Boy", setOf("gb", "gbc"), storageId = 0, layoutKey = "gb"),

    // Game Boy Advance : moteur ARM7TDMI dédié (module gba-core).
    GAME_BOY_ADVANCE(
        "Game Boy Advance",
        setOf("gba"),
        storageId = 2,
        buttons = ConsoleButtons.FACE + ConsoleButtons.SHOULDERS,
        layoutKey = "gba",
    ),

    // Nintendo DS : deux processeurs, deux écrans (module nds-core). La valeur
    // 3 est la première libre, 1 restant retiré.
    NINTENDO_DS(
        "Nintendo DS",
        setOf("nds"),
        storageId = 3,
        // L'écran du bas, seul tactile des deux.
        touchScreen = TouchScreenSize(width = 256, height = 192),
        buttons = ConsoleButtons.FACE + ConsoleButtons.SHOULDERS + ConsoleButtons.X_AND_Y,
        layoutKey = "nds",
    ),
    ;

    /** Taille en pixels d'un écran tactile de console. */
    data class TouchScreenSize(val width: Int, val height: Int) {
        /**
         * Ramène une position donnée en fractions de la dalle vers un pixel.
         *
         * Les deux fractions sont bornées : un doigt qui déborde est retenu sur
         * le bord, comme un stylet qui sort de l'écran d'une console. Le pixel
         * rendu reste donc toujours dans l'écran, quelle que soit la façon dont
         * l'appelant a mesuré.
         */
        fun pixelAt(fractionX: Double, fractionY: Double): Pair<Int, Int> = Pair(
            (fractionX * width).toInt().coerceIn(0, width - 1),
            (fractionY * height).toInt().coerceIn(0, height - 1),
        )
    }

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
