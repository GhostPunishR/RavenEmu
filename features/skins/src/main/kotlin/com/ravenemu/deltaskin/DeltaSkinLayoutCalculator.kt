package com.ravenemu.deltaskin

import kotlin.math.min

data class DeltaSkinRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top

    fun contains(x: Double, y: Double): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    fun intersect(other: DeltaSkinRect): DeltaSkinRect? {
        val result = DeltaSkinRect(
            left = maxOf(left, other.left),
            top = maxOf(top, other.top),
            right = minOf(right, other.right),
            bottom = minOf(bottom, other.bottom),
        )
        return result.takeIf { it.width > 0.0 && it.height > 0.0 }
    }
}

data class DeltaSkinInsets(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
)

data class DeltaSkinLayout(
    val panel: DeltaSkinRect,
    val gameArea: DeltaSkinRect,
    val gameScreen: DeltaSkinRect,
    val scale: Double,
    /**
     * Où chaque écran de la console se pose, quand le skin le dit lui-même.
     *
     * Vide pour un skin qui ne place pas ses écrans : le jeu occupe alors
     * [gameArea] tout entier, à son ratio. Une liste de deux entrées est le cas
     * ordinaire d'une Nintendo DS, dont les deux écrans sont dessinés à des
     * endroits que le skin choisit et qui ne se touchent pas forcément.
     */
    val screens: List<DeltaSkinScreenPlacement> = emptyList(),
)

/**
 * Une découpe du tampon de la console, et l'endroit où elle se pose.
 *
 * [source] est en pixels du tampon produit par le moteur, `null` valant « tout
 * le tampon » ; [destination] est en pixels de la vue. Les deux écrans d'une
 * Nintendo DS arrivent empilés dans un seul tampon : le skin les redécoupe.
 */
data class DeltaSkinScreenPlacement(
    val source: DeltaSkinFrame?,
    val destination: DeltaSkinRect,
)

object DeltaSkinLayoutCalculator {
    /**
     * Place le panneau du skin et l'écran du jeu dans la vue.
     *
     * Deux formes de skin existent, et [declaredScreens] les sépare.
     *
     * Un skin **qui place ses écrans** occupe toute la vue : le jeu se dessine
     * dans les trous que son image réserve, aux cadres qu'il donne. C'est la
     * forme de tous les skins de Nintendo DS, dont les deux écrans doivent
     * tomber de part et d'autre de la charnière dessinée. Sans cette forme, un
     * tel skin était refusé : son image est aussi haute que l'écran, il ne
     * restait aucune place au-dessus, et l'utilisateur ne voyait qu'un « skin
     * invalide » sans savoir ce qu'on lui reprochait.
     *
     * Un skin **qui ne place rien** garde la disposition historique : son
     * panneau s'ancre en bas sur toute la largeur, et le jeu prend ce qui reste
     * au-dessus.
     */
    fun calculate(
        containerWidth: Double,
        containerHeight: Double,
        insets: DeltaSkinInsets,
        mappingSize: DeltaSkinSize,
        nativeScreenSize: DeltaSkinSize,
        declaredScreens: List<DeltaSkinScreen> = emptyList(),
    ): DeltaSkinLayout? {
        if (
            containerWidth <= 0.0 ||
            containerHeight <= 0.0 ||
            mappingSize.width <= 0.0 ||
            mappingSize.height <= 0.0 ||
            nativeScreenSize.width <= 0.0 ||
            nativeScreenSize.height <= 0.0
        ) {
            return null
        }
        val contentLeft = insets.left.coerceAtLeast(0.0)
        val contentTop = insets.top.coerceAtLeast(0.0)
        val contentRight = containerWidth - insets.right.coerceAtLeast(0.0)
        val contentBottom = containerHeight - insets.bottom.coerceAtLeast(0.0)
        val panelWidth = contentRight - contentLeft
        if (panelWidth <= 0.0 || contentBottom <= contentTop) return null

        if (declaredScreens.isNotEmpty()) {
            return fullPanel(
                content = DeltaSkinRect(contentLeft, contentTop, contentRight, contentBottom),
                mappingSize = mappingSize,
                declaredScreens = declaredScreens,
            )
        }

        val scale = panelWidth / mappingSize.width
        val panelHeight = mappingSize.height * scale
        val panelTop = contentBottom - panelHeight
        if (panelTop <= contentTop) return null

        val panel = DeltaSkinRect(
            left = contentLeft,
            top = panelTop,
            right = contentRight,
            bottom = contentBottom,
        )
        val gameArea = DeltaSkinRect(
            left = contentLeft,
            top = contentTop,
            right = contentRight,
            bottom = panelTop,
        )
        val gameScale = min(
            gameArea.width / nativeScreenSize.width,
            gameArea.height / nativeScreenSize.height,
        )
        if (!gameScale.isFinite() || gameScale <= 0.0) return null
        val gameWidth = nativeScreenSize.width * gameScale
        val gameHeight = nativeScreenSize.height * gameScale
        val gameLeft = gameArea.left + (gameArea.width - gameWidth) / 2.0
        val gameTop = gameArea.top + (gameArea.height - gameHeight) / 2.0
        val screen = DeltaSkinRect(
            left = gameLeft,
            top = gameTop,
            right = gameLeft + gameWidth,
            bottom = gameTop + gameHeight,
        )
        return DeltaSkinLayout(
            panel = panel,
            gameArea = gameArea,
            gameScreen = screen,
            scale = scale,
        )
    }

