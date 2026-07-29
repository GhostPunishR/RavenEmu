package com.ravenemu.input

import kotlin.math.roundToInt

/**
 * Rectangle normalisé dans le canevas du skin. Les quatre valeurs restent
 * indépendantes de la densité et de la taille physique de l'appareil.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left in 0f..1f && right in 0f..1f && left <= right)
        require(top in 0f..1f && bottom in 0f..1f && top <= bottom)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
}

/**
 * Géométrie d'une coque portrait RavenEmu.
 *
 * [screenRect] est l'ouverture intérieure du cadre. Le conteneur y inscrit la
 * surface native sans l'étirer. Les rectangles de [controls] donnent les
 * dimensions visuelles par défaut ; un profil personnalisé peut en déplacer
 * le centre et en changer l'échelle sans désynchroniser l'image et la hitbox.
 */
data class SkinGeometry(
    val designAspectRatio: Float,
    val nativeScreenAspectRatio: Float,
    val screenRect: NormalizedRect,
    val controls: Map<ControlId, NormalizedRect>,
)

/** Géométries approuvées communes aux coques vectorielles et aux hitboxes. */
object RavenSkinGeometries {
    private const val DESIGN_ASPECT = 1000f / 2160f

    val gb: SkinGeometry = SkinGeometry(
        designAspectRatio = DESIGN_ASPECT,
        nativeScreenAspectRatio = 160f / 144f,
        screenRect = NormalizedRect(
            left = 0.07f,
            top = 0.055f,
            right = 0.93f,
            bottom = 0.41333f,
        ),
        controls = linkedMapOf(
            ControlId.DPAD to square(centerX = 0.215f, centerY = 0.665f, width = 0.31f),
            ControlId.BUTTON_A to square(centerX = 0.805f, centerY = 0.625f, width = 0.17f),
            ControlId.BUTTON_B to square(centerX = 0.650f, centerY = 0.700f, width = 0.17f),
            ControlId.SELECT to NormalizedRect(0.325f, 0.825f, 0.485f, 0.895f),
            ControlId.START to NormalizedRect(0.515f, 0.825f, 0.675f, 0.895f),
            ControlId.MENU to NormalizedRect(0.415f, 0.445f, 0.585f, 0.545f),
            ControlId.BUTTON_L to NormalizedRect(0.055f, 0.450f, 0.295f, 0.505f),
            ControlId.BUTTON_R to NormalizedRect(0.705f, 0.450f, 0.945f, 0.505f),
        ),
    )

    val gba: SkinGeometry = SkinGeometry(
        designAspectRatio = DESIGN_ASPECT,
        nativeScreenAspectRatio = 240f / 160f,
        screenRect = NormalizedRect(
            left = 0.07f,
            top = 0.055f,
            right = 0.93f,
            bottom = 0.32043f,
        ),
        controls = linkedMapOf(
            ControlId.DPAD to square(centerX = 0.215f, centerY = 0.650f, width = 0.31f),
            ControlId.BUTTON_A to square(centerX = 0.805f, centerY = 0.610f, width = 0.17f),
            ControlId.BUTTON_B to square(centerX = 0.650f, centerY = 0.690f, width = 0.17f),
            ControlId.SELECT to NormalizedRect(0.325f, 0.825f, 0.485f, 0.895f),
            ControlId.START to NormalizedRect(0.515f, 0.825f, 0.675f, 0.895f),
            ControlId.MENU to NormalizedRect(0.415f, 0.375f, 0.585f, 0.475f),
            ControlId.BUTTON_L to NormalizedRect(0.055f, 0.390f, 0.295f, 0.460f),
            ControlId.BUTTON_R to NormalizedRect(0.705f, 0.390f, 0.945f, 0.460f),
        ),
    )

    fun forSkin(skin: TouchSkin): SkinGeometry? = when (skin) {
        TouchSkin.CLASSIC -> null
        TouchSkin.RAVEN_GB -> gb
        TouchSkin.RAVEN_GBA -> gba
    }

