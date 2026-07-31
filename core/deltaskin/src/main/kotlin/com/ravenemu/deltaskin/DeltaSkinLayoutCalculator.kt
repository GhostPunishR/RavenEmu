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
)

object DeltaSkinLayoutCalculator {
    fun calculate(
        containerWidth: Double,
        containerHeight: Double,
        insets: DeltaSkinInsets,
        mappingSize: DeltaSkinSize,
        nativeScreenSize: DeltaSkinSize,
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
