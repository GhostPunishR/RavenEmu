package com.ravenemu.core.gba.timer

import com.ravenemu.core.gba.interrupt.GbaInterruptController
import com.ravenemu.core.gba.interrupt.Interrupt

/**
 * Quatre timers de la Game Boy Advance (`TM0`–`TM3`, registres 0x0400_0100…).
 *
 * Chaque timer possède un compteur 16 bits, une valeur de rechargement (écrite
 * dans `TMxCNT_L`, le compteur étant relu à la même adresse) et un contrôle
 * `TMxCNT_H` : prédiviseur (1/64/256/1024), mode **cascade** (bit 2 : le timer
 * s'incrémente au débordement du précédent au lieu de l'horloge), IRQ au
 * débordement (bit 6) et activation (bit 7). Le compteur repart de la valeur de
 * rechargement à chaque débordement.
 */
class GbaTimers(private val interrupts: GbaInterruptController) {

    private val counter = IntArray(4)
    private val reload = IntArray(4)
    private val control = IntArray(4)
    private val prescalerCounter = IntArray(4)

    fun onReloadWrite(timer: Int, value: Int) {
        reload[timer] = value and 0xFFFF
    }

    fun onControlWrite(timer: Int, value: Int) {
        val wasEnabled = control[timer] and 0x80 != 0
        control[timer] = value and 0xFFFF
        val nowEnabled = value and 0x80 != 0
        if (!wasEnabled && nowEnabled) {
            counter[timer] = reload[timer]
            prescalerCounter[timer] = 0
        }
    }

    fun counter(timer: Int): Int = counter[timer]

    fun control(timer: Int): Int = control[timer]

    /** Avance les timers pilotés par l'horloge de [cycles] cycles CPU. */
    fun tick(cycles: Int) {
        for (i in 0 until 4) {
            if (control[i] and 0x80 == 0) continue // désactivé
            if (control[i] and 0x04 != 0) continue // cascade : piloté par le précédent
            val prescale = PRESCALE[control[i] and 0x3]
            prescalerCounter[i] += cycles
            while (prescalerCounter[i] >= prescale) {
                prescalerCounter[i] -= prescale
                increment(i)
            }
        }
    }

    private fun increment(timer: Int) {
        counter[timer]++
        if (counter[timer] <= 0xFFFF) return
        counter[timer] = reload[timer]
        if (control[timer] and 0x40 != 0) interrupts.request(Interrupt.TIMER0 + timer)
        // Cascade : incrémente le timer suivant s'il est en mode count-up.
        val next = timer + 1
        if (next < 4 && control[next] and 0x80 != 0 && control[next] and 0x04 != 0) {
            increment(next)
        }
    }

    fun reset() {
        counter.fill(0)
        reload.fill(0)
        control.fill(0)
        prescalerCounter.fill(0)
    }

    /** État sérialisable (compteurs, rechargements, contrôles, prédiviseurs). */
    fun exportState(): IntArray = counter + reload + control + prescalerCounter

    fun importState(data: IntArray) {
        for (i in 0 until 4) {
            counter[i] = data[i]
            reload[i] = data[4 + i]
            control[i] = data[8 + i]
            prescalerCounter[i] = data[12 + i]
        }
    }

    private companion object {
        val PRESCALE = intArrayOf(1, 64, 256, 1024)
    }
}
