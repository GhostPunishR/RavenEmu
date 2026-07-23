package com.ravenemu.core.gba.cpu

import com.ravenemu.core.gba.CpuHarness
import kotlin.test.Test
import kotlin.test.assertEquals

/** Formats Thumb ajoutés : chargements/stockages, pile, MUL, SWI. */
class ThumbExtendedTest {

    @Test
    fun `PUSH puis POP via la pile`() {
        val h = CpuHarness()
        h.setReg(13, 0x0300_0420) // SP dans une zone inscriptible
        h.setReg(0, 0x1234)
        h.loadThumb(
            0xB401, // PUSH {R0}
            0xBC02, // POP {R1}
        )
        h.step(2)
        assertEquals(0x1234, h.reg(1))
        assertEquals(0x0300_0420, h.reg(13)) // SP rétabli
    }

    @Test
    fun `STR et LDR avec offset immédiat`() {
        val h = CpuHarness()
        h.setReg(1, 0x0300_0400)
        h.setReg(0, 0x0000_CAFE)
        h.loadThumb(
            0x6048, // STR R0, [R1, #4]
            0x684A, // LDR R2, [R1, #4]
        )
        h.step(2)
        assertEquals(0x0000_CAFE, h.reg(2))
    }

    @Test
    fun `STRH et LDRH avec offset immédiat`() {
        val h = CpuHarness()
        h.setReg(1, 0x0300_0400)
        h.setReg(0, 0xABCD_1234.toInt())
        h.loadThumb(
            0x8048, // STRH R0, [R1, #2]
            0x884A, // LDRH R2, [R1, #2]
        )
        h.step(2)
        assertEquals(0x1234, h.reg(2))
    }

    @Test
    fun `MUL Thumb`() {
        val h = CpuHarness()
        h.setReg(0, 6)
        h.setReg(1, 7)
        h.loadThumb(0x4348) // MUL R0, R1
        h.step()
        assertEquals(42, h.reg(0))
    }

    @Test
    fun `chargement relatif au SP`() {
        val h = CpuHarness()
        h.setReg(13, 0x0300_0400)
        h.setReg(0, 0x0000_BEEF)
        h.loadThumb(
            0x9001, // STR R0, [SP, #4]
            0x9901, // LDR R1, [SP, #4]
        )
        h.step(2)
        assertEquals(0x0000_BEEF, h.reg(1))
    }

    @Test
    fun `calcul d'adresse relative au SP`() {
        val h = CpuHarness()
        h.setReg(13, 0x0300_0400)
        h.loadThumb(0xA801) // ADD R0, SP, #4
        h.step()
        assertEquals(0x0300_0404, h.reg(0))
    }

    @Test
    fun `SWI Thumb entre en mode superviseur`() {
        val h = CpuHarness()
        val base = CpuHarness.IWRAM_BASE
        h.loadThumb(0xDF00) // SWI 0
        h.step()
        assertEquals(CpuState.MODE_SUPERVISOR, h.cpu.state.mode)
        assertEquals(0x08, h.reg(15))
        assertEquals(base + 2, h.reg(14)) // retour Thumb (pc+2)
        assertEquals(false, h.cpu.state.thumb) // l'exception s'exécute en ARM
    }
}
