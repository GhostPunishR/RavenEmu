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
    ): Int {
        var result = 0
        for (index in layout.elements.indices) {
            val element = layout.elements[index]
            if (!element.visible) continue
            val cx = ControlGeometry.centerX(element, width)
            val cy = ControlGeometry.centerY(element, height)
            val reach = ControlGeometry.radiusPx(element, density) * element.touchScale
            val dx = x - cx
            val dy = y - cy
            when (element.id) {
                ControlId.DPAD -> {
                    if (abs(dx) > reach || abs(dy) > reach) continue
                    val dead = reach * DPAD_DEAD_ZONE
                    if (dx < -dead) result = result or buttonMask(EmulatorButton.LEFT)
                    if (dx > dead) result = result or buttonMask(EmulatorButton.RIGHT)
                    if (dy < -dead) result = result or buttonMask(EmulatorButton.UP)
                    if (dy > dead) result = result or buttonMask(EmulatorButton.DOWN)
                }
                ControlId.BUTTON_A -> {
                    if (insideCircle(dx, dy, reach)) {
                        result = result or buttonMask(EmulatorButton.A)
                    }
                }
                ControlId.BUTTON_B -> {
                    if (insideCircle(dx, dy, reach)) {
                        result = result or buttonMask(EmulatorButton.B)
                    }
                }
                ControlId.START -> {
                    if (insidePill(dx, dy, reach)) {
                        result = result or buttonMask(EmulatorButton.START)
                    }
                }
                ControlId.SELECT -> {
                    if (insidePill(dx, dy, reach)) {
                        result = result or buttonMask(EmulatorButton.SELECT)
                    }
                }
                ControlId.BUTTON_L -> {
                    if (insidePill(dx, dy, reach)) {
                        result = result or buttonMask(EmulatorButton.L)
                    }
                }
                ControlId.BUTTON_R -> {
                    if (insidePill(dx, dy, reach)) {
                        result = result or buttonMask(EmulatorButton.R)
                    }
                }
                ControlId.MENU -> {
                    if (insidePill(dx, dy, reach)) result = result or MENU_MASK
                }
            }
        }
        return result
    }

    private fun insideCircle(dx: Float, dy: Float, reach: Float): Boolean =
        dx * dx + dy * dy <= reach * reach

    private fun insidePill(dx: Float, dy: Float, reach: Float): Boolean =
        abs(dx) <= reach * PILL_HALF_WIDTH && abs(dy) <= reach * PILL_HALF_HEIGHT

    private const val DPAD_DEAD_ZONE = 0.18f
    private const val PILL_HALF_WIDTH = 1.6f
    private const val PILL_HALF_HEIGHT = 0.75f
}
