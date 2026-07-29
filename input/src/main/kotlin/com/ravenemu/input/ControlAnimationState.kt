package com.ravenemu.input

import com.ravenemu.emulation.api.EmulatorButton
import kotlin.math.max

/**
 * Interpolation visuelle des commandes, sans `Animator`.
 *
 * Les cibles changent immédiatement avec l'entrée logique. [advance] ne fait
 * qu'approcher ces cibles au rythme des frames et ne crée aucun objet.
 */
internal class ControlAnimationState(
    private val pressDurationNanos: Long = PRESS_DURATION_NANOS,
    private val releaseDurationNanos: Long = RELEASE_DURATION_NANOS,
) {
    private val progress = FloatArray(SLOT_COUNT)
    private val targetPressed = BooleanArray(SLOT_COUNT)
    private var previousFrameNanos = UNSET_TIME

    fun setButtonPressed(button: EmulatorButton, pressed: Boolean): Boolean =
        setSlot(buttonSlot(button), pressed)

    fun setMenuPressed(pressed: Boolean): Boolean =
        setSlot(ControlId.MENU.ordinal, pressed)

    fun controlProgress(id: ControlId): Float = when (id) {
        ControlId.DPAD -> max(
            max(dpadProgress(EmulatorButton.UP), dpadProgress(EmulatorButton.DOWN)),
            max(dpadProgress(EmulatorButton.LEFT), dpadProgress(EmulatorButton.RIGHT)),
        )
        else -> progress[id.ordinal]
    }

    fun dpadProgress(direction: EmulatorButton): Float =
        progress[directionSlot(direction)]

    fun isTargetPressed(id: ControlId): Boolean = when (id) {
        ControlId.DPAD ->
            targetPressed[directionSlot(EmulatorButton.UP)] ||
                targetPressed[directionSlot(EmulatorButton.DOWN)] ||
                targetPressed[directionSlot(EmulatorButton.LEFT)] ||
                targetPressed[directionSlot(EmulatorButton.RIGHT)]
        else -> targetPressed[id.ordinal]
    }

    /**
     * Avance l'interpolation et renvoie `true` tant qu'une nouvelle frame est
     * nécessaire.
     */
    fun advance(frameTimeNanos: Long): Boolean {
        if (previousFrameNanos == UNSET_TIME) {
            previousFrameNanos = frameTimeNanos
            return hasPendingAnimation()
        }
        val elapsed = (frameTimeNanos - previousFrameNanos).coerceAtLeast(0L)
        previousFrameNanos = frameTimeNanos
        var pending = false
        for (slot in progress.indices) {
            val target = if (targetPressed[slot]) 1f else 0f
            val current = progress[slot]
            if (current == target) continue
            val duration = if (targetPressed[slot]) pressDurationNanos else releaseDurationNanos
            val step = if (duration <= 0L) 1f else elapsed.toFloat() / duration.toFloat()
            progress[slot] = if (target > current) {
                (current + step).coerceAtMost(target)
            } else {
                (current - step).coerceAtLeast(target)
            }
            if (progress[slot] != target) pending = true
        }
        return pending
    }

    fun reset() {
        progress.fill(0f)
        targetPressed.fill(false)
        previousFrameNanos = UNSET_TIME
    }

    private fun setSlot(slot: Int, pressed: Boolean): Boolean {
        if (targetPressed[slot] == pressed) return false
        targetPressed[slot] = pressed
        previousFrameNanos = UNSET_TIME
        return true
    }

    private fun hasPendingAnimation(): Boolean {
        for (slot in progress.indices) {
            val target = if (targetPressed[slot]) 1f else 0f
            if (progress[slot] != target) return true
        }
        return false
    }

    private fun buttonSlot(button: EmulatorButton): Int = when (button) {
        EmulatorButton.UP,
        EmulatorButton.DOWN,
        EmulatorButton.LEFT,
        EmulatorButton.RIGHT,
        -> directionSlot(button)
        EmulatorButton.A -> ControlId.BUTTON_A.ordinal
        EmulatorButton.B -> ControlId.BUTTON_B.ordinal
        EmulatorButton.START -> ControlId.START.ordinal
        EmulatorButton.SELECT -> ControlId.SELECT.ordinal
        EmulatorButton.L -> ControlId.BUTTON_L.ordinal
        EmulatorButton.R -> ControlId.BUTTON_R.ordinal
    }

    private fun directionSlot(direction: EmulatorButton): Int = when (direction) {
        EmulatorButton.UP -> DPAD_UP_SLOT
        EmulatorButton.DOWN -> DPAD_DOWN_SLOT
        EmulatorButton.LEFT -> DPAD_LEFT_SLOT
        EmulatorButton.RIGHT -> DPAD_RIGHT_SLOT
        else -> error("$direction n'est pas une direction du D-pad")
    }

    private companion object {
        val DPAD_UP_SLOT = ControlId.entries.size
        val DPAD_DOWN_SLOT = DPAD_UP_SLOT + 1
        val DPAD_LEFT_SLOT = DPAD_UP_SLOT + 2
        val DPAD_RIGHT_SLOT = DPAD_UP_SLOT + 3
        val SLOT_COUNT = DPAD_UP_SLOT + 4
        const val UNSET_TIME = Long.MIN_VALUE
        const val PRESS_DURATION_NANOS = 70_000_000L
        const val RELEASE_DURATION_NANOS = 100_000_000L
    }
}
