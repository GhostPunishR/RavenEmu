package com.ravenemu.input

import com.ravenemu.emulation.api.EmulatorButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlAnimationStateTest {

    @Test
    fun `appui et relachement convergent sans Animator`() {
        val state = ControlAnimationState(
            pressDurationNanos = 70_000_000L,
            releaseDurationNanos = 100_000_000L,
        )

        assertTrue(state.setButtonPressed(EmulatorButton.A, true))
        assertTrue(state.isTargetPressed(ControlId.BUTTON_A))
        assertTrue(state.advance(1L))
        assertEquals(0f, state.controlProgress(ControlId.BUTTON_A))
        assertFalse(state.advance(70_000_001L))
        assertEquals(1f, state.controlProgress(ControlId.BUTTON_A))

        assertTrue(state.setButtonPressed(EmulatorButton.A, false))
        assertFalse(state.isTargetPressed(ControlId.BUTTON_A))
        assertTrue(state.advance(80_000_000L))
        assertFalse(state.advance(180_000_000L))
        assertEquals(0f, state.controlProgress(ControlId.BUTTON_A))
    }

    @Test
    fun `directions du D-pad ont des etats visuels independants`() {
        val state = ControlAnimationState(pressDurationNanos = 1L, releaseDurationNanos = 1L)

        state.setButtonPressed(EmulatorButton.UP, true)
        state.setButtonPressed(EmulatorButton.RIGHT, true)
        state.advance(1L)
        state.advance(2L)

        assertEquals(1f, state.dpadProgress(EmulatorButton.UP))
        assertEquals(1f, state.dpadProgress(EmulatorButton.RIGHT))
        assertEquals(0f, state.dpadProgress(EmulatorButton.DOWN))
        assertEquals(0f, state.dpadProgress(EmulatorButton.LEFT))
    }

    @Test
    fun `MENU possede le meme cycle presse relache`() {
        val state = ControlAnimationState(pressDurationNanos = 1L, releaseDurationNanos = 1L)

        state.setMenuPressed(true)
        state.advance(1L)
        state.advance(2L)
        assertEquals(1f, state.controlProgress(ControlId.MENU))

        state.setMenuPressed(false)
        state.advance(3L)
        state.advance(4L)
        assertEquals(0f, state.controlProgress(ControlId.MENU))
    }
}