    private fun square(centerX: Float, centerY: Float, width: Float): NormalizedRect {
        val halfWidth = width * 0.5f
        val halfHeight = width * DESIGN_ASPECT * 0.5f
        return NormalizedRect(
            left = centerX - halfWidth,
            top = centerY - halfHeight,
            right = centerX + halfWidth,
            bottom = centerY + halfHeight,
        )
    }
}

/** Rectangle entier sans dépendance Android, testable sur la JVM. */
data class SkinPixelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class SkinLayoutResult(
    val skinRect: SkinPixelRect,
    val surfaceRect: SkinPixelRect,
)

/**
 * Calcule la coque et la surface native sans déformation.
 *
 * Le canevas de design conserve son ratio sur les téléphones courts, longs et
 * les tablettes. La surface est ensuite ajustée à l'intérieur de l'ouverture,
 * centrée et jamais recouverte par le bezel.
 */
object SkinLayoutCalculator {
    fun calculate(
        containerWidth: Int,
        containerHeight: Int,
        topInset: Int,
        geometry: SkinGeometry,
    ): SkinLayoutResult {
        if (containerWidth <= 0 || containerHeight <= 0) {
            val empty = SkinPixelRect(0, 0, 0, 0)
            return SkinLayoutResult(empty, empty)
        }

        val safeTop = topInset.coerceIn(0, containerHeight)
        val availableHeight = (containerHeight - safeTop).coerceAtLeast(1)
        val widthFromHeight = (availableHeight * geometry.designAspectRatio).roundToInt()
        val skinWidth: Int
        val skinHeight: Int
        if (widthFromHeight <= containerWidth) {
            skinWidth = widthFromHeight.coerceAtLeast(1)
            skinHeight = availableHeight
        } else {
            skinWidth = containerWidth
            skinHeight = (containerWidth / geometry.designAspectRatio).roundToInt()
                .coerceAtLeast(1)
        }

        val skinLeft = (containerWidth - skinWidth) / 2
        val skinTop = safeTop + (availableHeight - skinHeight) / 2
        val skinRect = SkinPixelRect(
            left = skinLeft,
            top = skinTop,
            right = skinLeft + skinWidth,
            bottom = skinTop + skinHeight,
        )

        val normalized = geometry.screenRect
        val openingLeft = skinLeft + (normalized.left * skinWidth).roundToInt()
        val openingTop = skinTop + (normalized.top * skinHeight).roundToInt()
        val openingRight = skinLeft + (normalized.right * skinWidth).roundToInt()
        val openingBottom = skinTop + (normalized.bottom * skinHeight).roundToInt()
        val openingWidth = (openingRight - openingLeft).coerceAtLeast(1)
        val openingHeight = (openingBottom - openingTop).coerceAtLeast(1)
        val openingAspect = openingWidth.toFloat() / openingHeight

        val surfaceWidth: Int
        val surfaceHeight: Int
        if (openingAspect > geometry.nativeScreenAspectRatio) {
            surfaceHeight = openingHeight
            surfaceWidth = (surfaceHeight * geometry.nativeScreenAspectRatio).roundToInt()
                .coerceAtLeast(1)
        } else {
            surfaceWidth = openingWidth
            surfaceHeight = (surfaceWidth / geometry.nativeScreenAspectRatio).roundToInt()
                .coerceAtLeast(1)
        }
        val surfaceLeft = openingLeft + (openingWidth - surfaceWidth) / 2
        val surfaceTop = openingTop + (openingHeight - surfaceHeight) / 2
        val surfaceRect = SkinPixelRect(
            left = surfaceLeft,
            top = surfaceTop,
            right = surfaceLeft + surfaceWidth,
            bottom = surfaceTop + surfaceHeight,
        )

        return SkinLayoutResult(skinRect, surfaceRect)
    }
}
