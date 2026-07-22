package com.ravenemu.core.gb

import com.ravenemu.emulation.api.SaveStateException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * Sérialisation des états instantanés RavenEmu pour la Game Boy.
 *
 * Format binaire versionné : magic `RVNS`, version, console, SHA-256 de la
 * ROM (l'état ne peut être restauré que sur la même ROM), puis l'état complet
 * de la machine. Ce format est propre à RavenEmu et n'est pas destiné à être
 * lu par d'autres émulateurs.
 */
internal object GameBoyState {

    private const val MAGIC = 0x52564E53 // "RVNS"

    /** Version 2 : état APU complet (la v1 stockait des registres bruts). */
    private const val VERSION = 2

    fun serialize(core: GameBoyCore, m: GameBoyCore.Machine): ByteArray {
        val buffer = ByteArrayOutputStream(64 * 1024)
        val out = DataOutputStream(buffer)

        out.writeInt(MAGIC)
        out.writeShort(VERSION)
        out.writeByte(core.console.ordinal)
        out.write(core.romHash)

        val cpu = m.cpu
        out.writeInt(cpu.af)
        out.writeInt(cpu.bc)
        out.writeInt(cpu.de)
        out.writeInt(cpu.hl)
        out.writeInt(cpu.sp)
        out.writeInt(cpu.pc)
        out.writeBoolean(cpu.ime)
        out.writeBoolean(cpu.halted)
        out.writeBoolean(cpu.eiPending)
        out.writeBoolean(cpu.haltBug)
        out.writeBoolean(cpu.locked)

        out.writeInt(m.interrupts.interruptFlags)
        out.writeInt(m.interrupts.interruptEnable)

        out.writeInt(m.timer.divCounter)
        out.writeInt(m.timer.tima)
        out.writeInt(m.timer.tma)
        out.writeInt(m.timer.readTac())
        out.writeInt(m.timer.stateReloadDelay())

        out.writeInt(m.serial.data)
        out.writeInt(m.serial.stateControl())
        out.writeInt(m.serial.stateRemaining())

        out.writeInt(m.joypad.stateSelect())

        val ppuFields = m.ppu.stateFields()
        out.writeInt(ppuFields.size)
        for (field in ppuFields) out.writeInt(field)
        out.write(m.ppu.vram)
        out.write(m.ppu.oam)

        out.write(m.bus.wram)
        out.write(m.bus.hram)
        val dma = m.bus.stateDma()
        out.writeInt(dma[0])
        out.writeInt(dma[1])

        m.apu.saveState(out)

        m.cartridge.saveState(out)

        out.flush()
        return buffer.toByteArray()
    }

    fun restore(core: GameBoyCore, m: GameBoyCore.Machine, state: ByteArray) {
        try {
            val input = DataInputStream(ByteArrayInputStream(state))

            if (input.readInt() != MAGIC) {
                throw SaveStateException("Ce fichier n'est pas un état RavenEmu")
            }
            val version = input.readUnsignedShort()
            if (version != VERSION) {
                throw SaveStateException("Version d'état non prise en charge : $version")
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

            val cpu = m.cpu
            cpu.af = input.readInt()
            cpu.bc = input.readInt()
            cpu.de = input.readInt()
            cpu.hl = input.readInt()
            cpu.sp = input.readInt()
            cpu.pc = input.readInt()
            cpu.ime = input.readBoolean()
            cpu.halted = input.readBoolean()
            cpu.eiPending = input.readBoolean()
            cpu.haltBug = input.readBoolean()
            cpu.locked = input.readBoolean()

            m.interrupts.interruptFlags = input.readInt()
            m.interrupts.interruptEnable = input.readInt()

            val divCounter = input.readInt()
            val tima = input.readInt()
            val tma = input.readInt()
            val tac = input.readInt()
            val reloadDelay = input.readInt()
            m.timer.restoreState(divCounter, tima, tma, tac, reloadDelay)

            val serialData = input.readInt()
            val serialControl = input.readInt()
            val serialRemaining = input.readInt()
            m.serial.restoreState(serialData, serialControl, serialRemaining)

            m.joypad.restoreState(input.readInt())

            val ppuFieldCount = input.readInt()
            val ppuFields = IntArray(ppuFieldCount) { input.readInt() }
            m.ppu.restoreState(ppuFields)
            input.readFully(m.ppu.vram)
            input.readFully(m.ppu.oam)

            input.readFully(m.bus.wram)
            input.readFully(m.bus.hram)
            m.bus.restoreDma(intArrayOf(input.readInt(), input.readInt()))

            m.apu.loadState(input)

            m.cartridge.loadState(input)
        } catch (e: SaveStateException) {
            throw e
        } catch (e: IOException) {
            throw SaveStateException("État instantané corrompu ou tronqué", e)
        } catch (e: IndexOutOfBoundsException) {
            throw SaveStateException("État instantané corrompu ou tronqué", e)
        }
    }
}
