package com.ravenemu.core.gba.interrupt

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GbaInterruptControllerTest {

    @Test
    fun `une interruption est en attente si IME, IE et IF concordent`() {
        val ic = GbaInterruptController()
        ic.masterEnable = true
        ic.enable = 1 shl Interrupt.VBLANK
        ic.request(Interrupt.VBLANK)
        assertTrue(ic.pending())
    }

    @Test
    fun `IME désactivé bloque la livraison`() {
        val ic = GbaInterruptController()
        ic.masterEnable = false
        ic.enable = 0xFFFF
        ic.request(Interrupt.VBLANK)
        assertFalse(ic.pending())
    }

    @Test
    fun `une source non autorisée par IE ne passe pas`() {
        val ic = GbaInterruptController()
        ic.masterEnable = true
        ic.enable = 1 shl Interrupt.HBLANK
        ic.request(Interrupt.VBLANK)
        assertFalse(ic.pending())
    }

    @Test
    fun `écrire 1 dans IF acquitte l'interruption`() {
        val ic = GbaInterruptController()
        ic.masterEnable = true
        ic.enable = 0xFFFF
        ic.request(Interrupt.TIMER0)
        assertTrue(ic.pending())
        ic.acknowledge(1 shl Interrupt.TIMER0)
        assertFalse(ic.pending())
    }
}
