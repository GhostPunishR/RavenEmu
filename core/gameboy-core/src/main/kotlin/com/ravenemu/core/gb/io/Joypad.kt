package com.ravenemu.core.gb.io

import com.ravenemu.core.gb.Interrupt
import com.ravenemu.core.gb.InterruptController
import com.ravenemu.emulation.api.EmulatorButton

/**
 * Registre P1/JOYP (0xFF00). Les bits 4 et 5 sélectionnent le groupe lu
 * (directions ou actions, 0 = sélectionné) ; le quartet bas restitue l'état
 * des boutons du groupe, 0 = enfoncé. Une pression sur une ligne sélectionnée
 * lève l'interruption joypad.
 */
class Joypad(private val interrupts: InterruptController) {

    /** Bits 4-5 écrits par le jeu (état de sélection). */
    private var select = 0x30

    /** Boutons d'action enfoncés : bit0 A, bit1 B, bit2 Select, bit3 Start. */
    private var actionState = 0

    /** Directions enfoncées : bit0 Droite, bit1 Gauche, bit2 Haut, bit3 Bas. */
    private var directionState = 0

    fun setButton(button: EmulatorButton, pressed: Boolean) {
        val (isAction, bit) = when (button) {
            EmulatorButton.A -> true to 0x01
            EmulatorButton.B -> true to 0x02
            EmulatorButton.SELECT -> true to 0x04
            EmulatorButton.START -> true to 0x08
            EmulatorButton.RIGHT -> false to 0x01
            EmulatorButton.LEFT -> false to 0x02
            EmulatorButton.UP -> false to 0x04
            EmulatorButton.DOWN -> false to 0x08
            // La Game Boy n'a pas de gâchettes L/R : sans effet.
            EmulatorButton.L, EmulatorButton.R -> return
        }
        val wasPressed: Boolean
        if (isAction) {
            wasPressed = (actionState and bit) != 0
            actionState = if (pressed) actionState or bit else actionState and bit.inv()
        } else {
            wasPressed = (directionState and bit) != 0
            directionState = if (pressed) directionState or bit else directionState and bit.inv()
        }
        val groupSelected =
            (isAction && (select and 0x20) == 0) || (!isAction && (select and 0x10) == 0)
        if (pressed && !wasPressed && groupSelected) {
            interrupts.request(Interrupt.JOYPAD)
        }
    }

    fun read(): Int {
        var lines = 0x0F
        if ((select and 0x10) == 0) lines = lines and directionState.inv()
        if ((select and 0x20) == 0) lines = lines and actionState.inv()
        return 0xC0 or select or (lines and 0x0F)
    }

    fun write(value: Int) {
        select = value and 0x30
    }

    // Sérialisation d'état.
    fun stateSelect(): Int = select
    fun restoreState(select: Int) {
        this.select = select and 0x30
    }
}
