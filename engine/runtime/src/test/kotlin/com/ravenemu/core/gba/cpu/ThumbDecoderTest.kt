package com.ravenemu.core.gba.cpu

import com.ravenemu.core.gba.CpuHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Vérifie le décodage/exécution Thumb sur des demi-mots encodés à la main. Les
 * commentaires donnent l'assembleur.
 */
class ThumbDecoderTest {

    @Test
    fun `MOV immediat 8 bits`() {
        val h = CpuHarness()
        h.loadThumb(0x2005) // MOV R0, #5
        h.step()
        assertEquals(5, h.reg(0))
    }

    @Test
    fun `ADD et SUB immediats`() {
        val h = CpuHarness()
        h.loadThumb(
            0x2005, // MOV R0, #5
            0x3001, // ADD R0, #1
            0x3802, // SUB R0, #2
        )
        h.step(3)
        assertEquals(4, h.reg(0))
    }

    @Test
    fun `CMP immediat met a jour Z`() {
        val h = CpuHarness()
        h.loadThumb(
            0x2005, // MOV R0, #5
            0x2805, // CMP R0, #5
        )
        h.step(2)
        assertTrue(h.cpu.state.zero)
    }

    @Test
    fun `decalage LSL immediat (format 1)`() {
        val h = CpuHarness()
        h.setReg(1, 1)
        h.loadThumb(0x0108) // LSL R0, R1, #4
        h.step()
        assertEquals(0x10, h.reg(0))
    }

    @Test
    fun `ADD registre (format 2)`() {
        val h = CpuHarness()
        h.setReg(0, 20)
        h.setReg(1, 22)
        h.loadThumb(0x1842) // ADD R2, R0, R1
        h.step()
        assertEquals(42, h.reg(2))
    }

    @Test
    fun `ALU AND`() {
        val h = CpuHarness()
        h.setReg(0, 0xF0)
        h.setReg(1, 0x3C)
        h.loadThumb(0x4008) // AND R0, R1
        h.step()
        assertEquals(0x30, h.reg(0))
    }

    @Test
    fun `ALU NEG`() {
        val h = CpuHarness()
        h.setReg(1, 7)
        h.loadThumb(0x4248) // NEG R0, R1
        h.step()
        assertEquals(-7, h.reg(0))
    }

    @Test
    fun `BX rebascule en ARM`() {
        val h = CpuHarness()
        h.setReg(0, CpuHarness.IWRAM_BASE + 0x200) // adresse paire → ARM
        h.loadThumb(0x4700) // BX R0
        h.step()
        assertFalse(h.cpu.state.thumb)
        assertEquals(CpuHarness.IWRAM_BASE + 0x200, h.reg(15))
    }

    @Test
    fun `chargement relatif au PC`() {
        val h = CpuHarness()
        h.writeWord(CpuHarness.IWRAM_BASE + 4, 0xCAFE_BABE.toInt())
        h.loadThumb(0x4800) // LDR R0, [PC, #0] → lit à (PC+4)&~2
        h.step()
        assertEquals(0xCAFE_BABE.toInt(), h.reg(0))
    }

    @Test
    fun `branchement inconditionnel saute une instruction`() {
        val h = CpuHarness()
        h.loadThumb(
            0xE000, // B +0 → saute l'instruction suivante
            0x2001, // MOV R0, #1 (sauté)
            0x2002, // MOV R0, #2
        )
        h.step(2)
        assertEquals(2, h.reg(0))
    }

    @Test
    fun `branchement conditionnel BEQ`() {
        val h = CpuHarness()
        h.loadThumb(
            0x2800, // CMP R0, #0 → Z=1 (R0 = 0)
            0xD000, // BEQ +0 → saute l'instruction suivante
            0x2005, // MOV R0, #5 (sauté)
            0x2007, // MOV R0, #7
        )
        h.step(3)
        assertEquals(7, h.reg(0))
    }

    @Test
    fun `branchement long avec lien (BL)`() {
        val h = CpuHarness()
        val base = CpuHarness.IWRAM_BASE
        h.loadThumb(
            0xF000, // BL (partie haute, offset 0)
            0xF806, // BL (partie basse, +12)
        )
        h.step(2)
        assertEquals(base + 16, h.reg(15))
        assertEquals((base + 4) or 1, h.reg(14)) // retour marqué Thumb
    }
}
