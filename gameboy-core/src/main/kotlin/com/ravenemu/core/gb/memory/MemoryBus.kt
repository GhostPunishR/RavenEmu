package com.ravenemu.core.gb.memory

import com.ravenemu.core.gb.InterruptController
import com.ravenemu.core.gb.SpeedController
import com.ravenemu.core.gb.cartridge.Cartridge
import com.ravenemu.core.gb.io.Apu
import com.ravenemu.core.gb.io.Joypad
import com.ravenemu.core.gb.io.SerialPort
import com.ravenemu.core.gb.io.Timer
import com.ravenemu.core.gb.ppu.Ppu

/**
 * Plan mémoire complet 0x0000–0xFFFF, modes DMG et Game Boy Color.
 *
 * DMG : ROM/RAM cartouche, VRAM/OAM (via le PPU), WRAM 8 KiO et son écho,
 * zone interdite, registres d'E/S, HRAM, IE, OAM DMA (0xFF46).
 *
 * CGB : WRAM 32 KiO en 8 banques (SVBK 0xFF70), banque VRAM (VBK 0xFF4F),
 * commutateur de vitesse (KEY1 0xFF4D), palettes couleur (0xFF68–0xFF6B) et
 * transfert VRAM HDMA/GDMA (0xFF51–0xFF55).
 */
