package com.ravenemu.android.player

import android.view.KeyEvent
import com.ravenemu.shared.player.GbButton

/**
 * Physical controllers (Bluetooth or USB) and keyboards: Android key codes and
 * axes to console buttons. Same default mapping as RavenEmu 0.8.1 (D-pad, A/B,
 * Start/Select, L/R shoulders); stick and hat both drive the D-pad.
 *
 * Takes plain values rather than Android events so it runs in unit tests.
 * One instance per player screen; call from the UI thread.
 */
internal class GamepadMapper {
    private var axisX = 0
    private var axisY = 0
    private val heldKeys = mutableSetOf<Int>()

    private fun pressedButtons(): Set<GbButton> = buildSet {
        heldKeys.mapNotNullTo(this) { KEY_MAP[it] }
        if (axisX < 0) add(GbButton.LEFT)
        if (axisX > 0) add(GbButton.RIGHT)
        if (axisY < 0) add(GbButton.UP)
        if (axisY > 0) add(GbButton.DOWN)
    }

    /** True when [keyCode] is a console button, whatever the event does. */
    fun handles(keyCode: Int): Boolean = keyCode in KEY_MAP

    /**
     * The button and its new state, or null when the key is not mapped or
     * the event is a key-repeat (the button is already held).
     */
    fun key(keyCode: Int, action: Int, repeatCount: Int): Pair<GbButton, Boolean>? {
        val button = KEY_MAP[keyCode] ?: return null
        val before = button in pressedButtons()
        when {
            action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> heldKeys.add(keyCode)
            action == KeyEvent.ACTION_UP -> heldKeys.remove(keyCode)
            else -> return null
        }
        val after = button in pressedButtons()
        return if (before != after) button to after else null
    }

    /**
     * D-pad changes from one joystick motion event. The hat (digital pad
     * reported as axes) wins over the analog stick when both move.
     */
    fun axes(hatX: Float, hatY: Float, stickX: Float, stickY: Float): List<Pair<GbButton, Boolean>> {
        val before = pressedButtons()
        axisX = direction(hatX, stickX)
        axisY = direction(hatY, stickY)
        val after = pressedButtons()
        // Release old directions before pressing new ones; keys and axes
        // are sources of the same logical button, never competing owners.
        return (before - after).map { it to false } +
            (after - before).map { it to true }
    }

    /** Forget the axis state, after the session released every button. */
    fun reset() {
        axisX = 0
        axisY = 0
        heldKeys.clear()
    }

    private fun direction(hat: Float, stick: Float): Int {
        val value = if (hat != 0f) hat else stick
        return when {
            value < -AXIS_THRESHOLD -> -1
            value > AXIS_THRESHOLD -> 1
            else -> 0
        }
    }

    companion object {
        private const val AXIS_THRESHOLD = 0.5f

        private val KEY_MAP: Map<Int, GbButton> = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to GbButton.UP,
            KeyEvent.KEYCODE_DPAD_DOWN to GbButton.DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to GbButton.LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to GbButton.RIGHT,
            KeyEvent.KEYCODE_BUTTON_A to GbButton.A,
            KeyEvent.KEYCODE_BUTTON_B to GbButton.B,
            KeyEvent.KEYCODE_BUTTON_START to GbButton.START,
            KeyEvent.KEYCODE_BUTTON_SELECT to GbButton.SELECT,
            // Shoulders and triggers both reach L/R (GBA); a GB game ignores them.
            KeyEvent.KEYCODE_BUTTON_L1 to GbButton.L,
            KeyEvent.KEYCODE_BUTTON_R1 to GbButton.R,
            KeyEvent.KEYCODE_BUTTON_L2 to GbButton.L,
            KeyEvent.KEYCODE_BUTTON_R2 to GbButton.R,
        )
    }
}
