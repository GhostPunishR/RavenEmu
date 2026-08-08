package com.ravenemu.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.ravenemu.emulation.api.EmulatorButton

/**
 * Manettes physiques Android : traduit touches et axes en boutons logiques.
 * Le remappage utilisateur se fait en remplaçant [keyMap].
 */
class GamepadMapper {

    /** Table touche Android → bouton console, modifiable pour le remappage. */
    var keyMap: Map<Int, EmulatorButton> = defaultKeyMap()

    private var hatX = 0
    private var hatY = 0

    /**
     * Traite un événement clavier/manette. Retourne le bouton concerné et
     * son nouvel état, ou `null` si la touche n'est pas mappée.
     */
    fun mapKeyEvent(event: KeyEvent): Pair<EmulatorButton, Boolean>? {
        val button = keyMap[event.keyCode] ?: return null
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> button to true
            KeyEvent.ACTION_UP -> button to false
            else -> null
        }
    }

    /**
     * Traite les axes (croix numérique HAT et stick gauche) d'un événement
     * de mouvement manette. Retourne les changements d'état à appliquer.
     */
    fun mapMotionEvent(event: MotionEvent): List<Pair<EmulatorButton, Boolean>> {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) {
            return emptyList()
        }
        val changes = mutableListOf<Pair<EmulatorButton, Boolean>>()
        val x = axisDirection(
            event.getAxisValue(MotionEvent.AXIS_HAT_X),
            event.getAxisValue(MotionEvent.AXIS_X),
        )
        val y = axisDirection(
            event.getAxisValue(MotionEvent.AXIS_HAT_Y),
            event.getAxisValue(MotionEvent.AXIS_Y),
        )
        if (x != hatX) {
            if (hatX < 0) changes += EmulatorButton.LEFT to false
            if (hatX > 0) changes += EmulatorButton.RIGHT to false
            if (x < 0) changes += EmulatorButton.LEFT to true
            if (x > 0) changes += EmulatorButton.RIGHT to true
            hatX = x
        }
        if (y != hatY) {
            if (hatY < 0) changes += EmulatorButton.UP to false
            if (hatY > 0) changes += EmulatorButton.DOWN to false
            if (y < 0) changes += EmulatorButton.UP to true
            if (y > 0) changes += EmulatorButton.DOWN to true
            hatY = y
        }
        return changes
    }

    private fun axisDirection(hat: Float, stick: Float): Int {
        val value = if (hat != 0f) hat else stick
        return when {
            value < -AXIS_THRESHOLD -> -1
            value > AXIS_THRESHOLD -> 1
            else -> 0
        }
    }

    companion object {
        private const val AXIS_THRESHOLD = 0.5f

        fun defaultKeyMap(): Map<Int, EmulatorButton> = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to EmulatorButton.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to EmulatorButton.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to EmulatorButton.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to EmulatorButton.RIGHT,
            KeyEvent.KEYCODE_BUTTON_A to EmulatorButton.A,
            KeyEvent.KEYCODE_BUTTON_B to EmulatorButton.B,
            KeyEvent.KEYCODE_BUTTON_START to EmulatorButton.START,
            KeyEvent.KEYCODE_BUTTON_SELECT to EmulatorButton.SELECT,
            // Gâchettes d'épaule (Game Boy Advance) ; ignorées par la Game Boy.
            KeyEvent.KEYCODE_BUTTON_L1 to EmulatorButton.L,
            KeyEvent.KEYCODE_BUTTON_R1 to EmulatorButton.R,
        )
    }
}
