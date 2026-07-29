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

    fun centerX(element: ControlElement, width: Int): Float = element.centerX * width

    fun centerY(element: ControlElement, height: Int): Float = element.centerY * height
}
