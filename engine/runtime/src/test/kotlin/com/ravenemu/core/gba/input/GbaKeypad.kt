package com.ravenemu.core.gba.input

import com.ravenemu.emulation.api.EmulatorButton

/**
 * Clavier de la Game Boy Advance : registre `KEYINPUT` (0x0400_0130), dix
 * touches (A, B, Select, Start, Droite, Gauche, Haut, Bas, R, L).
 *
 * `KEYINPUT` est **actif-bas** : un bit vaut `1` quand la touche est relâchée,
 * `0` quand elle est enfoncée. Les bits 10–15 sont inutilisés (lus à `0`).
 *
 * L'interruption clavier (`KEYCNT`, réveil par touches) relève du contrôleur
 * d'interruptions, différé ; ce clavier n'expose donc que l'état lisible.
 */
class GbaKeypad {

    /** Bits des touches actuellement enfoncées (1 = enfoncée), bits 0–9. */
    var pressedBits: Int = 0

    fun setButton(button: EmulatorButton, pressed: Boolean) {
        // Une touche que cette console n'a pas n'a pas de rang : la traduire en
        // nombre puis décaler poserait un bit au hasard.
        val bit = 1 shl (bitIndex(button) ?: return)
        pressedBits = if (pressed) pressedBits or bit else pressedBits and bit.inv()
    }

    /** Valeur du registre `KEYINPUT` (actif-bas, bits 0–9 significatifs). */
    fun keyInput(): Int = pressedBits.inv() and MASK

    fun reset() {
        pressedBits = 0
    }

    private fun bitIndex(button: EmulatorButton): Int? = when (button) {
        EmulatorButton.A -> 0
        EmulatorButton.B -> 1
        EmulatorButton.SELECT -> 2
        EmulatorButton.START -> 3
        EmulatorButton.RIGHT -> 4
        EmulatorButton.LEFT -> 5
        EmulatorButton.UP -> 6
        EmulatorButton.DOWN -> 7
        EmulatorButton.R -> 8
        EmulatorButton.L -> 9
        // La Game Boy Advance n'a pas les deux touches de la Nintendo DS.
        EmulatorButton.X, EmulatorButton.Y -> null
    }

    companion object {
        /** Adresse du registre KEYINPUT. */
        const val KEYINPUT_ADDRESS = 0x0400_0130

        /** Dix touches significatives (bits 0–9). */
        const val MASK = 0x03FF
    }
}
