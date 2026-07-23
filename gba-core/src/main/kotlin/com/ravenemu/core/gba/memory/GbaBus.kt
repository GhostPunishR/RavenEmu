package com.ravenemu.core.gba.memory

import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.dma.DmaController
import com.ravenemu.core.gba.input.GbaKeypad
import com.ravenemu.core.gba.interrupt.GbaInterruptController
import com.ravenemu.core.gba.ppu.GbaPpu
import com.ravenemu.core.gba.timer.GbaTimers

/**
 * Bus mémoire minimal de la Game Boy Advance : achemine les accès 8, 16 et
 * 32 bits vers la bonne région du plan mémoire, en gérant l'alignement et les
 * zones miroir.
 *
 * Périmètre du premier lot : BIOS (interne, nul par défaut, voir HLE ultérieur),
 * EWRAM, IWRAM, registres d'E/S (stockage brut), palette, VRAM, OAM, ROM
 * cartouche et SRAM. Le registre clavier `KEYINPUT` reflète l'état du
 * [keypad] ; les autres registres d'E/S ne déclenchent encore aucun effet de
 * bord (DMA, timers, PPU…) : ils sont conservés tels quels et lus tels quels.
 * La rotation des lectures 32/16 bits non alignées est appliquée par le CPU,
 * qui aligne les adresses avant d'appeler ce bus.
 */
