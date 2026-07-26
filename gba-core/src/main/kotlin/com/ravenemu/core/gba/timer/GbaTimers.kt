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

    /** Notification de débordement, utilisée par les canaux Direct Sound. */
    var onOverflow: ((Int) -> Unit)? = null

    private val counter = IntArray(4)
    private val reload = IntArray(4)
    private val control = IntArray(4)
    private val prescalerCounter = IntArray(4)

    /**
     * Cycles reçus mais pas encore appliqués, et nombre de cycles avant le
     * prochain débordement.
     *
     * Entre deux débordements, la seule chose qu'un timer donne à voir est la
     * valeur de son compteur, lisible par les registres d'E/S. Tout le reste —
     * interruption, échantillon Direct Sound, cascade — n'arrive qu'au
     * débordement. Les cycles sont donc **accumulés** et n'appliqués qu'au
     * moment utile : à l'échéance, ou dès qu'on lit un compteur. Appelé après
     * chaque instruction, ce point d'entrée se réduit ainsi à une addition et
     * une comparaison.
     */
    private var pendingCycles = 0
    private var cyclesUntilOverflow = NO_OVERFLOW

    fun onReloadWrite(timer: Int, value: Int) {
        flush() // les cycles en attente valent encore pour l'ancien rechargement
        reload[timer] = value and 0xFFFF
    }

    fun onControlWrite(timer: Int, value: Int) {
        flush() // idem : appliqué avant que les réglages ne changent
        val wasEnabled = control[timer] and 0x80 != 0
        control[timer] = value and 0xFFFF
        val nowEnabled = value and 0x80 != 0
        if (!wasEnabled && nowEnabled) {
            counter[timer] = reload[timer]
            prescalerCounter[timer] = 0
        }
        cyclesUntilOverflow = computeBudget()
    }

    /** Compteur courant. Les cycles en attente sont appliqués d'abord. */
    fun counter(timer: Int): Int {
        flush()
        return counter[timer]
    }

    fun control(timer: Int): Int = control[timer]

    /**
     * Avance les timers pilotés par l'horloge de [cycles] cycles CPU.
     *
     * Le calcul est fait **en une division**, non pas cycle par cycle. Un canal
     * Direct Sound est cadencé par un timer de prédiviseur 1 : à raison d'un
     * appel après chaque instruction, une boucle d'incrémentation coûtait ici
     * plusieurs centaines de milliers de tours par trame, pour un résultat
     * strictement identique.
     */
    fun tick(cycles: Int) {
        pendingCycles += cycles
        // Rien d'observable avant la prochaine échéance : on diffère.
        if (pendingCycles < cyclesUntilOverflow) return
        flush()
    }

    /** Applique les cycles accumulés, puis recalcule la prochaine échéance. */
    private fun flush() {
        val cycles = pendingCycles
        if (cycles > 0) {
            pendingCycles = 0
            applyCycles(cycles)
        }
        cyclesUntilOverflow = computeBudget()
    }

    /**
     * Nombre de cycles avant le prochain débordement, tous timers confondus.
     * [NO_OVERFLOW] si aucun timer piloté par l'horloge n'est actif.
     */
    private fun computeBudget(): Int {
        var budget = NO_OVERFLOW
        for (i in 0 until 4) {
            val settings = control[i]
            if (settings and 0x80 == 0 || settings and 0x04 != 0) continue
            val prescale = PRESCALE[settings and 0x3]
            val ticks = 0x1_0000 - counter[i]
            val cycles = ticks * prescale - prescalerCounter[i]
            if (cycles < budget) budget = cycles
        }
        return budget
    }

    private fun applyCycles(cycles: Int) {
        for (i in 0 until 4) {
            val settings = control[i]
            if (settings and 0x80 == 0) continue // désactivé
            if (settings and 0x04 != 0) continue // cascade : piloté par le précédent
            val prescale = PRESCALE[settings and 0x3]
            val total = prescalerCounter[i] + cycles
            if (total < prescale) {
                prescalerCounter[i] = total
                continue
            }
            prescalerCounter[i] = total % prescale
            advance(i, total / prescale)
        }
    }

    /**
     * Ajoute [ticks] incréments au compteur du timer [timer], en traitant d'un
     * bloc les débordements éventuels. Chaque débordement conserve ses effets :
     * interruption, échantillon Direct Sound consommé, cascade.
     */
    private fun advance(timer: Int, ticks: Int) {
        val value = counter[timer] + ticks
        if (value <= 0xFFFF) {
            counter[timer] = value
            return
        }
        // Après le premier débordement, le compteur repart du rechargement :
        // la période vaut donc 0x10000 - rechargement.
        val period = 0x1_0000 - reload[timer]
        val excess = value - 0x1_0000
        val overflows = 1 + excess / period
        counter[timer] = reload[timer] + excess % period
        repeat(overflows) { onTimerOverflow(timer) }
    }

    /** Effets d'un débordement : interruption, Direct Sound, cascade. */
    private fun onTimerOverflow(timer: Int) {
        if (control[timer] and 0x40 != 0) interrupts.request(Interrupt.TIMER0 + timer)
        // Les canaux Direct Sound consomment un échantillon à chaque débordement.
        onOverflow?.invoke(timer)
        // Cascade : incrémente le timer suivant s'il est en mode count-up.
        val next = timer + 1
        if (next < 4 && control[next] and 0x80 != 0 && control[next] and 0x04 != 0) {
            advance(next, 1)
        }
    }

    fun reset() {
        counter.fill(0)
        reload.fill(0)
        control.fill(0)
        prescalerCounter.fill(0)
        pendingCycles = 0
        cyclesUntilOverflow = NO_OVERFLOW
    }

    /** État sérialisable (compteurs, rechargements, contrôles, prédiviseurs). */
    fun exportState(): IntArray {
        flush() // l'état exporté ne doit rien devoir à une file d'attente
        return counter + reload + control + prescalerCounter
    }

    fun importState(data: IntArray) {
        for (i in 0 until 4) {
            counter[i] = data[i]
            reload[i] = data[4 + i]
            control[i] = data[8 + i]
            prescalerCounter[i] = data[12 + i]
        }
        pendingCycles = 0
        cyclesUntilOverflow = computeBudget()
    }

    private companion object {
        val PRESCALE = intArrayOf(1, 64, 256, 1024)

        /** Aucun timer actif : aucune échéance à surveiller. */
        const val NO_OVERFLOW = Int.MAX_VALUE
    }
}
