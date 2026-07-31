package com.ravenemu.core.gba.input

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.emulation.api.EmulatorButton
import kotlin.test.Test
import kotlin.test.assertEquals

class GbaKeypadTest {

    @Test
    fun `au repos toutes les touches sont relachees (actif-bas)`() {
        val keypad = GbaKeypad()
        assertEquals(0x03FF, keypad.keyInput())
    }

    @Test
    fun `une touche enfoncee met son bit a zero`() {
        val keypad = GbaKeypad()
        keypad.setButton(EmulatorButton.A, true) // bit 0
        assertEquals(0x03FE, keypad.keyInput())
        keypad.setButton(EmulatorButton.A, false)
        assertEquals(0x03FF, keypad.keyInput())
    }

    @Test
    fun `les gachettes L et R occupent les bits 9 et 8`() {
        val keypad = GbaKeypad()
        keypad.setButton(EmulatorButton.R, true) // bit 8
        assertEquals(0x03FF and (1 shl 8).inv(), keypad.keyInput())
        keypad.setButton(EmulatorButton.L, true) // bit 9
        assertEquals(0x03FF and (1 shl 8).inv() and (1 shl 9).inv(), keypad.keyInput())
    }

    @Test
    fun `chaque touche occupe un bit distinct`() {
        val expected = mapOf(
            EmulatorButton.A to 0,
            EmulatorButton.B to 1,
            EmulatorButton.SELECT to 2,
            EmulatorButton.START to 3,
            EmulatorButton.RIGHT to 4,
            EmulatorButton.LEFT to 5,
            EmulatorButton.UP to 6,
            EmulatorButton.DOWN to 7,
            EmulatorButton.R to 8,
            EmulatorButton.L to 9,
        )
        for ((button, bit) in expected) {
            val keypad = GbaKeypad()
            keypad.setButton(button, true)
            assertEquals(0x03FF and (1 shl bit).inv(), keypad.keyInput(), "bit de $button")
        }
    }

    @Test
    fun `KEYINPUT est lisible via le bus`() {
        val core = GbaCore()
        core.loadRom(SyntheticRom.build())
        val bus = core.machine!!.bus
        core.setButton(EmulatorButton.START, true) // bit 3
        assertEquals(0x03FF and (1 shl 3).inv(), bus.read16(GbaKeypad.KEYINPUT_ADDRESS))
        // Lecture 8 bits basse et haute cohérente.
        assertEquals((0x03FF and (1 shl 3).inv()) and 0xFF, bus.read8(GbaKeypad.KEYINPUT_ADDRESS))
    }
}
