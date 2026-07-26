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

    /** Modèle de temps d'attente des régions mémoire, piloté par `WAITCNT`. */
    val timing = MemoryTiming()

    /**
     * Compteurs et signalements du moteur. Le bus étant accessible depuis le CPU,
     * le BIOS et le DMA, c'est lui qui les porte.
     */
    val diagnostics = com.ravenemu.core.gba.diag.GbaDiagnostics()

    /**
     * Cycles d'attente accumulés par les accès depuis le dernier [takeWaitCycles].
     * Le CPU et le DMA les récupèrent pour facturer leurs accès.
     */
    var waitCycles = 0
        private set

    /**
     * Adresse qu'un accès doit viser pour être qualifié de **séquentiel**, c'est-à-dire
     * la fin de l'accès précédent. `-1` force un accès non séquentiel.
     */
    private var nextSequentialAddress = -1

    /** Prochain accès facturé comme lecture d'instruction (préchargement Game Pak). */
    private var fetchAccess = false

    /** Récupère et remet à zéro les cycles d'attente accumulés. */
    fun takeWaitCycles(): Int {
        val value = waitCycles
        waitCycles = 0
        return value
    }

    /**
     * Marque le prochain accès comme lecture d'instruction : seul ce type d'accès
     * bénéficie de la file de préchargement de la cartouche.
     */
    fun markInstructionFetch() {
        fetchAccess = true
    }

    /** Casse la chaîne séquentielle : saut, changement de maître du bus, réveil. */
    fun breakAccessSequence() {
        nextSequentialAddress = -1
    }

    /** Facture un accès de [width] octets à [address] dans [waitCycles]. */
    private fun account(address: Int, width: Int) {
        val sequential = address == nextSequentialAddress
        waitCycles += if (fetchAccess) {
            timing.instructionWaitStates(address, width, sequential)
        } else {
            timing.waitStates(address, width, sequential)
        }
        fetchAccess = false
        nextSequentialAddress = address + width
    }

    // ---- Cache de lecture d'instruction ----
    //
    // Le processeur relit une instruction à chaque pas, presque toujours dans la
    // même région et à l'adresse suivante. Le chemin générique refaisait pour
    // chacune quatre fois le même aiguillage sur l'octet de poids fort de
    // l'adresse : une fois pour la facturation, une fois pour le temps d'attente,
    // une fois pour trouver le tableau, une fois pour calculer l'index. Une
    // fenêtre mémorisée ramène tout cela à une comparaison de bornes.

    private var codeBlock: ByteArray? = null
    private var codeStart = 0
    private var codeLimit = 0
    private var codeSequentialWait16 = 0
    private var codeNonSequentialWait16 = 0
    private var codeSequentialWait32 = 0
    private var codeNonSequentialWait32 = 0

    /** Invalide la fenêtre de code : `WAITCNT` a changé, ou l'exécution a migré. */
    fun invalidateCodeWindow() {
        codeBlock = null
    }

    /**
     * Lecture d'un demi-mot d'**instruction**. Identique à [read16] du point de
     * vue du programme émulé, mais servie par la fenêtre de code quand elle
     * s'applique.
     */
    fun fetch16(address: Int): Int {
        val block = codeBlock
        if (block != null && address >= codeStart && address + 2 <= codeLimit) {
            waitCycles += if (address == nextSequentialAddress) {
                codeSequentialWait16
            } else {
                codeNonSequentialWait16
            }
            nextSequentialAddress = address + 2
            val i = address - codeStart
            return (block[i].toInt() and 0xFF) or ((block[i + 1].toInt() and 0xFF) shl 8)
        }
        openCodeWindow(address)
        markInstructionFetch()
        return read16(address)
    }

    /** Lecture d'un mot d'**instruction**, symétrique de [fetch16]. */
    fun fetch32(address: Int): Int {
        val block = codeBlock
        if (block != null && address >= codeStart && address + 4 <= codeLimit) {
            waitCycles += if (address == nextSequentialAddress) {
                codeSequentialWait32
            } else {
                codeNonSequentialWait32
            }
            nextSequentialAddress = address + 4
            val i = address - codeStart
            return (block[i].toInt() and 0xFF) or
                ((block[i + 1].toInt() and 0xFF) shl 8) or
                ((block[i + 2].toInt() and 0xFF) shl 16) or
                ((block[i + 3].toInt() and 0xFF) shl 24)
        }
        openCodeWindow(address)
        markInstructionFetch()
        return read32(address)
    }

    /**
     * Ouvre la fenêtre de code contenant [address], si la région s'y prête.
     *
     * Seules l'EWRAM, l'IWRAM et la cartouche sont mises en fenêtre : un jeu n'y
     * exécute pratiquement jamais rien d'autre, et ces trois régions ont des
     * miroirs de taille fixe, donc une fenêtre contiguë bien définie. Les temps
     * d'attente sont mémorisés avec elle, d'où l'invalidation sur `WAITCNT`.
     */
    private fun openCodeWindow(address: Int) {
        codeBlock = null
        when ((address ushr 24) and 0xFF) {
            0x02 -> openWindow(ewram, address, MemoryRegion.EWRAM.mirrorMask)
            0x03 -> openWindow(iwram, address, MemoryRegion.IWRAM.mirrorMask)
            // 0x0D est exclu : c'est la fenêtre du port EEPROM, dont les
            // lectures sont sérielles et ne doivent jamais être servies depuis
            // la ROM. Aucun jeu n'y exécute de code.
            in 0x08..0x0C -> {
                val offset = romOffset(address)
                val rom = cartridge.rom
                if (offset < rom.size) {
                    // La fenêtre couvre la ROM entière depuis sa base ; elle ne
                    // franchit pas de frontière de zone d'attente, les bases
                    // 0x08/0x0A/0x0C étant alignées sur 32 Mio.
                    codeBlock = rom
                    codeStart = address - offset
                    codeLimit = codeStart + rom.size
                    cacheCodeWaitStates(address)
                }
            }
        }
    }

    private fun openWindow(block: ByteArray, address: Int, mirrorMask: Int) {
        codeBlock = block
        codeStart = address and mirrorMask.inv()
        codeLimit = codeStart + block.size
        cacheCodeWaitStates(address)
    }

    private fun cacheCodeWaitStates(address: Int) {
        codeSequentialWait16 = timing.instructionWaitStates(address, 2, sequential = true)
        codeNonSequentialWait16 = timing.instructionWaitStates(address, 2, sequential = false)
        codeSequentialWait32 = timing.instructionWaitStates(address, 4, sequential = true)
        codeNonSequentialWait32 = timing.instructionWaitStates(address, 4, sequential = false)
    }

    /** Réaligne le modèle de temps d'attente sur le contenu courant de [io]. */
    fun syncTimingFromIo() {
        timing.waitControl = ioRegister(MemoryTiming.WAITCNT_OFFSET)
    }

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
            MemoryTiming.WAITCNT_OFFSET -> {
                timing.waitControl = value
                invalidateCodeWindow() // les coûts mémorisés ne valent plus
            }
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

    /**
     * Signale un accès hors du plan mémoire et retourne la valeur lue par le
     * matériel dans ce cas : rien.
     */
    private fun unsupportedAccess(address: Int, write: Boolean): Int {
        diagnostics.report(com.ravenemu.core.gba.diag.GbaDiagnostics.Event.UNSUPPORTED_ACCESS) {
            val kind = if (write) "écriture" else "lecture"
            "$kind hors du plan mémoire à 0x${address.toUInt().toString(16)}"
        }
        return 0
    }

    // ---- Lectures ----

    fun read8(address: Int): Int {
        account(address, 1)
        return read8Raw(address)
    }

    /** Lecture d'un octet **sans facturation** : brique des accès plus larges. */
    private fun read8Raw(address: Int): Int {
        val region = MemoryRegion.of(address) ?: return unsupportedAccess(address, write = false)
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
        account(a, 2)
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
        return read8Raw(a) or (read8Raw(a + 1) shl 8)
    }

    fun read32(address: Int): Int {
        val a = address and 0x3.inv() // alignement mot
        account(a, 4)
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
        return read8Raw(a) or
            (read8Raw(a + 1) shl 8) or
            (read8Raw(a + 2) shl 16) or
            (read8Raw(a + 3) shl 24)
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
        account(address, 1)
        write8Raw(address, value)
    }

    /** Écriture d'un octet **sans facturation**. */
    private fun write8Raw(address: Int, value: Int) {
        val region = MemoryRegion.of(address) ?: run {
            unsupportedAccess(address, write = true)
            return
        }
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
        account(a, 2)
        write16Raw(a, value)
    }

    /** Écriture d'un demi-mot aligné **sans facturation**. */
    private fun write16Raw(a: Int, value: Int) {
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
        account(a, 4)
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
        write16Raw(a, value and 0xFFFF)
        write16Raw(a + 2, (value ushr 16) and 0xFFFF)
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
