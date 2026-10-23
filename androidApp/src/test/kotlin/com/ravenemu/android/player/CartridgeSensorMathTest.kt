package com.ravenemu.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class CartridgeSensorMathTest {
    private val g = 9.80665f

    @Test fun manualSunCoversTheWholeRange() {
        assertEquals(0, CartridgeSensorMath.manualLight(0))
        assertEquals(128, CartridgeSensorMath.manualLight(50))
        assertEquals(255, CartridgeSensorMath.manualLight(100))
        assertEquals(0, CartridgeSensorMath.manualLight(-10))
        assertEquals(255, CartridgeSensorMath.manualLight(150))
    }

    /** Indoor light gives nothing, an overcast sky half, the sun everything. */
    @Test fun lightFollowsDaylightOnALogScale() {
        assertEquals(0, CartridgeSensorMath.lightLevel(0f))
        assertEquals(0, CartridgeSensorMath.lightLevel(400f))
        assertEquals(0, CartridgeSensorMath.lightLevel(1_000f))
        assertEquals(128, CartridgeSensorMath.lightLevel(10_000f))
        assertEquals(255, CartridgeSensorMath.lightLevel(100_000f))
        assertEquals(255, CartridgeSensorMath.lightLevel(150_000f))
        assertEquals(0, CartridgeSensorMath.lightLevel(Float.NaN))
        assertTrue(CartridgeSensorMath.lightLevel(30_000f) > CartridgeSensorMath.lightLevel(5_000f))
    }

    /** Android turns counterclockwise for a positive z; the core, clockwise. */
    @Test fun rotationIsClockwisePositiveAndBounded() {
        assertEquals(0, CartridgeSensorMath.rotationSpeed(0f))
        assertEquals(32767, CartridgeSensorMath.rotationSpeed(-PI.toFloat()))
        assertEquals(-32768, CartridgeSensorMath.rotationSpeed(PI.toFloat()))
        assertEquals(16384, CartridgeSensorMath.rotationSpeed(-PI.toFloat() / 2))
        assertEquals(32767, CartridgeSensorMath.rotationSpeed(-20f))
        assertEquals(0, CartridgeSensorMath.rotationSpeed(Float.NaN))
    }

    @Test fun flatPhoneIsLevel() {
        assertEquals(0, CartridgeSensorMath.tiltX(0f, 0f, 0))
        assertEquals(0, CartridgeSensorMath.tiltY(0f, 0f, 0))
    }

    /**
     * Portrait: the right side going down reads x < 0 on Android, the top
     * edge going down (away from the player) reads y < 0; both are positive
     * for the core. Held upright, gravity gives the core's -1 g on y.
     */
    @Test fun portraitAxesMatchTheCore() {
        assertEquals(32767, CartridgeSensorMath.tiltX(-g, 0f, 0))
        assertEquals(16384, CartridgeSensorMath.tiltX(-g / 2, 0f, 0))
        assertEquals(32767, CartridgeSensorMath.tiltY(0f, -g, 0))
        assertEquals(-32768, CartridgeSensorMath.tiltY(0f, g, 0))
    }

    /**
     * Landscape, top of the phone on the left (ROTATION_90): the right side
     * of the screen is the phone's bottom edge, so it going down reads y > 0;
     * the top of the screen is the phone's right side (x).
     */
    @Test fun axesFollowTheDisplayedScreen() {
        assertEquals(32767, CartridgeSensorMath.tiltX(0f, g, 1))
        assertEquals(0, CartridgeSensorMath.tiltY(0f, g, 1))
        assertEquals(32767, CartridgeSensorMath.tiltY(-g, 0f, 1))
        // Upside down (ROTATION_180): both axes reversed.
        assertEquals(32767, CartridgeSensorMath.tiltX(g, 0f, 2))
        assertEquals(32767, CartridgeSensorMath.tiltY(0f, g, 2))
        // Top of the phone on the right (ROTATION_270): the top of the
        // screen is the phone's left side, so it going down reads x > 0.
        assertEquals(32767, CartridgeSensorMath.tiltX(0f, -g, 3))
        assertEquals(32767, CartridgeSensorMath.tiltY(g, 0f, 3))
    }

    @Test fun tiltIsBoundedAndIgnoresNaN() {
        assertEquals(32767, CartridgeSensorMath.tiltX(-3 * g, 0f, 0))
        assertEquals(-32768, CartridgeSensorMath.tiltX(3 * g, 0f, 0))
        assertEquals(0, CartridgeSensorMath.tiltX(Float.NaN, 0f, 0))
    }
}