class GbaBus(
    private val cartridge: GbaCartridge,
    val keypad: GbaKeypad = GbaKeypad(),
) {

    /** BIOS interne : nul dans le premier lot (HLE des appels logiciels à venir). */
    val bios = ByteArray(MemoryRegion.BIOS.size)
    val ewram = ByteArray(MemoryRegion.EWRAM.size)
    val iwram = ByteArray(MemoryRegion.IWRAM.size)
    val io = ByteArray(MemoryRegion.IO.size)
    val paletteRam = ByteArray(MemoryRegion.PALETTE.size)
    val vram = ByteArray(MemoryRegion.VRAM.size)
    val oam = ByteArray(MemoryRegion.OAM.size)
    val sram = ByteArray(MemoryRegion.SRAM.size)

    /** Unité graphique, rattachée après construction (registres DISPSTAT/VCOUNT). */
    var ppu: GbaPpu? = null

    /** Contrôleur d'interruptions (registres IE/IF/IME), rattaché après construction. */
    var interrupts: GbaInterruptController? = null

    /** Timers (registres TMxCNT), rattachés après construction. */
    var timers: GbaTimers? = null

    /** Canaux DMA (registres DMAxCNT), rattachés après construction. */
    var dma: DmaController? = null

    /** Replie une adresse VRAM (96 Kio) : blocs de 128 Kio dont les 32 derniers Kio recopient les précédents. */
    private fun vramOffset(address: Int): Int {
        var offset = address and 0x1_FFFF
        if (offset >= 0x1_8000) offset -= 0x8000
        return offset
    }

    private fun romOffset(address: Int): Int = address and 0x01FF_FFFF

    /** Lecture d'un octet d'E/S ; certains registres reflètent l'état matériel. */
    private fun readIo(offset: Int): Int {
        val ic = interrupts
        val tm = timers
        return when {
            offset == KEYINPUT_LOW -> keypad.keyInput() and 0xFF
            offset == KEYINPUT_HIGH -> (keypad.keyInput() ushr 8) and 0xFF
            offset == DISPSTAT_LOW -> ppu?.dispStatLowByte() ?: (io[offset].toInt() and 0xFF)
            offset == VCOUNT_LOW -> ppu?.vcount ?: (io[offset].toInt() and 0xFF)
            offset == VCOUNT_HIGH -> 0 // VCOUNT < 228 : octet haut nul
            tm != null && offset in 0x100..0x10F -> readTimerByte(tm, offset)
            ic != null && offset == IE_LOW -> ic.enable and 0xFF
            ic != null && offset == IE_HIGH -> (ic.enable ushr 8) and 0xFF
            ic != null && offset == IF_LOW -> ic.flags and 0xFF
            ic != null && offset == IF_HIGH -> (ic.flags ushr 8) and 0xFF
            ic != null && offset == IME -> if (ic.masterEnable) 1 else 0
            ic != null && offset == IME + 1 -> 0
            else -> io[offset].toInt() and 0xFF
        }
    }

    /** Lecture d'un octet d'un registre de timer (compteur en 0/1, contrôle en 2/3). */
    private fun readTimerByte(tm: GbaTimers, offset: Int): Int {
        val timer = (offset - 0x100) / 4
        return when ((offset - 0x100) % 4) {
            0 -> tm.counter(timer) and 0xFF
            1 -> (tm.counter(timer) ushr 8) and 0xFF
            2 -> tm.control(timer) and 0xFF
            else -> (tm.control(timer) ushr 8) and 0xFF
        }
    }

    /**
     * Effets de bord des écritures 16 bits vers les registres de contrôle
     * (interruptions, timers, DMA). Les octets ont déjà été stockés dans [io].
     */
    private fun handleIoWrite(offset: Int, value: Int) {
        when (offset) {
            IE_LOW -> interrupts?.enable = value
            IF_LOW -> interrupts?.acknowledge(value)
            IME -> interrupts?.masterEnable = value and 1 != 0
            0x100, 0x104, 0x108, 0x10C -> timers?.onReloadWrite((offset - 0x100) / 4, value)
            0x102, 0x106, 0x10A, 0x10E -> timers?.onControlWrite((offset - 0x100) / 4, value)
            0x0BA -> dma?.onControlWrite(0, value)
            0x0C6 -> dma?.onControlWrite(1, value)
            0x0D2 -> dma?.onControlWrite(2, value)
            0x0DE -> dma?.onControlWrite(3, value)
        }
    }

    // ---- Lectures ----

    fun read8(address: Int): Int {
        val region = MemoryRegion.of(address) ?: return 0
        return when (region) {
            MemoryRegion.BIOS -> bios[address and region.mirrorMask].toInt() and 0xFF
            MemoryRegion.EWRAM -> ewram[address and region.mirrorMask].toInt() and 0xFF
            MemoryRegion.IWRAM -> iwram[address and region.mirrorMask].toInt() and 0xFF
            MemoryRegion.IO -> readIo(address and region.mirrorMask)
            MemoryRegion.PALETTE -> paletteRam[address and region.mirrorMask].toInt() and 0xFF
            MemoryRegion.VRAM -> vram[vramOffset(address)].toInt() and 0xFF
            MemoryRegion.OAM -> oam[address and region.mirrorMask].toInt() and 0xFF
            MemoryRegion.ROM -> cartridge.read8(romOffset(address))
            MemoryRegion.SRAM -> sram[address and region.mirrorMask].toInt() and 0xFF
        }
    }

    fun read16(address: Int): Int {
        val a = address and 0x1.inv() // alignement demi-mot
        return read8(a) or (read8(a + 1) shl 8)
    }

    fun read32(address: Int): Int {
        val a = address and 0x3.inv() // alignement mot
        return read8(a) or
            (read8(a + 1) shl 8) or
            (read8(a + 2) shl 16) or
            (read8(a + 3) shl 24)
    }

    // ---- Écritures ----

    /** Écrit un octet dans une région, sans le comportement de duplication 8 bits. */
    private fun writeByteRaw(region: MemoryRegion, address: Int, value: Byte) {
        when (region) {
            MemoryRegion.BIOS, MemoryRegion.ROM -> Unit // lecture seule
            MemoryRegion.EWRAM -> ewram[address and region.mirrorMask] = value
            MemoryRegion.IWRAM -> iwram[address and region.mirrorMask] = value
            MemoryRegion.IO -> io[address and region.mirrorMask] = value
            MemoryRegion.SRAM -> sram[address and region.mirrorMask] = value
            MemoryRegion.PALETTE -> paletteRam[address and region.mirrorMask] = value
            MemoryRegion.VRAM -> vram[vramOffset(address)] = value
            MemoryRegion.OAM -> oam[address and region.mirrorMask] = value
        }
    }

    fun write8(address: Int, value: Int) {
        val region = MemoryRegion.of(address) ?: return
        val v = (value and 0xFF).toByte()
        when (region) {
            // Un octet écrit dans la palette ou la VRAM est dupliqué sur les
            // deux octets du demi-mot adressé (comportement matériel).
            MemoryRegion.PALETTE -> {
                val base = (address and region.mirrorMask) and 0x1.inv()
                paletteRam[base] = v
                paletteRam[base + 1] = v
            }
            MemoryRegion.VRAM -> {
                val base = vramOffset(address) and 0x1.inv()
                vram[base] = v
                vram[base + 1] = v
            }
            // Les écritures 8 bits vers l'OAM sont ignorées par le matériel.
            MemoryRegion.OAM -> Unit
            else -> writeByteRaw(region, address, v)
        }
    }

    fun write16(address: Int, value: Int) {
        val a = address and 0x1.inv()
        val region = MemoryRegion.of(a) ?: return
        writeByteRaw(region, a, (value and 0xFF).toByte())
        writeByteRaw(region, a + 1, ((value ushr 8) and 0xFF).toByte())
        if (region == MemoryRegion.IO) handleIoWrite(a and region.mirrorMask, value and 0xFFFF)
    }

    fun write32(address: Int, value: Int) {
        val a = address and 0x3.inv()
        write16(a, value and 0xFFFF)
        write16(a + 2, (value ushr 16) and 0xFFFF)
    }

    private companion object {
        // Offsets de registres dans la région d'E/S (base 0x0400_0000).
        const val DISPSTAT_LOW = 0x004
        const val VCOUNT_LOW = 0x006
        const val VCOUNT_HIGH = 0x007
        const val KEYINPUT_LOW = 0x130
        const val KEYINPUT_HIGH = 0x131
        const val IE_LOW = 0x200
        const val IE_HIGH = 0x201
        const val IF_LOW = 0x202
        const val IF_HIGH = 0x203
        const val IME = 0x208
    }
}
