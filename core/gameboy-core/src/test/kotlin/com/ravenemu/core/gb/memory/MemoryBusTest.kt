package com.ravenemu.core.gb.memory

import com.ravenemu.core.gb.InterruptController
import com.ravenemu.core.gb.TestRoms
import com.ravenemu.core.gb.cartridge.Cartridge
import com.ravenemu.core.gb.io.Apu
import com.ravenemu.core.gb.io.Joypad
import com.ravenemu.core.gb.io.SerialPort
import com.ravenemu.core.gb.io.Timer
import com.ravenemu.core.gb.ppu.Ppu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryBusTest {

    private class Machine(rom: ByteArray = TestRoms.build(type = 0x03, ramSizeCode = 0x02)) {
        val interrupts = InterruptController()
        val cartridge = Cartridge.create(rom)
        val timer = Timer(interrupts)
        val serial = SerialPort(interrupts)
        val joypad = Joypad(interrupts)
        val ppu = Ppu(interrupts)
        val apu = Apu()
        val bus = MemoryBus(cartridge, ppu, interrupts, timer, serial, joypad, apu)

        init {
            ppu.writeLcdc(0x11) // LCD éteint : accès VRAM/OAM libres en test
        }
    }

    @Test
    fun `rom accessible et ecriture routee vers le mbc`() {
        val m = Machine(
            TestRoms.build(type = 0x03, ramSizeCode = 0x02) { rom -> rom[0x0000] = 0x3C }
        )
        assertEquals(0x3C, m.bus.read(0x0000))
        m.bus.write(0x0000, 0x0A) // activation RAM via le MBC
        m.bus.write(0xA000, 0x42)
        assertEquals(0x42, m.bus.read(0xA000))
    }

    @Test
    fun `wram et son echo`() {
        val m = Machine()
        m.bus.write(0xC123, 0x77)
        assertEquals(0x77, m.bus.read(0xC123))
        assertEquals(0x77, m.bus.read(0xE123)) // écho
        m.bus.write(0xE200, 0x55)
        assertEquals(0x55, m.bus.read(0xC200))
    }

    @Test
    fun `zone interdite inerte`() {
        val m = Machine()
        m.bus.write(0xFEA0, 0x12)
        assertEquals(0x00, m.bus.read(0xFEA0))
    }

    @Test
    fun `hram et ie`() {
        val m = Machine()
        m.bus.write(0xFF80, 0x99)
        assertEquals(0x99, m.bus.read(0xFF80))
        m.bus.write(0xFFFF, 0x1F)
        assertEquals(0x1F, m.bus.read(0xFFFF))
    }

    @Test
    fun `registres io routes`() {
        val m = Machine()
        m.bus.write(0xFF07, 0x05) // TAC
        assertEquals(0xFD, m.bus.read(0xFF07))
        m.bus.write(0xFF42, 0x21) // SCY
        assertEquals(0x21, m.bus.read(0xFF42))
        m.bus.write(0xFF44, 0x50) // LY en lecture seule
        assertEquals(0x00, m.bus.read(0xFF44))
        m.bus.write(0xFF12, 0xF3) // registre APU mémorisé
        assertEquals(0xF3, m.bus.read(0xFF12))
        assertEquals(0xFF, m.bus.read(0xFF4D)) // registre CGB non câblé
    }

    @Test
    fun `oam dma copie 160 octets`() {
        val m = Machine()
        for (i in 0 until 0xA0) m.bus.write(0xC000 + i, i)
        m.bus.write(0xFF46, 0xC0)
        m.bus.tick(MemoryBus.DMA_CYCLES)
        assertEquals(0x00, m.ppu.oam[0].toInt() and 0xFF)
        assertEquals(0x5A, m.ppu.oam[0x5A].toInt() and 0xFF)
        assertEquals(0x9F, m.ppu.oam[0x9F].toInt() and 0xFF)
    }

    @Test
    fun `bus restreint a la hram pendant le dma`() {
        val m = Machine()
        m.bus.write(0xC000, 0x42)
        m.bus.write(0xFF80, 0x24)
        m.bus.write(0xFF46, 0xC0)
        assertTrue(m.bus.dmaActive)
        assertEquals(0xFF, m.bus.read(0xC000)) // bloqué
        assertEquals(0x24, m.bus.read(0xFF80)) // HRAM accessible
        m.bus.write(0xC000, 0x00) // ignoré
        m.bus.tick(MemoryBus.DMA_CYCLES)
        assertFalse(m.bus.dmaActive)
        assertEquals(0x42, m.bus.read(0xC000))
    }

    @Test
    fun `lecture du registre dma`() {
        val m = Machine()
        m.bus.write(0xFF46, 0xC1)
        m.bus.tick(MemoryBus.DMA_CYCLES)
        assertEquals(0xC1, m.bus.read(0xFF46))
    }
}
