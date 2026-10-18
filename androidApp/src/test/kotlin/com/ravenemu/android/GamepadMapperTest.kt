package com.ravenemu.android

import android.view.KeyEvent
import com.ravenemu.shared.GbButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadMapperTest {
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP

    @Test
    fun faceButtonsPressAndRelease() {
        val mapper = GamepadMapper()
        assertEquals(GbButton.A to true, mapper.key(KeyEvent.KEYCODE_BUTTON_A, down, 0))
        assertEquals(GbButton.A to false, mapper.key(KeyEvent.KEYCODE_BUTTON_A, up, 0))
        assertEquals(GbButton.START to true, mapper.key(KeyEvent.KEYCODE_BUTTON_START, down, 0))
        assertEquals(GbButton.R to true, mapper.key(KeyEvent.KEYCODE_BUTTON_R2, down, 0))
    }

    @Test
    fun keyRepeatDoesNotPressAgain() {
        assertNull(GamepadMapper().key(KeyEvent.KEYCODE_BUTTON_B, down, 3))
    }

    @Test
    fun otherKeysAreLeftToTheSystem() {
        val mapper = GamepadMapper()
        assertFalse(mapper.handles(KeyEvent.KEYCODE_BACK))
        assertFalse(mapper.handles(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(mapper.key(KeyEvent.KEYCODE_VOLUME_UP, down, 0))
        assertTrue(mapper.handles(KeyEvent.KEYCODE_DPAD_LEFT))
    }

    @Test
    fun stickMovesTheDpadAndReleasesOnReturn() {
        val mapper = GamepadMapper()
        assertEquals(listOf(GbButton.LEFT to true), mapper.axes(0f, 0f, -0.9f, 0.1f))
        // Moving to the opposite side releases the old direction first.
        assertEquals(listOf(GbButton.LEFT to false, GbButton.RIGHT to true),
            mapper.axes(0f, 0f, 0.9f, 0f))
        assertEquals(listOf(GbButton.RIGHT to false), mapper.axes(0f, 0f, 0.2f, 0f))
        assertEquals(emptyList<Pair<GbButton, Boolean>>(), mapper.axes(0f, 0f, 0f, 0f))
    }

    @Test
    fun hatWinsOverTheStickAndSupportsDiagonals() {
        val mapper = GamepadMapper()
        assertEquals(listOf(GbButton.RIGHT to true, GbButton.UP to true),
            mapper.axes(1f, -1f, -0.9f, 0.9f))
    }

    @Test
    fun shoulderAliasesStayPressedUntilTheirLastSourceIsReleased() {
        val mapper = GamepadMapper()
        assertEquals(GbButton.L to true, mapper.key(KeyEvent.KEYCODE_BUTTON_L1, down, 0))
        assertNull(mapper.key(KeyEvent.KEYCODE_BUTTON_L2, down, 0))
        assertNull(mapper.key(KeyEvent.KEYCODE_BUTTON_L1, up, 0))
        assertEquals(GbButton.L to false, mapper.key(KeyEvent.KEYCODE_BUTTON_L2, up, 0))
        assertNull(mapper.key(KeyEvent.KEYCODE_BUTTON_L2, up, 0))
    }

    @Test
    fun dpadKeyAndStickDoNotReleaseEachOther() {
        val mapper = GamepadMapper()
        assertEquals(GbButton.LEFT to true, mapper.key(KeyEvent.KEYCODE_DPAD_LEFT, down, 0))
        assertEquals(emptyList<Pair<GbButton, Boolean>>(), mapper.axes(0f, 0f, -1f, 0f))
        assertNull(mapper.key(KeyEvent.KEYCODE_DPAD_LEFT, up, 0))
        assertEquals(listOf(GbButton.LEFT to false), mapper.axes(0f, 0f, 0f, 0f))

        assertEquals(listOf(GbButton.RIGHT to true), mapper.axes(0f, 0f, 1f, 0f))
        assertNull(mapper.key(KeyEvent.KEYCODE_DPAD_RIGHT, down, 0))
        assertEquals(emptyList<Pair<GbButton, Boolean>>(), mapper.axes(0f, 0f, 0f, 0f))
        assertEquals(GbButton.RIGHT to false, mapper.key(KeyEvent.KEYCODE_DPAD_RIGHT, up, 0))
    }

    @Test
    fun resetForgetsAllSourcesAfterTheSessionReleasesItsButtons() {
        val mapper = GamepadMapper()
        mapper.key(KeyEvent.KEYCODE_BUTTON_L1, down, 0)
        mapper.axes(0f, 0f, -1f, 0f)
        mapper.reset()
        assertNull(mapper.key(KeyEvent.KEYCODE_BUTTON_L1, up, 0))
        assertEquals(GbButton.L to true, mapper.key(KeyEvent.KEYCODE_BUTTON_L1, down, 0))
        assertEquals(listOf(GbButton.LEFT to true), mapper.axes(0f, 0f, -1f, 0f))
    }
}
