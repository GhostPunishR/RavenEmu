package com.ravenemu.core.gba.memory

import com.ravenemu.core.gba.audio.GbaApu
import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.dma.DmaController
import com.ravenemu.core.gba.input.GbaKeypad
import com.ravenemu.core.gba.interrupt.GbaInterruptController
import com.ravenemu.core.gba.ppu.GbaPpu
import com.ravenemu.core.gba.save.GbaSaveMemory
import com.ravenemu.core.gba.timer.GbaTimers

/**
 * Bus mémoire de la Game Boy Advance : achemine les accès 8, 16 et 32 bits vers
 * la bonne région du plan mémoire (BIOS, EWRAM, IWRAM, E/S, palette, VRAM, OAM,
 * ROM cartouche et zone de sauvegarde), en gérant l'alignement et les zones
 * miroir. La rotation des lectures non alignées est appliquée par le CPU, qui
 * aligne les adresses avant d'appeler ce bus.
 *
 * Les registres d'E/S ne sont pas de simples octets : les lectures reflètent
 * l'état du matériel (`KEYINPUT`, `DISPSTAT`, `VCOUNT`, compteurs de timers,
 * `IE`/`IF`/`IME`) et les écritures 16 bits déclenchent les effets de bord
 * correspondants (interruptions, timers, DMA, audio).
 *
 * La zone de sauvegarde est routée vers la mémoire de la cartouche : SRAM et
 * Flash par accès octet, EEPROM par son protocole série — ce dernier est traité
 * au niveau des accès **16 bits**, car un accès y transporte un seul bit et ne
 * doit donc jamais être décomposé en deux octets.
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

    /** Unité audio (registres 0x60–0xA7), rattachée après construction. */
    var apu: GbaApu? = null

    /** Replie une adresse VRAM (96 Kio) : blocs de 128 Kio dont les 32 derniers Kio recopient les précédents. */
    private fun vramOffset(address: Int): Int {
        var offset = address and 0x1_FFFF
        if (offset >= 0x1_8000) offset -= 0x8000
        return offset
    }

    private fun romOffset(address: Int): Int = address and 0x01FF_FFFF

    /** Mémoire EEPROM de la cartouche, ou `null` si elle est d'un autre type. */
    fun eeprom(): GbaSaveMemory.Eeprom? = cartridge.save as? GbaSaveMemory.Eeprom

    /** Mémoire de sauvegarde de la cartouche, tous types confondus. */
    fun saveMemory(): GbaSaveMemory? = cartridge.save

    /** `true` si l'adresse tombe dans la fenêtre EEPROM (0x0D00_0000-0x0DFF_FFFF). */
    private fun isEepromWindow(address: Int): Boolean =
        cartridge.save is GbaSaveMemory.Eeprom && (address ushr 24) == 0x0D

    /** Lecture dans l'espace cartouche (ROM). */
    private fun readRomRegion(address: Int): Int = cartridge.read8(romOffset(address))

    /**
     * L'espace ROM est en lecture seule ; la fenêtre EEPROM est traitée au
     * niveau des accès 16 bits, seuls utilisés par son protocole série.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun writeRomRegion(address: Int, value: Int) = Unit

    /** Lecture de la zone de sauvegarde (SRAM ou Flash selon la cartouche). */
    private fun readSaveRegion(address: Int): Int = when (val save = cartridge.save) {
        is GbaSaveMemory.Sram -> save.read(address)
        is GbaSaveMemory.Flash -> save.read(address)
        // Sans mémoire déclarée, la zone se comporte comme une RAM ordinaire.
        else -> sram[address and MemoryRegion.SRAM.mirrorMask].toInt() and 0xFF
    }

    private fun writeSaveRegion(address: Int, value: Int) {
        when (val save = cartridge.save) {
            is GbaSaveMemory.Sram -> save.write(address, value)
            is GbaSaveMemory.Flash -> save.write(address, value)
            else -> sram[address and MemoryRegion.SRAM.mirrorMask] = value.toByte()
        }
    }

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
     * Effets de bord d'une écriture vers un registre de contrôle 16 bits
     * (interruptions, timers, DMA, audio). Les octets ont déjà été stockés dans
     * [io] : [value] est la valeur complète du registre après écriture.
     *
     * L'affichage (`DISPCNT`, `DISPSTAT`, fenêtres, mélange…) n'apparaît pas
     * ici : le PPU lit ces registres directement dans [io], donc une écriture
     * de n'importe quelle largeur prend effet sans notification.
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
            in 0x060..0x09F -> apu?.writeRegister(offset, value)
        }
    }

    /** Valeur courante d'un registre d'E/S 16 bits, lue dans [io]. */
    private fun ioRegister(offset: Int): Int =
        (io[offset].toInt() and 0xFF) or ((io[offset + 1].toInt() and 0xFF) shl 8)

    /** `true` si l'offset désigne une file d'échantillons Direct Sound. */
    private fun isFifo(offset: Int): Boolean = offset in FIFO_A_START..FIFO_B_END

    /** Canal Direct Sound visé par un offset de FIFO (`0` = A, `1` = B). */
    private fun fifoChannel(offset: Int): Int = if (offset < FIFO_B_START) 0 else 1

    /**
     * Propage une écriture d'E/S de [byteCount] octets à partir de [offset] :
     * chaque registre 16 bits couvert est notifié, du plus bas au plus haut.
     * Les FIFO reçoivent exactement les octets écrits, sans arrondi de largeur.
     */
    private fun propagateIoWrite(offset: Int, value: Int, byteCount: Int) {
        if (isFifo(offset)) {
            apu?.pushFifo(fifoChannel(offset), value, byteCount)
            return
        }
        var register = offset and 0x1.inv()
        val end = offset + byteCount
        while (register < end) {
            handleIoWrite(register, ioRegister(register))
            register += 2
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
            MemoryRegion.ROM -> readRomRegion(address)
            MemoryRegion.SRAM -> readSaveRegion(address)
        }
    }

    fun read16(address: Int): Int {
        val a = address and 0x1.inv() // alignement demi-mot
        // Le port EEPROM est sériel : un accès 16 bits transporte un seul bit,
        // il ne doit donc pas être décomposé en deux lectures d'octet.
        val eeprom = eeprom()
        if (eeprom != null && isEepromWindow(a)) return eeprom.read()
        // Chemin rapide : un seul aiguillage de région puis deux accès tableau,
        // au lieu de deux appels complets à read8.
        val block = directBlock(a)
        if (block != null) {
            val i = directOffset(a)
            return (block[i].toInt() and 0xFF) or ((block[i + 1].toInt() and 0xFF) shl 8)
        }
        return read8(a) or (read8(a + 1) shl 8)
    }

    fun read32(address: Int): Int {
        val a = address and 0x3.inv() // alignement mot
        // Chemin rapide : décisif pour la vitesse, le CPU lisant ici chaque
        // instruction et chaque mot de données.
        val block = directBlock(a)
        if (block != null) {
            val i = directOffset(a)
            return (block[i].toInt() and 0xFF) or
                ((block[i + 1].toInt() and 0xFF) shl 8) or
                ((block[i + 2].toInt() and 0xFF) shl 16) or
                ((block[i + 3].toInt() and 0xFF) shl 24)
        }
        return read8(a) or
            (read8(a + 1) shl 8) or
            (read8(a + 2) shl 16) or
            (read8(a + 3) shl 24)
    }

    /**
     * Tableau servant directement un accès multi-octets aligné, ou `null` quand
     * la région exige un traitement particulier (E/S, sauvegarde, bord de zone).
     * Les régions retournées ici se lisent et s'écrivent sans effet de bord.
     */
    private fun directBlock(address: Int): ByteArray? = when ((address ushr 24) and 0xFF) {
        0x02 -> ewram
        0x03 -> iwram
        0x05 -> paletteRam
        0x06 -> if ((address and 0x1_FFFF) < 0x1_8000 - 4) vram else null
        0x07 -> oam
        in 0x08..0x0C -> romBlockFor(address)
        else -> null
    }

    private fun directOffset(address: Int): Int = when ((address ushr 24) and 0xFF) {
        0x02 -> address and MemoryRegion.EWRAM.mirrorMask
        0x03 -> address and MemoryRegion.IWRAM.mirrorMask
        0x05 -> address and MemoryRegion.PALETTE.mirrorMask
        0x06 -> vramOffset(address)
        0x07 -> address and MemoryRegion.OAM.mirrorMask
        else -> romOffset(address)
    }

    /** ROM cartouche : servie directement si l'accès tient dans ses bornes. */
    private fun romBlockFor(address: Int): ByteArray? {
        val offset = romOffset(address)
        return if (offset >= 0 && offset + 4 <= cartridge.rom.size) cartridge.rom else null
    }

    // ---- Écritures ----

    /** Écrit un octet dans une région, sans le comportement de duplication 8 bits. */
    private fun writeByteRaw(region: MemoryRegion, address: Int, value: Byte) {
        when (region) {
            MemoryRegion.BIOS -> Unit // lecture seule
            MemoryRegion.ROM -> writeRomRegion(address, value.toInt() and 0xFF)
            MemoryRegion.EWRAM -> ewram[address and region.mirrorMask] = value
            MemoryRegion.IWRAM -> iwram[address and region.mirrorMask] = value
            MemoryRegion.IO -> io[address and region.mirrorMask] = value
            MemoryRegion.SRAM -> writeSaveRegion(address, value.toInt() and 0xFF)
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
            MemoryRegion.IO -> {
                val offset = address and region.mirrorMask
                io[offset] = v
                // Une écriture d'octet doit agir comme le matériel : un STRB sur
                // SOUNDCNT_X allume ou éteint réellement l'APU.
                propagateIoWrite(offset, value and 0xFF, byteCount = 1)
            }
            else -> writeByteRaw(region, address, v)
        }
    }

    fun write16(address: Int, value: Int) {
        val a = address and 0x1.inv()
        val eeprom = eeprom()
        if (eeprom != null && isEepromWindow(a)) {
            eeprom.write(value)
            return
        }
        val region = MemoryRegion.of(a) ?: return
        writeByteRaw(region, a, (value and 0xFF).toByte())
        writeByteRaw(region, a + 1, ((value ushr 8) and 0xFF).toByte())
        if (region == MemoryRegion.IO) {
            propagateIoWrite(a and region.mirrorMask, value and 0xFFFF, byteCount = 2)
        }
    }

    fun write32(address: Int, value: Int) {
        val a = address and 0x3.inv()
        if ((a ushr 24) and 0xFF == 0x04) {
            // E/S : les quatre octets sont stockés, puis les deux registres
            // 16 bits couverts sont notifiés dans l'ordre croissant.
            val offset = a and MemoryRegion.IO.mirrorMask
            for (i in 0 until 4) io[offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
            propagateIoWrite(offset, value, byteCount = 4)
            return
        }
        // Chemin rapide symétrique de read32, hors régions à effet de bord et
        // hors ROM (en lecture seule).
        val block = if ((a ushr 24) and 0xFF in 0x08..0x0C) null else directBlock(a)
        if (block != null) {
            val i = directOffset(a)
            block[i] = (value and 0xFF).toByte()
            block[i + 1] = ((value ushr 8) and 0xFF).toByte()
            block[i + 2] = ((value ushr 16) and 0xFF).toByte()
            block[i + 3] = ((value ushr 24) and 0xFF).toByte()
            return
        }
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

        // Files d'échantillons Direct Sound.
        const val FIFO_A_START = 0x0A0
        const val FIFO_B_START = 0x0A4
        const val FIFO_B_END = 0x0A7
    }
}
