package com.ravenemu.input

/**
 * Géométrie partagée par le rendu, le hit-testing et l'éditeur.
 *
 * Les positions restent celles de [ControlElement], relatives à la vue. Les
 * dimensions physiques sont exprimées en dp pour garder la même lisibilité
 * sur toutes les densités Android.
 */
internal object ControlGeometry {
    fun baseRadiusDp(id: ControlId): Float = when (id) {
        ControlId.DPAD -> 76f
        ControlId.BUTTON_A, ControlId.BUTTON_B -> 34f
        ControlId.START, ControlId.SELECT -> 26f
        ControlId.BUTTON_L, ControlId.BUTTON_R -> 28f
        ControlId.MENU -> 28f
    }

    fun radiusPx(element: ControlElement, density: Float): Float =
        baseRadiusDp(element.id) * density * element.scale

    fun widthPx(
        element: ControlElement,
        skin: TouchSkin,
        viewportWidth: Int,
        density: Float,
    ): Float {
        val geometry = RavenSkinGeometries.forSkin(skin)
        if (geometry != null) {
            return geometry.controls.getValue(element.id).width * viewportWidth * element.scale
        }
        val radius = radiusPx(element, density)
        return when (element.id) {
            ControlId.DPAD, ControlId.BUTTON_A, ControlId.BUTTON_B -> radius * 2f
            ControlId.MENU -> radius * 3.56f
            else -> radius * 3.2f
        }
    }

    fun heightPx(
        element: ControlElement,
        skin: TouchSkin,
        viewportHeight: Int,
        density: Float,
    ): Float {
        val geometry = RavenSkinGeometries.forSkin(skin)
        if (geometry != null) {
            return geometry.controls.getValue(element.id).height * viewportHeight * element.scale
        }
        val radius = radiusPx(element, density)
        return when (element.id) {
            ControlId.DPAD, ControlId.BUTTON_A, ControlId.BUTTON_B -> radius * 2f
            else -> radius * 1.5f
        }
    }

    fun centerX(element: ControlElement, width: Int): Float = element.centerX * width

    fun centerY(element: ControlElement, height: Int): Float = element.centerY * height
}
