package com.ravenemu.core.gba.state

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.GbaMachine
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
    private const val VERSION = 1
    private const val BANK_WORDS = 28 // CpuState.exportBanks(): 6*3 + 10

    /** Taille maximale acceptée pour un état (garde-fou anti-« fichier trop volumineux »). */
    private const val MAX_STATE_SIZE = 1 shl 20 // 1 Mio

    fun serialize(core: GbaCore, machine: GbaMachine): ByteArray {
        val buffer = ByteArrayOutputStream(512 * 1024)
        val out = DataOutputStream(buffer)

        out.writeInt(MAGIC)
        out.writeShort(VERSION)
        out.writeByte(core.console.ordinal)
        out.write(core.romHash)

        val state = machine.cpu.state
        val banks = state.exportBanks()
        for (i in 0 until 16) out.writeInt(state.regs[i])
        out.writeInt(state.cpsr())
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

        out.writeInt(machine.interrupts.enable)
        out.writeInt(machine.interrupts.flags)
        out.writeBoolean(machine.interrupts.masterEnable)
        for (v in machine.timers.exportState()) out.writeInt(v)
        for (v in machine.dma.exportState()) out.writeInt(v)

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
            if (console != core.console.ordinal) {
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

            val ie = input.readInt()
            val iflag = input.readInt()
            val ime = input.readBoolean()
            val timerState = IntArray(16) { input.readInt() }
            val dmaState = IntArray(8) { input.readInt() }

            // Tout est lu et validé : application atomique.
            val state = machine.cpu.state
            state.importBanks(banks)
            state.setControlRaw(cpsr)
            for (i in 0 until 16) state.regs[i] = regs[i]

            ewram.copyInto(bus.ewram)
            iwram.copyInto(bus.iwram)
            io.copyInto(bus.io)
            palette.copyInto(bus.paletteRam)
            vram.copyInto(bus.vram)
            oam.copyInto(bus.oam)
            sram.copyInto(bus.sram)
            bus.keypad.pressedBits = keypadBits
            machine.interrupts.enable = ie
            machine.interrupts.flags = iflag
            machine.interrupts.masterEnable = ime
            machine.timers.importState(timerState)
            machine.dma.importState(dmaState)
        } catch (e: SaveStateException) {
            throw e
        } catch (e: IOException) {
            throw SaveStateException("État instantané corrompu ou tronqué", e)
        }
    }
}
