package com.ravenemu.core.gb

/** Les cinq sources d'interruption, par ordre de priorité décroissante. */
enum class Interrupt(val mask: Int, val vector: Int) {
    VBLANK(0x01, 0x0040),
    STAT(0x02, 0x0048),
    TIMER(0x04, 0x0050),
    SERIAL(0x08, 0x0058),
    JOYPAD(0x10, 0x0060),
}

/**
 * Registres IF (0xFF0F) et IE (0xFFFF). Le CPU consulte [pending] pour
 * décider du service ; les périphériques lèvent leurs drapeaux via [request].
 */
class InterruptController {

    /** IF brut (5 bits utiles). Valeur post-boot DMG : 0xE1. */
    var interruptFlags = 0xE1

    /** IE brut. */
    var interruptEnable = 0x00

    fun request(interrupt: Interrupt) {
        interruptFlags = interruptFlags or interrupt.mask
    }

    fun acknowledge(interrupt: Interrupt) {
        interruptFlags = interruptFlags and interrupt.mask.inv()
    }

    /** Interruptions à la fois demandées et autorisées par IE. */
    val pending: Int
        get() = interruptEnable and interruptFlags and 0x1F

    /** Plus prioritaire des interruptions en attente, ou `null`. */
    fun highestPending(): Interrupt? {
        val p = pending
        if (p == 0) return null
        return Interrupt.entries.first { (p and it.mask) != 0 }
    }

    /** Lecture bus de IF : les 3 bits hauts sont câblés à 1. */
    fun readFlags(): Int = interruptFlags or 0xE0

    fun writeFlags(value: Int) {
        interruptFlags = value and 0x1F
    }

    fun readEnable(): Int = interruptEnable

    fun writeEnable(value: Int) {
        interruptEnable = value and 0xFF
    }
}
