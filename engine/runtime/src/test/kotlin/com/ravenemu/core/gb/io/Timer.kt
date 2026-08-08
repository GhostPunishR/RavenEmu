package com.ravenemu.core.gb.io

import com.ravenemu.core.gb.Interrupt
import com.ravenemu.core.gb.InterruptController

/**
 * Timers matériels : DIV est l'octet haut d'un compteur interne 16 bits
 * incrémenté à chaque T-cycle ; TIMA s'incrémente sur front descendant du
 * bit sélectionné par TAC (ET logique avec le bit d'activation), ce qui
 * reproduit les effets de bord des écritures DIV/TAC. Le débordement de TIMA
 * laisse la valeur à 0 pendant 4 cycles avant rechargement TMA et demande
 * d'interruption ; une écriture TIMA dans cette fenêtre annule le
 * rechargement.
 */
class Timer(private val interrupts: InterruptController) {

    /** Compteur interne. Valeur post-boot telle que DIV = 0xAB. */
    var divCounter = 0xAB00
        private set

    var tima = 0x00
        private set
    var tma = 0x00
    private var tac = 0xF8

    /** Cycles restants avant rechargement TMA après débordement (-1 : aucun). */
    private var reloadDelay = -1

    private fun timerSignal(counter: Int, tacValue: Int): Boolean =
        (tacValue and 0x04) != 0 && (counter and selectedBitMaskFor(tacValue)) != 0

    private fun selectedBitMaskFor(tacValue: Int): Int = when (tacValue and 0x03) {
        0x00 -> 1 shl 9
        0x01 -> 1 shl 3
        0x02 -> 1 shl 5
        else -> 1 shl 7
    }

    private fun incrementTima() {
        tima = (tima + 1) and 0xFF
        if (tima == 0) reloadDelay = 4
    }

    private fun setDivCounter(value: Int) {
        val before = timerSignal(divCounter, tac)
        divCounter = value and 0xFFFF
        if (before && !timerSignal(divCounter, tac)) incrementTima()
    }

    fun tick(cycles: Int) {
        repeat(cycles) {
            if (reloadDelay > 0) {
                reloadDelay--
                if (reloadDelay == 0) {
                    tima = tma
                    interrupts.request(Interrupt.TIMER)
                    reloadDelay = -1
                }
            }
            setDivCounter(divCounter + 1)
        }
    }

    fun readDiv(): Int = (divCounter shr 8) and 0xFF

    /** Toute écriture remet le compteur interne à zéro. */
    fun writeDiv() {
        setDivCounter(0)
    }

    fun readTima(): Int = tima

    fun writeTima(value: Int) {
        // Une écriture pendant la fenêtre de rechargement l'annule.
        if (reloadDelay > 0) reloadDelay = -1
        tima = value and 0xFF
    }

    fun readTac(): Int = tac or 0xF8

    fun writeTac(value: Int) {
        val before = timerSignal(divCounter, tac)
        tac = (value and 0x07) or 0xF8
        if (before && !timerSignal(divCounter, tac)) incrementTima()
    }

    // Accès directs pour la sérialisation d'état.
    fun stateReloadDelay(): Int = reloadDelay
    fun restoreState(divCounter: Int, tima: Int, tma: Int, tac: Int, reloadDelay: Int) {
        this.divCounter = divCounter and 0xFFFF
        this.tima = tima and 0xFF
        this.tma = tma and 0xFF
        this.tac = tac or 0xF8
        this.reloadDelay = reloadDelay
    }
}
