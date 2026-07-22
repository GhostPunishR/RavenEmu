package com.ravenemu.core.gb.io

import com.ravenemu.core.gb.Interrupt
import com.ravenemu.core.gb.InterruptController

/**
 * Port série minimal : aucun pair n'est connecté. Un transfert lancé à
 * l'horloge interne (SC = 0x81) se termine après 8 bits à 8192 Hz
 * (4096 T-cycles), reçoit 0xFF et lève l'interruption série — le comportement
 * observé sur console sans câble. Le câble link n'est pas émulé (limite
 * documentée).
 */
class SerialPort(private val interrupts: InterruptController) {

    var data = 0x00 // SB
        private set
    private var control = 0x7E // SC, bits non câblés à 1
    private var remainingCycles = 0

    fun tick(cycles: Int) {
        if (remainingCycles <= 0) return
        remainingCycles -= cycles
        if (remainingCycles <= 0) {
            remainingCycles = 0
            data = 0xFF
            control = control and 0x7F
            interrupts.request(Interrupt.SERIAL)
        }
    }

    fun readData(): Int = data

    fun writeData(value: Int) {
        data = value and 0xFF
    }

    fun readControl(): Int = control or 0x7E

    fun writeControl(value: Int) {
        control = (value and 0x81) or 0x7E
        remainingCycles = if ((value and 0x80) != 0 && (value and 0x01) != 0) {
            TRANSFER_CYCLES
        } else {
            0
        }
    }

    // Sérialisation d'état.
    fun stateControl(): Int = control
    fun stateRemaining(): Int = remainingCycles
    fun restoreState(data: Int, control: Int, remaining: Int) {
        this.data = data and 0xFF
        this.control = control
        this.remainingCycles = remaining
    }

    companion object {
        /** 8 bits à 8192 Hz sur une horloge à 4 194 304 Hz. */
        const val TRANSFER_CYCLES = 8 * 512
    }
}
