package com.ravenemu.input

import com.ravenemu.emulation.api.EmulatorButton

/**
 * Agrège les pointeurs en un état logique stable.
 *
 * Un bouton n'est relâché que lorsque le dernier pointeur qui le couvre part.
 * Le callback est appelé synchroniquement depuis [updatePointer],
 * [releasePointer] ou [releaseAll] : aucune animation ni file d'attente ne
 * s'interpose entre le geste et l'émulation.
 */
internal class TouchInputState(
    private val onButtonChanged: (EmulatorButton, Boolean) -> Unit,
    private val onMenuVisualChanged: (Boolean) -> Unit,
) {
    private val pointerHits = HashMap<Int, Int>(MAX_POINTERS)
    private val buttonPointerCounts = IntArray(EmulatorButton.entries.size)
    private var menuPointerCount = 0

    /**
     * Met à jour un pointeur et renvoie `true` si au moins une commande vient
     * d'être enfoncée, afin que la vue déclenche le retour haptique.
     */
    fun updatePointer(pointerId: Int, hitMask: Int): Boolean {
        val previous = pointerHits[pointerId] ?: 0
        if (previous == hitMask) return false

        var anyNewPress = false
        val changedButtons = ControlHitTester.buttonsMask(previous xor hitMask)
        for (index in EmulatorButton.entries.indices) {
            val button = EmulatorButton.entries[index]
            val bit = ControlHitTester.buttonMask(button)
            if (changedButtons and bit == 0) continue
            val wasPressed = buttonPointerCounts[button.ordinal] > 0
            if (hitMask and bit != 0) {
                buttonPointerCounts[button.ordinal]++
            } else {
                buttonPointerCounts[button.ordinal] =
                    (buttonPointerCounts[button.ordinal] - 1).coerceAtLeast(0)
            }
            val isPressed = buttonPointerCounts[button.ordinal] > 0
            if (wasPressed != isPressed) {
                onButtonChanged(button, isPressed)
                if (isPressed) anyNewPress = true
            }
        }

        val previousMenu = ControlHitTester.hasMenu(previous)
        val currentMenu = ControlHitTester.hasMenu(hitMask)
        if (previousMenu != currentMenu) {
            val wasPressed = menuPointerCount > 0
            menuPointerCount += if (currentMenu) 1 else -1
            menuPointerCount = menuPointerCount.coerceAtLeast(0)
            val isPressed = menuPointerCount > 0
            if (wasPressed != isPressed) {
                onMenuVisualChanged(isPressed)
                if (isPressed) anyNewPress = true
            }
        }

        if (hitMask == 0) pointerHits.remove(pointerId) else pointerHits[pointerId] = hitMask
        return anyNewPress
    }

    fun releasePointer(pointerId: Int) {
        if (pointerHits.containsKey(pointerId)) updatePointer(pointerId, 0)
    }

    fun releaseAll() {
        if (pointerHits.isEmpty()) return
        for (index in EmulatorButton.entries.indices) {
            val button = EmulatorButton.entries[index]
            if (buttonPointerCounts[button.ordinal] > 0) {
                buttonPointerCounts[button.ordinal] = 0
                onButtonChanged(button, false)
            }
        }
        if (menuPointerCount > 0) {
            menuPointerCount = 0
            onMenuVisualChanged(false)
        }
        pointerHits.clear()
    }

    fun isPressed(button: EmulatorButton): Boolean =
        buttonPointerCounts[button.ordinal] > 0

    fun isMenuPressed(): Boolean = menuPointerCount > 0

    private companion object {
        const val MAX_POINTERS = 5
    }
}
