package com.ravenemu.core.gba.state

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.cartridge.GbaGpio
import com.ravenemu.emulation.api.SaveStateException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Sérialisation des états instantanés Game Boy Advance, dans le conteneur
 * versionné RavenEmu (`RVNS`).
 *
 * En-tête : magic `RVNS`, version, console, SHA-256 de la ROM. Un état ne peut
 * être restauré que sur la **même ROM** et la même console. La restauration est
 * **transactionnelle** : tout est lu et validé dans des tampons locaux avant la
 * moindre mutation de la machine ; un fichier tronqué ou trop volumineux est
 * rejeté sans effet de bord. Ce format est distinct de celui de la Game Boy.
 */
object GbaState {

    private const val MAGIC = 0x52564E53 // "RVNS"
    /**
     * Version 8 : ajoute le port GPIO de la cartouche et son horloge temps réel
     * (broches, transaction en cours, registre d'état, écart avec l'heure de
     * l'hôte). Sans eux, recharger un état au milieu d'un dialogue avec l'horloge
     * laisserait le jeu attendre des octets qui ne viendraient jamais.
     *
     * Version 7 : état PPU complet (dont les points de référence affines),
     * interruptions, timers, DMA (adresses courantes et cycles dus), pause CPU
     * (`halted`), attente d'interruption du BIOS (`IntrWait`) et mémoire de
     * sauvegarde de la cartouche.
     */
    private const val VERSION = 8
    private const val BANK_WORDS = 28 // CpuState.exportBanks(): 6*3 + 10
    private const val TIMER_STATE_WORDS = 16
    private const val DMA_STATE_WORDS = 9 // 4 sources + 4 destinations + cycles dus

    /** Taille maximale acceptée pour un état (garde-fou anti-« fichier trop volumineux »). */
    private const val MAX_STATE_SIZE = 1 shl 20 // 1 Mio

    fun serialize(core: GbaCore, machine: GbaMachine): ByteArray {
        val buffer = ByteArrayOutputStream(512 * 1024)
        val out = DataOutputStream(buffer)

        out.writeInt(MAGIC)
        out.writeShort(VERSION)
        out.writeByte(core.console.storageId)
        out.write(core.romHash)

        val state = machine.cpu.state
        val banks = state.exportBanks()
        for (i in 0 until 16) out.writeInt(state.regs[i])
        out.writeInt(state.cpsr())
        out.writeBoolean(state.halted)
        out.writeInt(banks.size)
        for (v in banks) out.writeInt(v)

        val bus = machine.bus
        out.write(bus.ewram)
        out.write(bus.iwram)
        out.write(bus.io)
        out.write(bus.paletteRam)
        out.write(bus.vram)
        out.write(bus.oam)
        out.write(bus.sram)
        out.writeInt(bus.keypad.pressedBits)

        val ppuFields = machine.ppu.stateFields()
        out.writeInt(ppuFields.size)
        for (field in ppuFields) out.writeInt(field)
        for (pixel in machine.ppu.frame) out.writeInt(pixel)

        out.writeInt(machine.interrupts.enable)
        out.writeInt(machine.interrupts.flags)
        out.writeBoolean(machine.interrupts.masterEnable)
        for (value in machine.timers.exportState()) out.writeInt(value)
        for (value in machine.dma.exportState()) out.writeInt(value)

        // Attente d'interruption du BIOS : présence, masque, politique.
        val wait = machine.bios.waitState
        out.writeBoolean(wait != null)
        out.writeInt(wait?.interruptMask ?: 0)
        out.writeBoolean(wait?.discardOldFlags ?: false)

        // Mémoire de sauvegarde : longueur puis contenu (vide si absente).
        val save = machine.cartridge.save
        val saveData = save?.export() ?: ByteArray(0)
        out.writeInt(saveData.size)
        out.write(saveData)

        // Port GPIO et horloge temps réel : présence, puis un bloc de taille fixe.
        val gpio = machine.cartridge.gpio
        out.writeBoolean(gpio != null)
        if (gpio != null) for (value in gpio.exportState()) out.writeInt(value)

        out.flush()
        return buffer.toByteArray()
    }

