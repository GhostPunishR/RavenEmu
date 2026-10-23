package com.ravenemu.shared.player

import kotlin.math.abs
import kotlin.math.min

/**
 * Matches the buttons in RavenCore sdk/include/ravenemu/bridge.h: the eight
 * Game Boy buttons, then the GBA shoulder buttons L and R.
 */
enum class GbButton(val nativeCode: Int) {
    UP(0), DOWN(1), LEFT(2), RIGHT(3),
    A(4), B(5), START(6), SELECT(7),
    L(8), R(9),
}

/** RavenEmu 0.8.1-style central dead zone, sliding and diagonal support. */
object GbDpadHitTest {
    fun buttonsAt(x: Float, y: Float, width: Float, height: Float): Set<GbButton> {
        if (width <= 0f || height <= 0f) return emptySet()
        val cx = width / 2f
        val cy = height / 2f
        val reach = min(width, height) * .49f
        val dx = x - cx
        val dy = y - cy
        if (abs(dx) > reach || abs(dy) > reach) return emptySet()
        val dead = reach * .18f
        return buildSet {
            if (dx < -dead) add(GbButton.LEFT)
            if (dx > dead) add(GbButton.RIGHT)
            if (dy < -dead) add(GbButton.UP)
            if (dy > dead) add(GbButton.DOWN)
        }
    }
}
