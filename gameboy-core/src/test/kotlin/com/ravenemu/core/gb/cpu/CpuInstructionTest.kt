package com.ravenemu.core.gb.cpu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuInstructionTest {

    @Test
    fun `registres post-boot DMG`() {
        val cpu = CpuHarness().cpu
        assertEquals(0x01B0, cpu.af)
        assertEquals(0x0013, cpu.bc)
        assertEquals(0x00D8, cpu.de)
        assertEquals(0x014D, cpu.hl)
        assertEquals(0xFFFE, cpu.sp)
        assertEquals(0x0100, cpu.pc)
        assertFalse(cpu.ime)
    }

    @Test
    fun `ecriture de F masque le quartet bas`() {
        val cpu = CpuHarness().cpu
        cpu.af = 0x12FF
        assertEquals(0x12F0, cpu.af)
    }

    @Test
    fun `NOP consomme 4 cycles`() {
        val h = CpuHarness().program(0x00)
        assertEquals(4, h.run())
        assertEquals(0x0101, h.cpu.pc)
    }

    @Test
    fun `LD rr d16 et LD r d8`() {
        val h = CpuHarness().program(
            0x01, 0x34, 0x12, // LD BC,0x1234
            0x3E, 0x99, // LD A,0x99
        )
        assertEquals(12, h.run())
        assertEquals(0x1234, h.cpu.bc)
        assertEquals(8, h.run())
        assertEquals(0x99, h.cpu.a)
    }

    @Test
    fun `LD r r' toutes combinaisons registre`() {
        val h = CpuHarness().program(0x41) // LD B,C
        h.cpu.c = 0x5A
        h.run()
        assertEquals(0x5A, h.cpu.b)

        val h2 = CpuHarness().program(0x70) // LD (HL),B
        h2.cpu.hl = 0xC000
        h2.cpu.b = 0x42
        assertEquals(8, h2.run())
        assertEquals(0x42, h2.bus.memory[0xC000])

        val h3 = CpuHarness().program(0x7E) // LD A,(HL)
        h3.cpu.hl = 0xC000
        h3.bus.memory[0xC000] = 0x77
        assertEquals(8, h3.run())
        assertEquals(0x77, h3.cpu.a)
    }

    @Test
    fun `LDI et LDD deplacent HL`() {
        val h = CpuHarness().program(0x22, 0x32) // LD (HL+),A ; LD (HL-),A
        h.cpu.hl = 0xC000
        h.cpu.a = 0xAA
        h.run()
        assertEquals(0xC001, h.cpu.hl)
        assertEquals(0xAA, h.bus.memory[0xC000])
        h.run()
        assertEquals(0xC000, h.cpu.hl)
        assertEquals(0xAA, h.bus.memory[0xC001])
    }

    @Test
    fun `INC positionne Z et H sans toucher C`() {
        val h = CpuHarness().program(0x3C, 0x3C) // INC A ×2
        h.cpu.a = 0x0F
        h.cpu.f = Cpu.FLAG_C
        h.run()
        assertEquals(0x10, h.cpu.a)
        assertTrue(h.cpu.flagH)
        assertTrue(h.cpu.flagC) // C préservé
        assertFalse(h.cpu.flagZ)

        h.cpu.a = 0xFF
        h.run()
        assertEquals(0x00, h.cpu.a)
        assertTrue(h.cpu.flagZ)
        assertTrue(h.cpu.flagH)
    }

    @Test
    fun `DEC positionne N H Z`() {
        val h = CpuHarness().program(0x05, 0x05) // DEC B ×2
        h.cpu.b = 0x01
        h.run()
        assertEquals(0x00, h.cpu.b)
        assertTrue(h.cpu.flagZ)
        assertTrue(h.cpu.flagN)
        assertFalse(h.cpu.flagH)

        h.cpu.b = 0x10
        h.run()
        assertEquals(0x0F, h.cpu.b)
        assertTrue(h.cpu.flagH)
    }

    @Test
    fun `ADD et ADC avec retenues`() {
        val h = CpuHarness().program(0x80) // ADD A,B
        h.cpu.a = 0x3A
        h.cpu.b = 0xC6
        h.run()
        assertEquals(0x00, h.cpu.a)
        assertTrue(h.cpu.flagZ)
        assertTrue(h.cpu.flagH)
        assertTrue(h.cpu.flagC)
        assertFalse(h.cpu.flagN)

        val h2 = CpuHarness().program(0x88) // ADC A,B
        h2.cpu.a = 0x00
        h2.cpu.b = 0xFF
        h2.cpu.f = Cpu.FLAG_C
        h2.run()
        assertEquals(0x00, h2.cpu.a)
        assertTrue(h2.cpu.flagZ)
        assertTrue(h2.cpu.flagC)
        assertTrue(h2.cpu.flagH)
    }

    @Test
    fun `SUB SBC et CP`() {
        val h = CpuHarness().program(0x90) // SUB B
        h.cpu.a = 0x3E
        h.cpu.b = 0x3E
        h.run()
        assertEquals(0x00, h.cpu.a)
        assertTrue(h.cpu.flagZ)
        assertTrue(h.cpu.flagN)

        val h2 = CpuHarness().program(0x98) // SBC A,B
        h2.cpu.a = 0x00
        h2.cpu.b = 0x00
        h2.cpu.f = Cpu.FLAG_C
        h2.run()
        assertEquals(0xFF, h2.cpu.a)
        assertTrue(h2.cpu.flagC)
        assertTrue(h2.cpu.flagH)

        val h3 = CpuHarness().program(0xFE, 0x42) // CP 0x42
        h3.cpu.a = 0x42
        h3.run()
        assertEquals(0x42, h3.cpu.a) // A inchangé
        assertTrue(h3.cpu.flagZ)
    }

    @Test
    fun `AND XOR OR drapeaux`() {
        val h = CpuHarness().program(0xA0) // AND B
        h.cpu.a = 0xF0
        h.cpu.b = 0x0F
        h.run()
        assertEquals(0x00, h.cpu.a)
        assertTrue(h.cpu.flagZ)
        assertTrue(h.cpu.flagH)
        assertFalse(h.cpu.flagC)

        val h2 = CpuHarness().program(0xA8) // XOR B
        h2.cpu.a = 0xFF
        h2.cpu.b = 0x0F
        h2.run()
        assertEquals(0xF0, h2.cpu.a)
        assertFalse(h2.cpu.flagH)

        val h3 = CpuHarness().program(0xB0) // OR B
        h3.cpu.a = 0x00
        h3.cpu.b = 0x00
        h3.run()
        assertTrue(h3.cpu.flagZ)
    }

    @Test
    fun `ADD HL rr conserve Z`() {
        val h = CpuHarness().program(0x09) // ADD HL,BC
        h.cpu.hl = 0x8A23
        h.cpu.bc = 0x0605
        h.cpu.f = Cpu.FLAG_Z
        h.run()
        assertEquals(0x9028, h.cpu.hl)
        assertTrue(h.cpu.flagZ) // préservé
        assertTrue(h.cpu.flagH)
        assertFalse(h.cpu.flagC)
    }

    @Test
    fun `INC rr ne touche aucun drapeau`() {
        val h = CpuHarness().program(0x03) // INC BC
        h.cpu.bc = 0xFFFF
        h.cpu.f = 0x00
        h.run()
        assertEquals(0x0000, h.cpu.bc)
        assertEquals(0x00, h.cpu.f)
    }

    @Test
    fun `DAA apres addition BCD`() {
        // 0x45 + 0x38 = 0x7D → DAA → 0x83.
        val h = CpuHarness().program(0x80, 0x27) // ADD A,B ; DAA
        h.cpu.a = 0x45
        h.cpu.b = 0x38
        h.run(2)
        assertEquals(0x83, h.cpu.a)
        assertFalse(h.cpu.flagC)

        // 0x99 + 0x01 = 0x9A → DAA → 0x00 avec retenue.
        val h2 = CpuHarness().program(0x80, 0x27)
        h2.cpu.a = 0x99
        h2.cpu.b = 0x01
        h2.run(2)
        assertEquals(0x00, h2.cpu.a)
        assertTrue(h2.cpu.flagC)
        assertTrue(h2.cpu.flagZ)
    }

    @Test
    fun `DAA apres soustraction BCD`() {
        // 0x20 - 0x13 = 0x0D → DAA → 0x07.
        val h = CpuHarness().program(0x90, 0x27) // SUB B ; DAA
        h.cpu.a = 0x20
        h.cpu.b = 0x13
        h.run(2)
        assertEquals(0x07, h.cpu.a)
    }

    @Test
    fun `CPL SCF CCF`() {
        val h = CpuHarness().program(0x2F, 0x37, 0x3F) // CPL ; SCF ; CCF
        h.cpu.a = 0xF0
        h.run()
        assertEquals(0x0F, h.cpu.a)
        assertTrue(h.cpu.flagN)
        assertTrue(h.cpu.flagH)
        h.run()
        assertTrue(h.cpu.flagC)
        assertFalse(h.cpu.flagN)
        h.run()
        assertFalse(h.cpu.flagC)
    }

    @Test
    fun `rotations accumulateur mettent Z a zero`() {
        val h = CpuHarness().program(0x07) // RLCA
        h.cpu.a = 0x80
        h.run()
        assertEquals(0x01, h.cpu.a)
        assertTrue(h.cpu.flagC)
        assertFalse(h.cpu.flagZ)

        val h2 = CpuHarness().program(0x1F) // RRA
        h2.cpu.a = 0x01
        h2.cpu.f = 0x00
        h2.run()
        assertEquals(0x00, h2.cpu.a)
        assertTrue(h2.cpu.flagC)
        assertFalse(h2.cpu.flagZ) // RRA ne met jamais Z
    }

    @Test
    fun `ADD SP e8 et LD HL SP e8`() {
        val h = CpuHarness().program(0xE8, 0x02) // ADD SP,+2
        h.cpu.sp = 0xFFF8
        assertEquals(16, h.run())
        assertEquals(0xFFFA, h.cpu.sp)
        assertFalse(h.cpu.flagZ)

        val h2 = CpuHarness().program(0xF8, 0xFE) // LD HL,SP-2
        h2.cpu.sp = 0x0000
        assertEquals(12, h2.run())
        assertEquals(0xFFFE, h2.cpu.hl)
        assertFalse(h2.cpu.flagC)
        assertFalse(h2.cpu.flagH)
    }

    @Test
    fun `LDH et LD via 0xFF00`() {
        val h = CpuHarness().program(
            0xE0, 0x80, // LDH (0x80),A
            0xF0, 0x80, // LDH A,(0x80)
            0xE2, // LD (C),A
        )
        h.cpu.a = 0x5A
        assertEquals(12, h.run())
        assertEquals(0x5A, h.bus.memory[0xFF80])
        h.cpu.a = 0x00
        assertEquals(12, h.run())
        assertEquals(0x5A, h.cpu.a)
        h.cpu.c = 0x81
        assertEquals(8, h.run())
        assertEquals(0x5A, h.bus.memory[0xFF81])
    }

    @Test
    fun `LD a16 SP ecrit les deux octets`() {
        val h = CpuHarness().program(0x08, 0x00, 0xC1) // LD (0xC100),SP
        h.cpu.sp = 0xFFF8
        assertEquals(20, h.run())
        assertEquals(0xF8, h.bus.memory[0xC100])
        assertEquals(0xFF, h.bus.memory[0xC101])
    }

    @Test
    fun `opcode invalide verrouille le CPU`() {
        val h = CpuHarness().program(0xD3, 0x00)
        h.run()
        assertTrue(h.cpu.locked)
        val pcAfter = h.cpu.pc
        h.run()
        assertEquals(pcAfter, h.cpu.pc) // plus aucune progression
    }
}
