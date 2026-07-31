package com.ravenemu.core.gb.io

import com.ravenemu.core.gb.InterruptController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TimerTest {

    private fun freshTimer(): Pair<Timer, InterruptController> {
        val interrupts = InterruptController().apply { interruptFlags = 0 }
        val timer = Timer(interrupts)
        timer.writeDiv() // repart d'un compteur nul pour des tests lisibles
        return timer to interrupts
    }

    @Test
    fun `DIV vaut 0xAB au boot`() {
        val interrupts = InterruptController()
        assertEquals(0xAB, Timer(interrupts).readDiv())
    }

    @Test
    fun `DIV s'incremente tous les 256 cycles`() {
        val (timer, _) = freshTimer()
        timer.tick(255)
        assertEquals(0x00, timer.readDiv())
        timer.tick(1)
        assertEquals(0x01, timer.readDiv())
        timer.tick(256 * 5)
        assertEquals(0x06, timer.readDiv())
    }

    @Test
    fun `ecrire DIV remet le compteur a zero`() {
        val (timer, _) = freshTimer()
        timer.tick(1000)
        assertEquals(0x03, timer.readDiv())
        timer.writeDiv()
        assertEquals(0x00, timer.readDiv())
    }

    @Test
    fun `TIMA suit la periode selectionnee par TAC`() {
        // TAC=0x05 : 262144 Hz → incrément tous les 16 cycles.
        val (timer, _) = freshTimer()
        timer.writeTac(0x05)
        timer.tick(16)
        assertEquals(1, timer.readTima())
        timer.tick(16 * 9)
        assertEquals(10, timer.readTima())

        // TAC=0x04 : 4096 Hz → tous les 1024 cycles.
        val (slow, _) = freshTimer()
        slow.writeTac(0x04)
        slow.tick(1024)
        assertEquals(1, slow.readTima())
    }

    @Test
    fun `TIMA n'avance pas quand le timer est desactive`() {
        val (timer, _) = freshTimer()
        timer.writeTac(0x00)
        timer.tick(10_000)
        assertEquals(0, timer.readTima())
    }

    @Test
    fun `debordement TIMA recharge TMA et leve l'interruption apres 4 cycles`() {
        val (timer, interrupts) = freshTimer()
        timer.tma = 0x23
        timer.writeTac(0x05) // période 16
        timer.writeTima(0xFF)
        timer.tick(16) // débordement → TIMA=0, rechargement en attente
        assertEquals(0x00, timer.readTima())
        assertEquals(0, interrupts.interruptFlags and 0x04)
        timer.tick(4)
        assertEquals(0x23, timer.readTima())
        assertEquals(0x04, interrupts.interruptFlags and 0x04)
    }

    @Test
    fun `ecriture TIMA pendant la fenetre annule le rechargement`() {
        val (timer, interrupts) = freshTimer()
        timer.tma = 0x23
        timer.writeTac(0x05)
        timer.writeTima(0xFF)
        timer.tick(16)
        timer.writeTima(0x42) // annule
        timer.tick(8)
        assertEquals(0x42 + 0, timer.readTima())
        assertEquals(0, interrupts.interruptFlags and 0x04)
    }

    @Test
    fun `ecrire DIV peut incrementer TIMA par front descendant`() {
        val (timer, _) = freshTimer()
        timer.writeTac(0x05) // bit 3 du compteur
        timer.tick(8) // bit 3 à 1
        timer.writeDiv() // front descendant → incrément
        assertEquals(1, timer.readTima())
    }

    @Test
    fun `desactiver le timer bit haut incremente TIMA`() {
        val (timer, _) = freshTimer()
        timer.writeTac(0x05)
        timer.tick(8) // bit sélectionné à 1
        timer.writeTac(0x01) // désactivation → front descendant
        assertEquals(1, timer.readTima())
    }

    @Test
    fun `lecture TAC cable les bits hauts a 1`() {
        val (timer, _) = freshTimer()
        timer.writeTac(0x05)
        assertEquals(0xFD, timer.readTac())
    }
}

class JoypadTest {

    private fun fresh(): Pair<Joypad, InterruptController> {
        val interrupts = InterruptController().apply { interruptFlags = 0 }
        return Joypad(interrupts) to interrupts
    }

    @Test
    fun `aucune selection lit 0xF dans le quartet bas`() {
        val (joypad, _) = fresh()
        joypad.write(0x30)
        com.ravenemu.emulation.api.EmulatorButton.entries.forEach {
            joypad.setButton(it, true)
        }
        assertEquals(0x0F, joypad.read() and 0x0F)
    }

    @Test
    fun `groupe directions selectionne`() {
        val (joypad, _) = fresh()
        joypad.write(0x20) // bit 4 = 0 → directions
        joypad.setButton(com.ravenemu.emulation.api.EmulatorButton.RIGHT, true)
        joypad.setButton(com.ravenemu.emulation.api.EmulatorButton.DOWN, true)
        assertEquals(0b0110, joypad.read() and 0x0F)
    }

    @Test
    fun `groupe actions selectionne`() {
        val (joypad, _) = fresh()
        joypad.write(0x10) // bit 5 = 0 → actions
        joypad.setButton(com.ravenemu.emulation.api.EmulatorButton.A, true)
        joypad.setButton(com.ravenemu.emulation.api.EmulatorButton.START, true)
        assertEquals(0b0110, joypad.read() and 0x0F)
    }

    @Test
    fun `pression sur ligne selectionnee leve l'interruption joypad`() {
        val (joypad, interrupts) = fresh()
        joypad.write(0x10)
        joypad.setButton(com.ravenemu.emulation.api.EmulatorButton.B, true)
        assertEquals(0x10, interrupts.interruptFlags and 0x10)
    }

    @Test
    fun `pression sur groupe non selectionne sans interruption`() {
        val (joypad, interrupts) = fresh()
        joypad.write(0x10) // actions sélectionnées
        joypad.setButton(com.ravenemu.emulation.api.EmulatorButton.UP, true)
        assertEquals(0, interrupts.interruptFlags and 0x10)
    }

    @Test
    fun `relachement reflete dans la lecture`() {
        val (joypad, _) = fresh()
        joypad.write(0x20)
        joypad.setButton(com.ravenemu.emulation.api.EmulatorButton.LEFT, true)
        assertEquals(0b1101, joypad.read() and 0x0F)
        joypad.setButton(com.ravenemu.emulation.api.EmulatorButton.LEFT, false)
        assertEquals(0x0F, joypad.read() and 0x0F)
    }
}

class SerialPortTest {

    @Test
    fun `transfert sans pair recoit 0xFF et leve l'interruption`() {
        val interrupts = InterruptController().apply { interruptFlags = 0 }
        val serial = SerialPort(interrupts)
        serial.writeData(0x42)
        serial.writeControl(0x81)
        serial.tick(SerialPort.TRANSFER_CYCLES - 1)
        assertEquals(0x42, serial.readData())
        assertEquals(0, interrupts.interruptFlags and 0x08)
        serial.tick(1)
        assertEquals(0xFF, serial.readData())
        assertTrue((serial.readControl() and 0x80) == 0)
        assertEquals(0x08, interrupts.interruptFlags and 0x08)
    }

    @Test
    fun `horloge externe seule ne termine jamais`() {
        val interrupts = InterruptController().apply { interruptFlags = 0 }
        val serial = SerialPort(interrupts)
        serial.writeData(0x42)
        serial.writeControl(0x80) // start, horloge externe
        serial.tick(100_000)
        assertEquals(0x42, serial.readData())
        assertEquals(0, interrupts.interruptFlags and 0x08)
    }
}
