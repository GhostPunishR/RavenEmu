package com.ravenemu.core.gba.timer

import com.ravenemu.core.gba.interrupt.GbaInterruptController
import com.ravenemu.core.gba.interrupt.Interrupt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GbaTimersTest {

    @Test
    fun `le compteur charge la valeur de rechargement à l'activation`() {
        val timers = GbaTimers(GbaInterruptController())
        timers.onReloadWrite(0, 0x1234)
        timers.onControlWrite(0, 0x0080) // activé, prédiviseur 1
        assertEquals(0x1234, timers.counter(0))
    }

    @Test
    fun `le débordement recharge et lève l'IRQ`() {
        val ic = GbaInterruptController()
        val timers = GbaTimers(ic)
        timers.onReloadWrite(0, 0xFFFE)
        timers.onControlWrite(0, 0x00C0) // activé + IRQ, prédiviseur 1
        timers.tick(2) // 0xFFFE → 0xFFFF → débordement
        assertEquals(0xFFFE, timers.counter(0))
        assertTrue(ic.flags and (1 shl Interrupt.TIMER0) != 0)
    }

    @Test
    fun `le prédiviseur 64 ralentit le comptage`() {
        val timers = GbaTimers(GbaInterruptController())
        timers.onReloadWrite(0, 0)
        timers.onControlWrite(0, 0x0081) // activé, prédiviseur 64
        timers.tick(63)
        assertEquals(0, timers.counter(0))
        timers.tick(1)
        assertEquals(1, timers.counter(0))
    }

    @Test
    fun `le mode cascade incrémente le timer suivant`() {
        val timers = GbaTimers(GbaInterruptController())
        timers.onReloadWrite(0, 0xFFFF)
        timers.onControlWrite(0, 0x0080) // T0 activé, prédiviseur 1
        timers.onControlWrite(1, 0x0084) // T1 activé, cascade (count-up)
        timers.tick(1) // T0 déborde → T1 s'incrémente
        assertEquals(1, timers.counter(1))
    }
}