    fun restore(core: GbaCore, machine: GbaMachine, data: ByteArray) {
        if (data.size > MAX_STATE_SIZE) {
            throw SaveStateException("État instantané trop volumineux : ${data.size} octets")
        }
        try {
            val input = DataInputStream(ByteArrayInputStream(data))

            if (input.readInt() != MAGIC) {
                throw SaveStateException("Ce fichier n'est pas un état RavenEmu")
            }
            val version = input.readUnsignedShort()
            if (version != VERSION) {
                throw SaveStateException("Version d'état GBA non prise en charge : $version")
            }
            val console = input.readUnsignedByte()
            if (console != core.console.storageId) {
                throw SaveStateException("État issu d'une autre console")
            }
            val hash = ByteArray(core.romHash.size)
            input.readFully(hash)
            if (!hash.contentEquals(core.romHash)) {
                throw SaveStateException("État issu d'une autre ROM")
            }

            // Lecture dans des tampons locaux (rien n'est encore appliqué).
            val regs = IntArray(16) { input.readInt() }
            val cpsr = input.readInt()
            val halted = input.readBoolean()
            val bankCount = input.readInt()
            if (bankCount != BANK_WORDS) {
                throw SaveStateException("État instantané corrompu (banques)")
            }
            val banks = IntArray(bankCount) { input.readInt() }

            val bus = machine.bus
            val ewram = ByteArray(bus.ewram.size).also(input::readFully)
            val iwram = ByteArray(bus.iwram.size).also(input::readFully)
            val io = ByteArray(bus.io.size).also(input::readFully)
            val palette = ByteArray(bus.paletteRam.size).also(input::readFully)
            val vram = ByteArray(bus.vram.size).also(input::readFully)
            val oam = ByteArray(bus.oam.size).also(input::readFully)
            val sram = ByteArray(bus.sram.size).also(input::readFully)
            val keypadBits = input.readInt()

            val ppuFieldCount = input.readInt()
            if (ppuFieldCount != com.ravenemu.core.gba.ppu.GbaPpu.STATE_FIELD_COUNT) {
                throw SaveStateException("État instantané corrompu (PPU)")
            }
            val ppuFields = IntArray(ppuFieldCount) { input.readInt() }
            val ppuFrame = IntArray(machine.ppu.frame.size) { input.readInt() }

            val interruptEnable = input.readInt()
            val interruptFlags = input.readInt()
            val interruptMasterEnable = input.readBoolean()
            val timerState = IntArray(TIMER_STATE_WORDS) { input.readInt() }
            val dmaState = IntArray(DMA_STATE_WORDS) { input.readInt() }
            val waiting = input.readBoolean()
            val waitMask = input.readInt()
            val waitDiscard = input.readBoolean()

            val saveSize = input.readInt()
            val expectedSaveSize = machine.cartridge.save?.data?.size ?: 0
            if (saveSize != expectedSaveSize) {
                throw SaveStateException("État instantané corrompu (sauvegarde)")
            }
            val saveData = ByteArray(saveSize).also(input::readFully)

            val gpio = machine.cartridge.gpio
            if (input.readBoolean() != (gpio != null)) {
                throw SaveStateException("État instantané corrompu (port GPIO)")
            }
            val gpioState =
                if (gpio != null) IntArray(GbaGpio.STATE_WORDS) { input.readInt() } else null

            if (input.read() != -1) {
                throw SaveStateException("État instantané corrompu (données excédentaires)")
            }

            // Valide d'abord les derniers champs susceptibles d'échouer.
            machine.ppu.restoreState(ppuFields)

            // Tout est lu et validé : application atomique.
            val state = machine.cpu.state
            state.importBanks(banks)
            state.setControlRaw(cpsr)
            state.halted = halted
            for (i in 0 until 16) state.regs[i] = regs[i]

            ewram.copyInto(bus.ewram)
            iwram.copyInto(bus.iwram)
            io.copyInto(bus.io)
            // `WAITCNT` est dupliqué dans le modèle de temps d'attente : il faut
            // le réaligner sur les registres restaurés.
            bus.syncTimingFromIo()
            palette.copyInto(bus.paletteRam)
            vram.copyInto(bus.vram)
            oam.copyInto(bus.oam)
            sram.copyInto(bus.sram)
            bus.keypad.pressedBits = keypadBits
            ppuFrame.copyInto(machine.ppu.frame)
            machine.interrupts.enable = interruptEnable
            machine.interrupts.flags = interruptFlags
            machine.interrupts.masterEnable = interruptMasterEnable
            machine.timers.importState(timerState)
            machine.dma.importState(dmaState)
            if (saveData.isNotEmpty()) machine.cartridge.save?.import(saveData)
            if (gpioState != null) gpio?.importState(gpioState)
            machine.bios.waitState =
                if (waiting) {
                    com.ravenemu.core.gba.bios.BiosWaitState(waitMask, waitDiscard)
                } else {
                    null
                }
        } catch (e: SaveStateException) {
            throw e
        } catch (e: IOException) {
            throw SaveStateException("État instantané corrompu ou tronqué", e)
        } catch (e: IllegalArgumentException) {
            throw SaveStateException("État instantané corrompu", e)
        }
    }
}
