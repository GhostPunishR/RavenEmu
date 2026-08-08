package com.ravenemu.core.gb

/**
 * Commutateur de vitesse Game Boy Color (registre KEY1, 0xFF4D).
 *
 * En mode double vitesse, le CPU, les timers et le port série tournent à
 * 8,4 MHz ; le PPU et l'APU restent à 4,19 MHz. Le jeu arme le changement en
 * posant le bit 0 de KEY1, puis exécute STOP : la vitesse bascule et le bit 0
 * se libère. Sans mode CGB, le registre est inerte.
 */
class SpeedController(private val cgbMode: Boolean) {

    /** `true` en double vitesse. */
    var doubleSpeed = false
        private set

    private var armed = false

    /** Décalage à appliquer aux horloges PPU/APU (1 = moitié en double vitesse). */
    val peripheralShift: Int get() = if (doubleSpeed) 1 else 0

    fun readKey1(): Int {
        if (!cgbMode) return 0xFF
        return (if (doubleSpeed) 0x80 else 0x00) or (if (armed) 0x01 else 0x00) or 0x7E
    }

    fun writeKey1(value: Int) {
        if (!cgbMode) return
        armed = (value and 0x01) != 0
    }

    /**
     * Appelé sur STOP : effectue la bascule si elle est armée et retourne
     * `true` dans ce cas.
     */
    fun onStop(): Boolean {
        if (!cgbMode || !armed) return false
        doubleSpeed = !doubleSpeed
        armed = false
        return true
    }

    fun state(): IntArray = intArrayOf(if (doubleSpeed) 1 else 0, if (armed) 1 else 0)

    fun restore(state: IntArray) {
        doubleSpeed = state[0] != 0
        armed = state[1] != 0
    }
}
