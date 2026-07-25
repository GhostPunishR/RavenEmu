package com.ravenemu.core.gba.cpu

import com.ravenemu.core.gba.CpuHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Instructions ARM ajoutées : MUL, demi-mots, LDM/STM, SWP, SWI. */
class ArmExtendedTest {

    @Test
    fun `MUL multiplie deux registres`() {
        val h = CpuHarness()
        h.setReg(1, 6)
        h.setReg(2, 7)
        h.loadArm(0xE0000291.toInt()) // MUL R0, R1, R2
        h.step()
        assertEquals(42, h.reg(0))
    }

    @Test
    fun `MLA multiplie et accumule`() {
        val h = CpuHarness()
        h.setReg(1, 6)
        h.setReg(2, 7)
        h.setReg(3, 100)
        // MLA R0, R1, R2, R3 : cond E, A=1 → 0xE0203291
        h.loadArm(0xE0203291.toInt())
        h.step()
        assertEquals(142, h.reg(0))
    }

    @Test
    fun `UMULL produit un résultat 64 bits`() {
        val h = CpuHarness()
        h.setReg(2, 0x1000_0000)
        h.setReg(3, 0x10)
        // UMULL R0(lo), R1(hi), R2, R3 : 0x00800090|(1<<16)|(0<<12)|(3<<8)|2
        h.loadArm(0xE0810392.toInt())
        h.step()
        assertEquals(0x0000_0000, h.reg(0)) // bits bas
        assertEquals(0x0000_0001, h.reg(1)) // bits hauts (0x1_0000_0000)
    }

    @Test
    fun `STRH et LDRH transfèrent un demi-mot`() {
        val h = CpuHarness()
        h.setReg(0, 0x1234_5678)
        h.setReg(1, 0x0300_0400)
        h.loadArm(
            0xE1C100B0.toInt(), // STRH R0, [R1]
            0xE1D120B0.toInt(), // LDRH R2, [R1]
        )
        h.step(2)
        assertEquals(0x5678, h.reg(2))
    }

    @Test
    fun `LDRSB étend le signe d'un octet`() {
        val h = CpuHarness()
        h.setReg(0, 0x80)
        h.setReg(1, 0x0300_0400)
        h.loadArm(
            0xE5C10000.toInt(), // STRB R0, [R1]
            0xE1D120D0.toInt(), // LDRSB R2, [R1]
        )
        h.step(2)
        assertEquals(-128, h.reg(2))
    }

    @Test
    fun `STMIA puis LDMIA avec réécriture de base`() {
        val h = CpuHarness()
        h.setReg(0, 0x0300_0400)
        h.setReg(1, 0xAAAA)
        h.setReg(2, 0xBBBB)
        h.loadArm(0xE8A00006.toInt()) // STMIA R0!, {R1, R2}
        h.step()
        assertEquals(0x0300_0408, h.reg(0)) // base avancée de 2 mots

        h.setReg(3, 0x0300_0400)
        h.loadArm(0xE8B30030.toInt()) // LDMIA R3!, {R4, R5}
        h.step()
        assertEquals(0xAAAA, h.reg(4))
        assertEquals(0xBBBB, h.reg(5))
        assertEquals(0x0300_0408, h.reg(3))
    }

    @Test
    fun `SWP échange registre et mémoire`() {
        val h = CpuHarness()
        h.setReg(0, 0x0300_0400)
        h.setReg(1, 0x2222_2222)
        h.writeWord(0x0300_0400, 0x1111_1111)
        h.loadArm(0xE1002091.toInt()) // SWP R2, R1, [R0]
        h.step()
        assertEquals(0x1111_1111, h.reg(2))       // ancienne valeur mémoire
        assertEquals(0x2222_2222, h.readWord(0x0300_0400)) // R1 écrit en mémoire
    }

    @Test
    fun `SWI entre en mode superviseur au vecteur 0x08`() {
        val h = CpuHarness()
        val base = CpuHarness.IWRAM_BASE
        h.loadArm(0xEF000000.toInt()) // SWI 0
        h.step()
        assertEquals(CpuState.MODE_SUPERVISOR, h.cpu.state.mode)
        assertEquals(0x08, h.reg(15))
        assertEquals(base + 4, h.reg(14)) // adresse de retour dans LR_svc
        assertTrue(h.cpu.state.irqDisabled)
    }
}