class MemoryBus(
    private val cartridge: Cartridge,
    private val ppu: Ppu,
    private val interrupts: InterruptController,
    private val timer: Timer,
    private val serial: SerialPort,
    private val joypad: Joypad,
    private val apu: Apu,
    private val cgbMode: Boolean = false,
    private val speed: SpeedController = SpeedController(false),
) : Bus {

    // WRAM : 8 banques de 4 KiO (2 seulement utilisées en DMG).
    val wram = ByteArray(0x8000)
    val hram = ByteArray(0x7F)
    private var svbk = 1

    private var dmaRegister = 0xFF
    private var dmaRemainingCycles = 0

    // HDMA/GDMA (CGB).
    private var hdmaSource = 0
    private var hdmaDest = 0
    private var hdmaLength = 0 // octets restants (0 si inactif)
    private var hdmaHBlankMode = false
    private var hdmaActive = false

    val dmaActive: Boolean get() = dmaRemainingCycles > 0

    private fun wramBank(): Int = if (cgbMode) (svbk and 0x07).let { if (it == 0) 1 else it } else 1

    fun tick(cycles: Int) {
        if (dmaRemainingCycles > 0) {
            dmaRemainingCycles = maxOf(0, dmaRemainingCycles - cycles)
        }
    }

    /** Transfert HDMA d'un bloc de 16 octets à chaque HBlank (mode CGB). */
    fun notifyHBlank() {
        if (!hdmaActive || !hdmaHBlankMode) return
        transferHdmaBlock()
        if (hdmaLength == 0) hdmaActive = false
    }

    override fun onStop() {
        speed.onStop()
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
        in 0xC000..0xCFFF -> wram[addr - 0xC000].toInt() and 0xFF
        in 0xD000..0xDFFF -> wram[wramBank() * 0x1000 + (addr - 0xD000)].toInt() and 0xFF
        in 0xE000..0xEFFF -> wram[addr - 0xE000].toInt() and 0xFF
        in 0xF000..0xFDFF -> wram[wramBank() * 0x1000 + (addr - 0xF000)].toInt() and 0xFF
        in 0xFE00..0xFE9F -> ppu.readOam(addr)
        in 0xFEA0..0xFEFF -> 0x00
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
        0xFF4D -> if (cgbMode) speed.readKey1() else 0xFF
        0xFF4F -> ppu.readVramBank()
        0xFF51 -> if (cgbMode) (hdmaSource shr 8) and 0xFF else 0xFF
        0xFF52 -> if (cgbMode) hdmaSource and 0xFF else 0xFF
        0xFF53 -> if (cgbMode) (hdmaDest shr 8) and 0xFF else 0xFF
        0xFF54 -> if (cgbMode) hdmaDest and 0xFF else 0xFF
        0xFF55 -> readHdmaStatus()
        0xFF68 -> if (cgbMode) ppu.readBcps() else 0xFF
        0xFF69 -> if (cgbMode) ppu.readBcpd() else 0xFF
        0xFF6A -> if (cgbMode) ppu.readOcps() else 0xFF
        0xFF6B -> if (cgbMode) ppu.readOcpd() else 0xFF
        0xFF70 -> if (cgbMode) (svbk or 0xF8) else 0xFF
        in 0xFF80..0xFFFE -> hram[addr - 0xFF80].toInt() and 0xFF
        0xFFFF -> interrupts.readEnable()
        else -> 0xFF
    }

    private fun writeInternal(addr: Int, v: Int) {
        when (addr) {
            in 0x0000..0x7FFF -> cartridge.writeControl(addr, v)
            in 0x8000..0x9FFF -> ppu.writeVram(addr, v)
            in 0xA000..0xBFFF -> cartridge.writeRam(addr, v)
            in 0xC000..0xCFFF -> wram[addr - 0xC000] = v.toByte()
            in 0xD000..0xDFFF -> wram[wramBank() * 0x1000 + (addr - 0xD000)] = v.toByte()
            in 0xE000..0xEFFF -> wram[addr - 0xE000] = v.toByte()
            in 0xF000..0xFDFF -> wram[wramBank() * 0x1000 + (addr - 0xF000)] = v.toByte()
            in 0xFE00..0xFE9F -> ppu.writeOam(addr, v)
            in 0xFEA0..0xFEFF -> {}
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
            0xFF44 -> {}
            0xFF45 -> ppu.writeLyc(v)
            0xFF46 -> startDma(v)
            0xFF47 -> ppu.bgp = v
            0xFF48 -> ppu.obp0 = v
            0xFF49 -> ppu.obp1 = v
            0xFF4A -> ppu.wy = v
            0xFF4B -> ppu.wx = v
            0xFF4D -> if (cgbMode) speed.writeKey1(v)
            0xFF4F -> ppu.writeVramBank(v)
            0xFF51 -> if (cgbMode) hdmaSource = (hdmaSource and 0x00FF) or (v shl 8)
            0xFF52 -> if (cgbMode) hdmaSource = (hdmaSource and 0xFF00) or (v and 0xF0)
            0xFF53 -> if (cgbMode) hdmaDest = (hdmaDest and 0x00FF) or ((v and 0x1F) shl 8)
            0xFF54 -> if (cgbMode) hdmaDest = (hdmaDest and 0xFF00) or (v and 0xF0)
            0xFF55 -> if (cgbMode) startHdma(v)
            0xFF68 -> if (cgbMode) ppu.writeBcps(v)
            0xFF69 -> if (cgbMode) ppu.writeBcpd(v)
            0xFF6A -> if (cgbMode) ppu.writeOcps(v)
            0xFF6B -> if (cgbMode) ppu.writeOcpd(v)
            0xFF70 -> if (cgbMode) svbk = v and 0x07
            in 0xFF80..0xFFFE -> hram[addr - 0xFF80] = v.toByte()
            0xFFFF -> interrupts.writeEnable(v)
            else -> {}
        }
    }

    private fun startDma(sourceHigh: Int) {
        dmaRegister = sourceHigh
        val base = sourceHigh shl 8
        for (i in 0 until 0xA0) {
            ppu.writeOamDirect(i, readForDma((base + i) and 0xFFFF))
        }
        dmaRemainingCycles = DMA_CYCLES
    }

    private fun readForDma(source: Int): Int = when (source) {
        in 0x0000..0x7FFF -> cartridge.readRom(source)
        in 0x8000..0x9FFF -> ppu.vram[ppu.vramBank * 0x2000 + (source and 0x1FFF)].toInt() and 0xFF
        in 0xA000..0xBFFF -> cartridge.readRam(source)
        in 0xC000..0xCFFF -> wram[source - 0xC000].toInt() and 0xFF
        in 0xD000..0xDFFF -> wram[wramBank() * 0x1000 + (source - 0xD000)].toInt() and 0xFF
        in 0xE000..0xFDFF -> wram[source - 0xE000].toInt() and 0xFF
        else -> 0xFF
    }

    // ---- HDMA / GDMA (CGB) ----

    private fun readHdmaStatus(): Int {
        if (!cgbMode) return 0xFF
        // Bit 7 : 0 = transfert HBlank en cours, 1 = terminé/inactif.
        val remainingBlocks = (hdmaLength / 16) - 1
        return if (hdmaActive) (remainingBlocks and 0x7F) else 0xFF
    }

    private fun startHdma(value: Int) {
        val hblank = (value and 0x80) != 0
        if (hdmaActive && !hblank) {
            // Écriture avec bit7=0 pendant un transfert HBlank : arrêt.
            hdmaActive = false
            return
        }
        hdmaLength = ((value and 0x7F) + 1) * 16
        hdmaHBlankMode = hblank
        if (hblank) {
            hdmaActive = true
        } else {
            // GDMA : transfert immédiat de tout le bloc.
            hdmaActive = true
            while (hdmaLength > 0) transferHdmaBlock()
            hdmaActive = false
        }
    }

    private fun transferHdmaBlock() {
        for (i in 0 until 16) {
            val value = readForHdma(hdmaSource and 0xFFFF)
            ppu.vram[ppu.vramBank * 0x2000 + (hdmaDest and 0x1FFF)] = value.toByte()
            hdmaSource = (hdmaSource + 1) and 0xFFFF
            hdmaDest = (hdmaDest + 1) and 0xFFFF
        }
        hdmaLength -= 16
    }

    private fun readForHdma(source: Int): Int = when (source) {
        in 0x0000..0x7FFF -> cartridge.readRom(source)
        in 0xA000..0xBFFF -> cartridge.readRam(source)
        in 0xC000..0xCFFF -> wram[source - 0xC000].toInt() and 0xFF
        in 0xD000..0xDFFF -> wram[wramBank() * 0x1000 + (source - 0xD000)].toInt() and 0xFF
        else -> 0xFF
    }

    // ---- Sérialisation ----

    fun stateDma(): IntArray = intArrayOf(
        dmaRegister, dmaRemainingCycles, svbk,
        hdmaSource, hdmaDest, hdmaLength,
        if (hdmaHBlankMode) 1 else 0, if (hdmaActive) 1 else 0,
    )

    fun restoreDma(fields: IntArray) {
        dmaRegister = fields[0]
        dmaRemainingCycles = fields[1]
        svbk = fields[2]
        hdmaSource = fields[3]
        hdmaDest = fields[4]
        hdmaLength = fields[5]
        hdmaHBlankMode = fields[6] != 0
        hdmaActive = fields[7] != 0
    }

    companion object {
        const val DMA_CYCLES = 640
    }
}
