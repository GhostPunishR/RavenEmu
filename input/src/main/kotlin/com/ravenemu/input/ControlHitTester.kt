package com.ravenemu.input

import com.ravenemu.emulation.api.EmulatorButton
import kotlin.math.abs

/**
 * Hit-testing sans allocation pour les commandes tactiles.
 *
 * Les boutons logiques occupent les bits correspondant à leur ordinal. Le bit
 * [MENU_MASK] est visuel et applicatif : il n'est jamais envoyé au moteur.
 */
internal object ControlHitTester {
    const val MENU_MASK: Int = 1 shl 30

    fun buttonMask(button: EmulatorButton): Int = 1 shl button.ordinal

    fun buttonsMask(hitMask: Int): Int = hitMask and MENU_MASK.inv()

    fun hasMenu(hitMask: Int): Boolean = hitMask and MENU_MASK != 0

    fun hitMask(
        layout: ControlLayout,
        x: Float,
        y: Float,
        width: Int,
        height: Int,
        density: Float,
        skin: TouchSkin = TouchSkin.CLASSIC,
    ): Int {
        var result = 0
        for (index in layout.elements.indices) {
            val element = layout.elements[index]
            if (!element.visible) continue
            val cx = ControlGeometry.centerX(element, width)
            val cy = ControlGeometry.centerY(element, height)
            val dx = x - cx
            val dy = y - cy
            val halfWidth = (
                ControlGeometry.widthPx(element, skin, width, density) *
                    0.5f * element.touchScale
                ).coerceAtLeast(1f)
            val halfHeight = (
                ControlGeometry.heightPx(element, skin, height, density) *
                    0.5f * element.touchScale
                ).coerceAtLeast(1f)
            val normalizedX = dx / halfWidth
            val normalizedY = dy / halfHeight
            when (element.id) {
                ControlId.DPAD -> {
                    if (abs(normalizedX) > 1f || abs(normalizedY) > 1f) continue
                    if (normalizedX < -DPAD_DEAD_ZONE) {
                        result = result or buttonMask(EmulatorButton.LEFT)
                    }
                    if (normalizedX > DPAD_DEAD_ZONE) {
                        result = result or buttonMask(EmulatorButton.RIGHT)
                    }
                    if (normalizedY < -DPAD_DEAD_ZONE) {
                        result = result or buttonMask(EmulatorButton.UP)
                    }
                    if (normalizedY > DPAD_DEAD_ZONE) {
                        result = result or buttonMask(EmulatorButton.DOWN)
                    }
                }
                ControlId.BUTTON_A -> {
                    if (insideEllipse(normalizedX, normalizedY)) {
                        result = result or buttonMask(EmulatorButton.A)
                    }
                }
                ControlId.BUTTON_B -> {
                    if (insideEllipse(normalizedX, normalizedY)) {
                        result = result or buttonMask(EmulatorButton.B)
                    }
                }
                ControlId.START -> {
                    if (insideBounds(normalizedX, normalizedY)) {
                        result = result or buttonMask(EmulatorButton.START)
                    }
                }
                ControlId.SELECT -> {
                    if (insideBounds(normalizedX, normalizedY)) {
                        result = result or buttonMask(EmulatorButton.SELECT)
                    }
                }
                ControlId.BUTTON_L -> {
                    if (insideBounds(normalizedX, normalizedY)) {
                        result = result or buttonMask(EmulatorButton.L)
                    }
                }
                ControlId.BUTTON_R -> {
                    if (insideBounds(normalizedX, normalizedY)) {
                        result = result or buttonMask(EmulatorButton.R)
                    }
                }
                ControlId.MENU -> {
                    if (insideBounds(normalizedX, normalizedY)) result = result or MENU_MASK
                }
            }
        }
        return result
    }

    private fun insideEllipse(normalizedX: Float, normalizedY: Float): Boolean =
        normalizedX * normalizedX + normalizedY * normalizedY <= 1f

    private fun insideBounds(normalizedX: Float, normalizedY: Float): Boolean =
        abs(normalizedX) <= 1f && abs(normalizedY) <= 1f

    private const val DPAD_DEAD_ZONE = 0.18f
}
