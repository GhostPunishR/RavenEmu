package com.ravenemu.core.gba.cpu

import com.ravenemu.core.gba.CpuHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Vérifie le décodage/exécution ARM sur des instructions encodées à la main
 * (indépendantes de la logique testée). Les commentaires donnent l'assembleur.
 */
class ArmDecoderTest {

    @Test
    fun `MOV immediat`() {
        val h = CpuHarness()
        h.loadArm(0xE3A00005.toInt()) // MOV R0, #5
        h.step()
        assertEquals(5, h.reg(0))
    }

    @Test
    fun `MOVS met a jour Z`() {
        val h = CpuHarness()
        h.loadArm(0xE3B00000.toInt()) // MOVS R0, #0
        h.step()
        assertTrue(h.cpu.state.zero)
        assertFalse(h.cpu.state.negative)
    }

    @Test
    fun `ADD registre`() {
        val h = CpuHarness()
        h.setReg(0, 20)
        h.setReg(1, 22)
        h.loadArm(0xE0802001.toInt()) // ADD R2, R0, R1
        h.step()
        assertEquals(42, h.reg(2))
    }

    @Test
    fun `SUBS produit un resultat negatif et efface la retenue`() {
        val h = CpuHarness()
        h.setReg(0, 0)
        h.loadArm(0xE2500001.toInt()) // SUBS R0, R0, #1
        h.step()
        assertEquals(-1, h.reg(0))
        assertTrue(h.cpu.state.negative)
        assertFalse(h.cpu.state.zero)
        assertFalse(h.cpu.state.carry) // emprunt : pas de retenue
    }

    @Test
    fun `ADDS signale le debordement signe`() {
        val h = CpuHarness()
        h.setReg(0, 0x7FFF_FFFF)
        h.setReg(1, 1)
        h.loadArm(0xE0900001.toInt()) // ADDS R0, R0, R1
        h.step()
        assertEquals(0x8000_0000.toInt(), h.reg(0))
        assertTrue(h.cpu.state.overflow)
        assertTrue(h.cpu.state.negative)
    }

    @Test
    fun `barrel shifter LSL immediat`() {
        val h = CpuHarness()
        h.setReg(1, 1)
        h.loadArm(0xE1A00201.toInt()) // MOV R0, R1, LSL #4
        h.step()
        assertEquals(0x10, h.reg(0))
    }

    @Test
    fun `barrel shifter LSR immediat`() {
        val h = CpuHarness()
        h.setReg(1, 2)
        h.loadArm(0xE1A000A1.toInt()) // MOV R0, R1, LSR #1
        h.step()
        assertEquals(1, h.reg(0))
    }

    @Test
    fun `AND et ORR immediats`() {
        val h = CpuHarness()
        h.setReg(0, 0xF0)
        h.loadArm(
            0xE200000F.toInt(), // AND R0, R0, #0x0F
            0xE3800001.toInt(), // ORR R0, R0, #1
        )
        h.step(2)
        assertEquals(1, h.reg(0))
    }

    @Test
    fun `branchement avec lien pose LR et PC`() {
        val h = CpuHarness()
        val base = CpuHarness.IWRAM_BASE
        h.loadArm(0xEB000006.toInt()) // BL +0x20 (vers base+0x20)
        h.step()
        assertEquals(base + 0x20, h.reg(15))
        assertEquals(base + 4, h.reg(14)) // adresse de retour
    }

    @Test
    fun `BX bascule en Thumb`() {
        val h = CpuHarness()
        h.setReg(0, (CpuHarness.IWRAM_BASE + 0x100) or 1) // bit 0 = Thumb
        h.loadArm(0xE12FFF10.toInt()) // BX R0
        h.step()
        assertTrue(h.cpu.state.thumb)
        assertEquals(CpuHarness.IWRAM_BASE + 0x100, h.reg(15))
    }

    @Test
    fun `STR puis LDR font un aller-retour memoire`() {
        val h = CpuHarness()
        h.setReg(0, 0x1234_5678)
        h.setReg(1, 0x0300_0400)
        h.loadArm(
            0xE5810000.toInt(), // STR R0, [R1]
            0xE5912000.toInt(), // LDR R2, [R1]
        )
        h.step(2)
        assertEquals(0x1234_5678, h.reg(2))
    }

    @Test
    fun `LDR non aligne fait tourner le mot`() {
        val h = CpuHarness()
        h.setReg(0, 0x1122_3344)
        h.setReg(1, 0x0300_0400)
        h.setReg(3, 0x0300_0401) // adresse non alignée (+1)
        h.loadArm(
            0xE5810000.toInt(), // STR R0, [R1]
            0xE5932000.toInt(), // LDR R2, [R3]
        )
        h.step(2)
        // Rotation de 8 bits : 0x11223344 ror 8 = 0x44112233.
        assertEquals(0x4411_2233, h.reg(2))
    }

    @Test
    fun `LDRB lit un octet non signe`() {
        val h = CpuHarness()
        h.setReg(0, 0x0000_00FF)
        h.setReg(1, 0x0300_0400)
        h.loadArm(
            0xE5C10000.toInt(), // STRB R0, [R1]
            0xE5D12000.toInt(), // LDRB R2, [R1]
        )
        h.step(2)
        assertEquals(0xFF, h.reg(2))
    }

    @Test
    fun `MRS lit le CPSR et MSR change de mode`() {
        val h = CpuHarness()
        h.loadArm(
            0xE10F0000.toInt(), // MRS R0, CPSR
            0xE3A00012.toInt(), // MOV R0, #0x12 (mode IRQ)
            0xE121F000.toInt(), // MSR CPSR_c, R0
        )
        h.step(3)
        assertEquals(CpuState.MODE_IRQ, h.cpu.state.mode)
        // Le R13 banqué du mode IRQ (fixé au reset) devient actif.
        assertEquals(0x0300_7FA0, h.reg(13))
    }

    @Test
    fun `l'execution conditionnelle respecte les drapeaux`() {
        val h = CpuHarness()
        h.setReg(0, 5)
        h.loadArm(
            0xE3500005.toInt(), // CMP R0, #5  → Z=1
            0x03A01001,         // MOVEQ R1, #1 (exécuté)
            0x13A02001,         // MOVNE R2, #1 (ignoré)
        )
        h.step(3)
        assertEquals(1, h.reg(1))
        assertEquals(0, h.reg(2))
    }
}
