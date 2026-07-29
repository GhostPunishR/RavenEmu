package com.ravenemu.input

import com.ravenemu.emulation.api.EmulatorButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchInputStateTest {

    @Test
    fun `multitouch A plus B reste independant`() {
        val changes = mutableListOf<Pair<EmulatorButton, Boolean>>()
        val state = TouchInputState(
            onButtonChanged = { button, pressed -> changes += button to pressed },
            onMenuVisualChanged = {},
        )

        state.updatePointer(4, ControlHitTester.buttonMask(EmulatorButton.A))
        state.updatePointer(9, ControlHitTester.buttonMask(EmulatorButton.B))

        assertTrue(state.isPressed(EmulatorButton.A))
        assertTrue(state.isPressed(EmulatorButton.B))
        assertEquals(
            listOf(EmulatorButton.A to true, EmulatorButton.B to true),
            changes,
        )

        state.releasePointer(4)
        assertFalse(state.isPressed(EmulatorButton.A))
        assertTrue(state.isPressed(EmulatorButton.B))
        state.releasePointer(9)
        assertEquals(
            listOf(
                EmulatorButton.A to true,
                EmulatorButton.B to true,
                EmulatorButton.A to false,
                EmulatorButton.B to false,
            ),
            changes,
        )
    }

    @Test
    fun `un second pointeur empeche un relachement premature`() {
        val changes = mutableListOf<Pair<EmulatorButton, Boolean>>()
        val state = TouchInputState(
            onButtonChanged = { button, pressed -> changes += button to pressed },
            onMenuVisualChanged = {},
        )
        val a = ControlHitTester.buttonMask(EmulatorButton.A)

        state.updatePointer(1, a)
        state.updatePointer(2, a)
        state.releasePointer(1)

        assertTrue(state.isPressed(EmulatorButton.A))
        assertEquals(listOf(EmulatorButton.A to true), changes)
        state.releasePointer(2)
        assertEquals(
            listOf(EmulatorButton.A to true, EmulatorButton.A to false),
            changes,
        )
    }

    @Test
    fun `entree logique est envoyee dans updatePointer`() {
        var updateReturned = false
        var callbackObservedBeforeReturn = false
        val state = TouchInputState(
            onButtonChanged = { button, pressed ->
                if (button == EmulatorButton.START && pressed) {
                    callbackObservedBeforeReturn = !updateReturned
                }
            },
            onMenuVisualChanged = {},
        )

        state.updatePointer(3, ControlHitTester.buttonMask(EmulatorButton.START))
        updateReturned = true

        assertTrue(callbackObservedBeforeReturn)
    }

    @Test
    fun `MENU anime la vue sans devenir un bouton du moteur`() {
        val buttonChanges = mutableListOf<Pair<EmulatorButton, Boolean>>()
        val menuChanges = mutableListOf<Boolean>()
        val state = TouchInputState(
            onButtonChanged = { button, pressed -> buttonChanges += button to pressed },
            onMenuVisualChanged = menuChanges::add,
        )

        state.updatePointer(7, ControlHitTester.MENU_MASK)
        assertTrue(state.isMenuPressed())
        assertTrue(buttonChanges.isEmpty())
        assertEquals(listOf(true), menuChanges)

        state.releasePointer(7)
        assertFalse(state.isMenuPressed())
        assertEquals(listOf(true, false), menuChanges)
    }

    @Test
    fun `diagonale du D-pad produit deux boutons`() {
        val layout = ControlLayout.ravenGbPortrait()
        val dpad = layout.element(ControlId.DPAD)!!
        val width = 1_000
        val height = 2_000
        val density = 1f
        val reach = ControlGeometry.radiusPx(dpad, density) * dpad.touchScale
        val hitMask = ControlHitTester.hitMask(
            layout = layout,
            x = ControlGeometry.centerX(dpad, width) + reach * 0.62f,
            y = ControlGeometry.centerY(dpad, height) - reach * 0.62f,
            width = width,
            height = height,
            density = density,
        )

        assertTrue(hitMask and ControlHitTester.buttonMask(EmulatorButton.UP) != 0)
        assertTrue(hitMask and ControlHitTester.buttonMask(EmulatorButton.RIGHT) != 0)
        assertFalse(hitMask and ControlHitTester.buttonMask(EmulatorButton.DOWN) != 0)
        assertFalse(hitMask and ControlHitTester.buttonMask(EmulatorButton.LEFT) != 0)
    }
}
