package com.ravenemu.core.gba.bios

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.interrupt.Interrupt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GbaBiosTest {

    private fun machine() = GbaMachine(SyntheticRom.build())

    @Test
    fun `SWI Div renvoie quotient, reste et valeur absolue`() {
        val m = machine()
        m.cpu.state.regs[0] = 100
        m.cpu.state.regs[1] = 7
        m.bios.handleSwi(0x06)
        assertEquals(14, m.cpu.state.regs[0])
        assertEquals(2, m.cpu.state.regs[1])
        assertEquals(14, m.cpu.state.regs[3])
    }

    @Test
    fun `SWI Div gère les négatifs`() {
        val m = machine()
        m.cpu.state.regs[0] = -100
        m.cpu.state.regs[1] = 7
        m.bios.handleSwi(0x06)
        assertEquals(-14, m.cpu.state.regs[0])
        assertEquals(14, m.cpu.state.regs[3]) // |quotient|
    }

    @Test
    fun `SWI Sqrt renvoie la racine entière`() {
        val m = machine()
        m.cpu.state.regs[0] = 144
        m.bios.handleSwi(0x08)
        assertEquals(12, m.cpu.state.regs[0])
    }

    @Test
    fun `SWI CpuSet copie des mots 32 bits`() {
        val m = machine()
        m.bus.write32(0x0200_0000, 0xDEAD_BEEF.toInt())
        m.bus.write32(0x0200_0004, 0x1234_5678)
        m.cpu.state.regs[0] = 0x0200_0000
        m.cpu.state.regs[1] = 0x0200_1000
        m.cpu.state.regs[2] = 2 or (1 shl 26) // 2 mots, transfert 32 bits
        m.bios.handleSwi(0x0B)
        assertEquals(0xDEAD_BEEF.toInt(), m.bus.read32(0x0200_1000))
        assertEquals(0x1234_5678, m.bus.read32(0x0200_1004))
    }

    @Test
    fun `SWI Halt met le CPU en pause puis une interruption le réveille`() {
        val m = machine()
        m.bios.handleSwi(0x02)
        assertTrue(m.cpu.state.halted)
        m.interrupts.enable = 0xFFFF
        m.interrupts.request(Interrupt.VBLANK)
        m.runFrame(256)
        assertFalse(m.cpu.state.halted)
    }

    @Test
    fun `une instruction SWI est routée vers le BIOS HLE`() {
        val m = machine()
        m.bus.write32(0x0300_0000, 0xEF060000.toInt()) // SWI 0x06 (Div)
        m.cpu.state.regs[15] = 0x0300_0000
        m.cpu.state.thumb = false
        m.cpu.state.regs[0] = 20
        m.cpu.state.regs[1] = 6
        m.cpu.step()
        assertEquals(3, m.cpu.state.regs[0]) // 20 / 6
    }

    @Test
    fun `livraison IRQ de bout en bout via le handler BIOS et le gestionnaire du jeu`() {
        val m = machine()
        // Gestionnaire du jeu en IWRAM : écrit 0xAB en 0x0200_0000 puis revient.
        m.bus.write32(0x0300_0100, 0xE3A000AB.toInt()) // MOV r0, #0xAB
        m.bus.write32(0x0300_0104, 0xE3A01402.toInt()) // MOV r1, #0x0200_0000
        m.bus.write32(0x0300_0108, 0xE5810000.toInt()) // STR r0, [r1]
        m.bus.write32(0x0300_010C, 0xE12FFF1E.toInt()) // BX LR
        // Pointeur de gestionnaire d'IRQ du jeu (lu par le handler BIOS).
        m.bus.write32(0x0300_7FFC, 0x0300_0100)

        m.cpu.state.irqDisabled = false
        m.interrupts.masterEnable = true
        m.interrupts.enable = 0xFFFF
        m.interrupts.request(Interrupt.VBLANK)

        m.runFrame(300)

        assertEquals(0xAB, m.bus.read32(0x0200_0000))
    }
}
