package com.ravenemu.core.gba.cpu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuStateTest {

    @Test
    fun `le CPSR encode drapeaux, controle et mode`() {
        val state = CpuState()
        state.negative = true
        state.carry = true
        state.thumb = true
        state.switchMode(CpuState.MODE_IRQ)
        val cpsr = state.cpsr()
        assertEquals(1, (cpsr ushr 31) and 1) // N
        assertEquals(0, (cpsr ushr 30) and 1) // Z
        assertEquals(1, (cpsr ushr 29) and 1) // C
        assertEquals(1, (cpsr ushr 5) and 1)  // T
        assertEquals(CpuState.MODE_IRQ, cpsr and 0x1F)
    }

    @Test
    fun `setCpsr restitue drapeaux et mode`() {
        val state = CpuState()
        state.setCpsr(0xA000_0013.toInt(), affectControl = true) // N,C, mode SVC
        assertTrue(state.negative)
        assertFalse(state.zero)
        assertTrue(state.carry)
        assertEquals(CpuState.MODE_SUPERVISOR, state.mode)
    }

    @Test
    fun `les banques R13 et R14 sont propres a chaque mode`() {
        val state = CpuState()
        state.switchMode(CpuState.MODE_SYSTEM)
        state.regs[13] = 0x0300_7F00
        state.regs[14] = 0x1111_1111
        state.switchMode(CpuState.MODE_IRQ)
        state.regs[13] = 0x0300_7FA0
        state.regs[14] = 0x2222_2222
        // Retour en mode système : on retrouve ses registres.
        state.switchMode(CpuState.MODE_SYSTEM)
        assertEquals(0x0300_7F00, state.regs[13])
        assertEquals(0x1111_1111, state.regs[14])
        // Et le mode IRQ a conservé les siens.
        state.switchMode(CpuState.MODE_IRQ)
        assertEquals(0x0300_7FA0, state.regs[13])
        assertEquals(0x2222_2222, state.regs[14])
    }

    @Test
    fun `le mode FIQ banque aussi R8 a R12`() {
        val state = CpuState()
        state.switchMode(CpuState.MODE_SYSTEM)
        state.regs[8] = 0xAAAA
        state.switchMode(CpuState.MODE_FIQ)
        state.regs[8] = 0xBBBB
        state.switchMode(CpuState.MODE_SYSTEM)
        assertEquals(0xAAAA, state.regs[8]) // le R8 utilisateur est préservé
        state.switchMode(CpuState.MODE_FIQ)
        assertEquals(0xBBBB, state.regs[8])
    }

    @Test
    fun `le SPSR est propre au mode privilegie`() {
        val state = CpuState()
        state.switchMode(CpuState.MODE_IRQ)
        state.setSpsr(0x1234_5678)
        state.switchMode(CpuState.MODE_SUPERVISOR)
        state.setSpsr(0x0000_00FF)
        assertEquals(0x0000_00FF, state.spsr())
        state.switchMode(CpuState.MODE_IRQ)
        assertEquals(0x1234_5678, state.spsr())
    }

    @Test
    fun `le mode systeme n'a pas de SPSR`() {
        val state = CpuState()
        state.switchMode(CpuState.MODE_SYSTEM)
        assertFalse(state.hasSpsr())
        state.switchMode(CpuState.MODE_IRQ)
        assertTrue(state.hasSpsr())
    }

    @Test
    fun `import et export de banques conservent l'etat`() {
        val state = CpuState()
        state.switchMode(CpuState.MODE_IRQ)
        state.regs[13] = 0x0300_7FA0
        state.setSpsr(0xDEAD_BEEF.toInt())
        val banks = state.exportBanks()

        val restored = CpuState()
        restored.importBanks(banks)
        restored.setControlRaw(state.cpsr())
        assertEquals(CpuState.MODE_IRQ, restored.mode)
        assertEquals(0xDEAD_BEEF.toInt(), restored.spsr())
    }
}
