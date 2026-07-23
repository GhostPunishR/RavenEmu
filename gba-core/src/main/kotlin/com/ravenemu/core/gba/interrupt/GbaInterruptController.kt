package com.ravenemu.core.gba.interrupt

/**
 * Contrôleur d'interruptions de la Game Boy Advance : registres `IE`
 * (0x0400_0200, activation par source), `IF` (0x0400_0202, drapeaux en attente,
 * effacés en écrivant `1`) et `IME` (0x0400_0208, activation globale, bit 0).
 *
 * Une source lève son bit dans `IF` via [request]. Une interruption est en
 * attente de livraison lorsque `IME` est actif et qu'un même bit est présent
 * dans `IE` et `IF` ([pending]). Le CPU consulte cet état et, si son drapeau `I`
 * l'autorise, prend l'exception IRQ.
 */
class GbaInterruptController {

    /** Registre `IE` : masque des sources autorisées. */
    var enable = 0

    /** Registre `IF` : sources en attente (bit à 1 = interruption levée). */
    var flags = 0

    /** Registre `IME` : activation globale des interruptions. */
    var masterEnable = false

    /** Lève l'interruption [bit] (une des constantes [Interrupt]). */
    fun request(bit: Int) {
        flags = flags or (1 shl bit)
    }

    /** Acquittement : écrire `1` sur un bit d'`IF` l'efface. */
    fun acknowledge(value: Int) {
        flags = flags and value.inv()
    }

    /** `true` si une interruption autorisée est en attente de livraison. */
    fun pending(): Boolean = masterEnable && (enable and flags and 0x3FFF) != 0

    fun reset() {
        enable = 0
        flags = 0
        masterEnable = false
    }
}

/** Numéros de bit des sources d'interruption GBA (`IE`/`IF`). */
object Interrupt {
    const val VBLANK = 0
    const val HBLANK = 1
    const val VCOUNT = 2
    const val TIMER0 = 3
    const val TIMER1 = 4
    const val TIMER2 = 5
    const val TIMER3 = 6
    const val SERIAL = 7
    const val DMA0 = 8
    const val DMA1 = 9
    const val DMA2 = 10
    const val DMA3 = 11
    const val KEYPAD = 12
    const val GAMEPAK = 13
}
