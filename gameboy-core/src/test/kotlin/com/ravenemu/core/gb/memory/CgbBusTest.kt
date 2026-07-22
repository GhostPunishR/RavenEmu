package com.ravenemu.core.gb.memory

import com.ravenemu.core.gb.InterruptController
import com.ravenemu.core.gb.SpeedController
import com.ravenemu.core.gb.TestRoms
import com.ravenemu.core.gb.cartridge.Cartridge
import com.ravenemu.core.gb.io.Apu
import com.ravenemu.core.gb.io.Joypad
import com.ravenemu.core.gb.io.SerialPort
import com.ravenemu.core.gb.io.Timer
import com.ravenemu.core.gb.ppu.Ppu
import kotlin.test.Test
import kotlin.test.assertEquals

class CgbBusTest {

    /** Machine minimale en mode CGB (LCD éteint pour un accès VRAM libre). */
    private class Machine {
        val interrupts = InterruptController()
        val cartridge = Cartridge.create(
            TestRoms.build(type = 0x1B, ramSizeCode = 0x02, cgbFlag = 0x80)
        )
        val timer = Timer(interrupts)
        val serial = SerialPort(interrupts)
        val joypad = Joypad(interrupts)
        val speed = SpeedController(cgbMode = true)
        val ppu = Ppu(interrupts, cgbMode = true)
        val apu = Apu()
        val bus = MemoryBus(
            cartridge, ppu, interrupts, timer, serial, joypad, apu,
            cgbMode = true, speed = speed,
        )

        init {
            ppu.writeLcdc(0x11) // LCD éteint
        }
    }

    @Test
    fun `banques de WRAM commutees par SVBK`() {
        val m = Machine()
        for (bank in 1..7) {
            m.bus.write(0xFF70, bank)
            m.bus.write(0xD000, 0x10 + bank)
        }
        for (bank in 1..7) {
            m.bus.write(0xFF70, bank)
            assertEquals(0x10 + bank, m.bus.read(0xD000), "banque WRAM $bank")
        }
        // C000-CFFF reste la banque 0, quelle que soit SVBK.
        m.bus.write(0xFF70, 5)
        m.bus.write(0xC000, 0x99)
        m.bus.write(0xFF70, 2)
        assertEquals(0x99, m.bus.read(0xC000))
        // SVBK = 0 est lu comme la banque 1.
        m.bus.write(0xFF70, 0)
        m.bus.write(0xD000, 0x55)
        m.bus.write(0xFF70, 1)
        assertEquals(0x55, m.bus.read(0xD000))
    }

    @Test
    fun `banques de VRAM commutees par VBK`() {
        val m = Machine()
        m.bus.write(0xFF4F, 1)
        m.bus.write(0x8000, 0xAA)
        m.bus.write(0xFF4F, 0)
        m.bus.write(0x8000, 0xBB)
        assertEquals(0xBB, m.bus.read(0x8000))
        m.bus.write(0xFF4F, 1)
        assertEquals(0xAA, m.bus.read(0x8000))
        assertEquals(0xFE, m.bus.read(0xFF4F) and 0xFE) // bits hauts à 1
    }

    @Test
    fun `palette de fond avec auto-increment`() {
        val m = Machine()
        m.bus.write(0xFF68, 0x80) // index 0, auto-incrément
        val bytes = intArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88)
        for (b in bytes) m.bus.write(0xFF69, b)
        // Relecture depuis l'index 0.
        m.bus.write(0xFF68, 0x00)
        for (b in bytes) {
            assertEquals(b, m.bus.read(0xFF69))
            // L'auto-incrément n'est pas actif en lecture ; on avance à la main.
            m.bus.write(0xFF68, (m.bus.read(0xFF68) and 0x3F) + 1)
        }
    }

    @Test
    fun `GDMA transfere immediatement vers la VRAM`() {
        val m = Machine()
        for (i in 0 until 16) m.bus.write(0xC000 + i, 0xA0 + i)
        m.bus.write(0xFF51, 0xC0) // source 0xC000
        m.bus.write(0xFF52, 0x00)
        m.bus.write(0xFF53, 0x00) // dest VRAM offset 0
        m.bus.write(0xFF54, 0x00)
        m.bus.write(0xFF55, 0x00) // mode général, 16 octets
        m.bus.write(0xFF4F, 0)
        for (i in 0 until 16) {
            assertEquals(0xA0 + i, m.bus.read(0x8000 + i), "octet $i")
        }
        assertEquals(0xFF, m.bus.read(0xFF55)) // terminé
    }

    @Test
    fun `HDMA transfere 16 octets par HBlank`() {
        val m = Machine()
        for (i in 0 until 32) m.bus.write(0xC000 + i, 0x50 + i)
        m.bus.write(0xFF51, 0xC0)
        m.bus.write(0xFF52, 0x00)
        m.bus.write(0xFF53, 0x00)
        m.bus.write(0xFF54, 0x00)
        m.bus.write(0xFF55, 0x81) // mode HBlank, 2 blocs (32 octets)

        m.bus.notifyHBlank()
        // 16 octets transférés, un bloc restant.
        m.bus.write(0xFF4F, 0)
        assertEquals(0x50, m.bus.read(0x8000))
        assertEquals(0x5F, m.bus.read(0x800F))
        assertEquals(0x00, m.bus.read(0xFF55) and 0x80) // encore actif

        m.bus.notifyHBlank()
        assertEquals(0x60, m.bus.read(0x8010))
        assertEquals(0x6F, m.bus.read(0x801F))
        assertEquals(0xFF, m.bus.read(0xFF55)) // terminé
    }

    @Test
    fun `registres CGB inertes en mode DMG`() {
        val interrupts = InterruptController()
        val cartridge = Cartridge.create(TestRoms.build(type = 0x00))
        val ppu = Ppu(interrupts, cgbMode = false)
        val bus = MemoryBus(
            cartridge, ppu, interrupts, Timer(interrupts), SerialPort(interrupts),
            Joypad(interrupts), Apu(), cgbMode = false, speed = SpeedController(false),
        )
        ppu.writeLcdc(0x11)
        bus.write(0xFF70, 3) // SVBK ignoré
        bus.write(0xFF4F, 1) // VBK ignoré
        assertEquals(0xFF, bus.read(0xFF70))
        assertEquals(0xFF, bus.read(0xFF4F))
        assertEquals(0xFF, bus.read(0xFF4D)) // KEY1
    }
}
