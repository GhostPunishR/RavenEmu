package com.ravenemu.core.gb.cpu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuCbTest {

    private fun cb(op: Int) = CpuHarness().program(0xCB, op)

    @Test
    fun `RLC registre`() {
        val h = cb(0x00) // RLC B
        h.cpu.b = 0x85
        assertEquals(8, h.run())
        assertEquals(0x0B, h.cpu.b)
        assertTrue(h.cpu.flagC)
        assertFalse(h.cpu.flagZ)
    }

    @Test
    fun `RRC registre`() {
        val h = cb(0x09) // RRC C
        h.cpu.c = 0x01
        h.run()
        assertEquals(0x80, h.cpu.c)
        assertTrue(h.cpu.flagC)
    }

    @Test
    fun `RL et RR propagent la retenue`() {
        val h = cb(0x12) // RL D
        h.cpu.d = 0x80
        h.cpu.f = 0x00
        h.run()
        assertEquals(0x00, h.cpu.d)
        assertTrue(h.cpu.flagZ)
        assertTrue(h.cpu.flagC)

        val h2 = cb(0x1B) // RR E
        h2.cpu.e = 0x00
        h2.cpu.f = Cpu.FLAG_C
        h2.run()
        assertEquals(0x80, h2.cpu.e)
        assertFalse(h2.cpu.flagC)
    }

    @Test
    fun `SLA SRA SRL`() {
        val h = cb(0x24) // SLA H
        h.cpu.h = 0xC0
        h.run()
        assertEquals(0x80, h.cpu.h)
        assertTrue(h.cpu.flagC)

        val h2 = cb(0x2D) // SRA L
        h2.cpu.l = 0x81
        h2.run()
        assertEquals(0xC0, h2.cpu.l) // bit de signe conservé
        assertTrue(h2.cpu.flagC)

        val h3 = cb(0x3F) // SRL A
        h3.cpu.a = 0x01
        h3.run()
        assertEquals(0x00, h3.cpu.a)
        assertTrue(h3.cpu.flagZ)
        assertTrue(h3.cpu.flagC)
    }

    @Test
    fun `SWAP echange les quartets`() {
        val h = cb(0x37) // SWAP A
        h.cpu.a = 0xF1
        h.run()
        assertEquals(0x1F, h.cpu.a)
        assertFalse(h.cpu.flagC)
    }

    @Test
    fun `BIT positionne Z sans modifier la valeur`() {
        val h = cb(0x7F) // BIT 7,A
        h.cpu.a = 0x80
        h.cpu.f = Cpu.FLAG_C
        assertEquals(8, h.run())
        assertFalse(h.cpu.flagZ)
        assertTrue(h.cpu.flagH)
        assertTrue(h.cpu.flagC) // C préservé
        assertEquals(0x80, h.cpu.a)

        val h2 = cb(0x40) // BIT 0,B
        h2.cpu.b = 0x00
        h2.run()
        assertTrue(h2.cpu.flagZ)
    }

    @Test
    fun `RES et SET manipulent le bon bit`() {
        val h = cb(0xBF) // RES 7,A
        h.cpu.a = 0xFF
        h.run()
        assertEquals(0x7F, h.cpu.a)

        val h2 = cb(0xC0) // SET 0,B
        h2.cpu.b = 0x00
        h2.run()
        assertEquals(0x01, h2.cpu.b)
    }

    @Test
    fun `operations CB sur memoire HL`() {
        val h = cb(0x06) // RLC (HL)
        h.cpu.hl = 0xC000
        h.bus.memory[0xC000] = 0x81
        assertEquals(16, h.run())
        assertEquals(0x03, h.bus.memory[0xC000])
        assertTrue(h.cpu.flagC)

        val h2 = cb(0x46) // BIT 0,(HL)
        h2.cpu.hl = 0xC000
        h2.bus.memory[0xC000] = 0x01
        assertEquals(12, h2.run())
        assertFalse(h2.cpu.flagZ)

        val h3 = cb(0xC6) // SET 0,(HL)
        h3.cpu.hl = 0xC000
        assertEquals(16, h3.run())
        assertEquals(0x01, h3.bus.memory[0xC000])
    }
}
