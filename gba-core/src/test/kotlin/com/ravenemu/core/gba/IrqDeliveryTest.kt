package com.ravenemu.core.gba

import com.ravenemu.core.gba.cpu.CpuState
import com.ravenemu.core.gba.interrupt.Interrupt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Livraison d'une interruption au CPU (entrée en exception IRQ, vecteur 0x18). */
class IrqDeliveryTest {

    @Test
    fun `une interruption en attente déclenche l'exception IRQ`() {
        val m = GbaMachine(SyntheticRom.build())
        // Handler minimal en BIOS : boucle sur elle-même (b .) au vecteur 0x18.
        m.bus.bios[0x18] = 0xFE.toByte()
        m.bus.bios[0x19] = 0xFF.toByte()
        m.bus.bios[0x1A] = 0xFF.toByte()
        m.bus.bios[0x1B] = 0xEA.toByte()

        // Le jeu a autorisé les IRQ (drapeau I dégagé) et activé le VBlank.
        m.cpu.state.irqDisabled = false
        m.interrupts.masterEnable = true
        m.interrupts.enable = 0xFFFF
        m.interrupts.request(Interrupt.VBLANK)

        m.runFrame(12)

        assertEquals(CpuState.MODE_IRQ, m.cpu.state.mode)
        assertEquals(0x18, m.cpu.state.regs[15])          // saut au vecteur IRQ
        assertEquals(0x0800_0004, m.cpu.state.regs[14])   // LR_irq = adresse interrompue + 4
        assertTrue(m.cpu.state.irqDisabled)               // les IRQ sont masquées pendant le handler
    }

    @Test
    fun `le drapeau I bloque la livraison`() {
        val m = GbaMachine(SyntheticRom.build())
        m.cpu.state.irqDisabled = true // IRQ masquées
        m.interrupts.masterEnable = true
        m.interrupts.enable = 0xFFFF
        m.interrupts.request(Interrupt.VBLANK)

        m.runFrame(12)

        // Le CPU reste en mode système, il n'a pas pris l'exception.
        assertEquals(CpuState.MODE_SYSTEM, m.cpu.state.mode)
    }
}