    /**
     * Disposition d'un skin qui occupe toute la vue et place ses écrans.
     *
     * Le panneau est mis à l'échelle pour tenir en entier, sans déformation, et
     * centré : une image plus étroite que la vue laisse une marge de chaque
     * côté plutôt que de s'étirer. Les cadres de sortie suivent le panneau,
     * puisqu'ils sont écrits dans ses coordonnées.
     */
    private fun fullPanel(
        content: DeltaSkinRect,
        mappingSize: DeltaSkinSize,
        declaredScreens: List<DeltaSkinScreen>,
    ): DeltaSkinLayout? {
        val scale = min(
            content.width / mappingSize.width,
            content.height / mappingSize.height,
        )
        if (!scale.isFinite() || scale <= 0.0) return null
        val panelWidth = mappingSize.width * scale
        val panelHeight = mappingSize.height * scale
        val panelLeft = content.left + (content.width - panelWidth) / 2.0
        val panelTop = content.top + (content.height - panelHeight) / 2.0
        val panel = DeltaSkinRect(
            left = panelLeft,
            top = panelTop,
            right = panelLeft + panelWidth,
            bottom = panelTop + panelHeight,
        )

        val placements = declaredScreens.map { screen ->
            DeltaSkinScreenPlacement(
                source = screen.inputFrame,
                destination = mapFrame(screen.outputFrame, panel, mappingSize),
            )
        }
        // L'enveloppe des écrans sert à ce qui ne sait raisonner que sur une
        // seule zone. Le dessin, lui, suit les cadres un par un : les réunir
        // étalerait l'image d'un écran sur la charnière qui les sépare.
        val enveloppe = placements.map { it.destination }.reduce { a, b ->
            DeltaSkinRect(
                left = minOf(a.left, b.left),
                top = minOf(a.top, b.top),
                right = maxOf(a.right, b.right),
                bottom = maxOf(a.bottom, b.bottom),
            )
        }
        return DeltaSkinLayout(
            panel = panel,
            gameArea = enveloppe,
            gameScreen = enveloppe,
            scale = scale,
            screens = placements,
        )
    }

    fun mapFrame(frame: DeltaSkinFrame, panel: DeltaSkinRect, mappingSize: DeltaSkinSize): DeltaSkinRect {
        val scaleX = panel.width / mappingSize.width
        val scaleY = panel.height / mappingSize.height
        return DeltaSkinRect(
            left = panel.left + frame.x * scaleX,
            top = panel.top + frame.y * scaleY,
            right = panel.left + (frame.x + frame.width) * scaleX,
            bottom = panel.top + (frame.y + frame.height) * scaleY,
        )
    }
}
