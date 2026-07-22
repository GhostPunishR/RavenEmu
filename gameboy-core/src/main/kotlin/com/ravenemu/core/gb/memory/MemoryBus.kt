package com.ravenemu.core.gb.memory

import com.ravenemu.core.gb.InterruptController
import com.ravenemu.core.gb.cartridge.Cartridge
import com.ravenemu.core.gb.io.Apu
import com.ravenemu.core.gb.io.Joypad
import com.ravenemu.core.gb.io.SerialPort
import com.ravenemu.core.gb.io.Timer
import com.ravenemu.core.gb.ppu.Ppu

/**
 * Plan mémoire complet 0x0000–0xFFFF : ROM/RAM cartouche, VRAM et OAM (via le
 * PPU qui applique ses restrictions de mode), WRAM et son écho, zone
 * interdite, registres d'E/S, HRAM, IE. Gère l'OAM DMA : copie de 160 octets
 * déclenchée par 0xFF46, bus restreint à la HRAM pendant les 640 cycles du
 * transfert (les lectures hors HRAM renvoient 0xFF, les écritures sont
 * ignorées), comme sur console.
 */
class MemoryBus(
    private val cartridge: Cartridge,
    private val ppu: Ppu,
    private val interrupts: InterruptController,
    private val timer: Timer,
    private val serial: SerialPort,
    private val joypad: Joypad,
    private val apu: Apu,
) : Bus {

    val wram = ByteArray(0x2000)
    val hram = ByteArray(0x7F)

    private var dmaRegister = 0xFF
    private var dmaRemainingCycles = 0

    val dmaActive: Boolean get() = dmaRemainingCycles > 0

    /** Avance le compte à rebours du DMA. */
    fun tick(cycles: Int) {
        if (dmaRemainingCycles > 0) {
            dmaRemainingCycles = maxOf(0, dmaRemainingCycles - cycles)
        }
    }

    override fun read(address: Int): Int {
        val addr = address and 0xFFFF
        if (dmaActive && !isDmaAccessible(addr)) return 0xFF
        return readInternal(addr)
    }

    override fun write(address: Int, value: Int) {
        val addr = address and 0xFFFF
        val v = value and 0xFF
        if (dmaActive && !isDmaAccessible(addr)) return
        writeInternal(addr, v)
    }

    private fun isDmaAccessible(addr: Int): Boolean =
        addr in 0xFF80..0xFFFE || addr == 0xFF46

    private fun readInternal(addr: Int): Int = when (addr) {
        in 0x0000..0x7FFF -> cartridge.readRom(addr)
        in 0x8000..0x9FFF -> ppu.readVram(addr)
        in 0xA000..0xBFFF -> cartridge.readRam(addr)
        in 0xC000..0xDFFF -> wram[addr - 0xC000].toInt() and 0xFF
        in 0xE000..0xFDFF -> wram[addr - 0xE000].toInt() and 0xFF // écho
        in 0xFE00..0xFE9F -> ppu.readOam(addr)
        in 0xFEA0..0xFEFF -> 0x00 // zone interdite
        0xFF00 -> joypad.read()
        0xFF01 -> serial.readData()
        0xFF02 -> serial.readControl()
        0xFF04 -> timer.readDiv()
        0xFF05 -> timer.readTima()
        0xFF06 -> timer.tma
        0xFF07 -> timer.readTac()
        0xFF0F -> interrupts.readFlags()
        in 0xFF10..0xFF3F -> apu.read(addr)
        0xFF40 -> ppu.lcdc
        0xFF41 -> ppu.readStat()
        0xFF42 -> ppu.scy
        0xFF43 -> ppu.scx
        0xFF44 -> ppu.ly
        0xFF45 -> ppu.lyc
        0xFF46 -> dmaRegister
        0xFF47 -> ppu.bgp
        0xFF48 -> ppu.obp0
        0xFF49 -> ppu.obp1
        0xFF4A -> ppu.wy
        0xFF4B -> ppu.wx
        in 0xFF80..0xFFFE -> hram[addr - 0xFF80].toInt() and 0xFF
        0xFFFF -> interrupts.readEnable()
        else -> 0xFF // registre non câblé (dont registres CGB)
    }

    private fun writeInternal(addr: Int, v: Int) {
        when (addr) {
            in 0x0000..0x7FFF -> cartridge.writeControl(addr, v)
            in 0x8000..0x9FFF -> ppu.writeVram(addr, v)
            in 0xA000..0xBFFF -> cartridge.writeRam(addr, v)
            in 0xC000..0xDFFF -> wram[addr - 0xC000] = v.toByte()
            in 0xE000..0xFDFF -> wram[addr - 0xE000] = v.toByte()
            in 0xFE00..0xFE9F -> ppu.writeOam(addr, v)
            in 0xFEA0..0xFEFF -> {} // zone interdite
            0xFF00 -> joypad.write(v)
            0xFF01 -> serial.writeData(v)
            0xFF02 -> serial.writeControl(v)
            0xFF04 -> timer.writeDiv()
            0xFF05 -> timer.writeTima(v)
            0xFF06 -> timer.tma = v
            0xFF07 -> timer.writeTac(v)
            0xFF0F -> interrupts.writeFlags(v)
            in 0xFF10..0xFF3F -> apu.write(addr, v)
            0xFF40 -> ppu.writeLcdc(v)
            0xFF41 -> ppu.writeStat(v)
            0xFF42 -> ppu.scy = v
            0xFF43 -> ppu.scx = v
            0xFF44 -> {} // LY en lecture seule
            0xFF45 -> ppu.writeLyc(v)
            0xFF46 -> startDma(v)
            0xFF47 -> ppu.bgp = v
            0xFF48 -> ppu.obp0 = v
            0xFF49 -> ppu.obp1 = v
            0xFF4A -> ppu.wy = v
            0xFF4B -> ppu.wx = v
            in 0xFF80..0xFFFE -> hram[addr - 0xFF80] = v.toByte()
            0xFFFF -> interrupts.writeEnable(v)
            else -> {} // registre non câblé
        }
    }

    /**
     * OAM DMA : la copie est effectuée immédiatement (l'OAM n'est de toute
     * façon inaccessible au CPU pendant le transfert), le verrouillage du bus
     * dure les 640 cycles réglementaires.
     */
    private fun startDma(sourceHigh: Int) {
        dmaRegister = sourceHigh
        val base = sourceHigh shl 8
        for (i in 0 until 0xA0) {
            val source = (base + i) and 0xFFFF
            val value = when (source) {
                in 0x0000..0x7FFF -> cartridge.readRom(source)
                in 0x8000..0x9FFF -> ppu.vram[source and 0x1FFF].toInt() and 0xFF
                in 0xA000..0xBFFF -> cartridge.readRam(source)
                in 0xC000..0xDFFF -> wram[source - 0xC000].toInt() and 0xFF
                in 0xE000..0xFDFF -> wram[source - 0xE000].toInt() and 0xFF
                else -> 0xFF
            }
            ppu.writeOamDirect(i, value)
        }
        dmaRemainingCycles = DMA_CYCLES
    }

    // ---- Sérialisation ----

    fun stateDma(): IntArray = intArrayOf(dmaRegister, dmaRemainingCycles)

    fun restoreDma(fields: IntArray) {
        dmaRegister = fields[0]
        dmaRemainingCycles = fields[1]
    }

    companion object {
        /** 160 octets copiés en 160 M-cycles, soit 640 T-cycles. */
        const val DMA_CYCLES = 640
    }
}
