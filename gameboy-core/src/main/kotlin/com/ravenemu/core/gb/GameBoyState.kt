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

    /** Version 4 : état Game Boy Color (CRAM, banques, vitesse, HDMA). */
    private const val VERSION = 4

    /** Garde-fou contre les fichiers corrompus provoquant de fortes allocations. */
    private const val MAX_STATE_SIZE = 1 shl 20

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
        out.write(m.ppu.bgCram)
        out.write(m.ppu.objCram)

        val speedState = m.speed.state()
        out.writeInt(speedState[0])
        out.writeInt(speedState[1])

        out.write(m.bus.wram)
        out.write(m.bus.hram)
        val dma = m.bus.stateDma()
        out.writeInt(dma.size)
        for (field in dma) out.writeInt(field)

        m.apu.saveState(out)

        m.cartridge.saveState(out)

        out.flush()
        return buffer.toByteArray()
    }

    fun restore(core: GameBoyCore, state: ByteArray): GameBoyCore.Machine {
        if (state.size > MAX_STATE_SIZE) {
            throw SaveStateException("État instantané trop volumineux : ${state.size} octets")
        }
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

            // Toute la restauration se fait dans une machine temporaire.
            // La machine active n'est remplacée qu'après lecture complète.
            val m = core.newMachineForState()
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
            val expectedPpuFieldCount = m.ppu.stateFields().size
            if (ppuFieldCount != expectedPpuFieldCount) {
                throw SaveStateException("État instantané corrompu (PPU)")
            }
            val ppuFields = IntArray(ppuFieldCount) { input.readInt() }
            m.ppu.restoreState(ppuFields)
            input.readFully(m.ppu.vram)
            input.readFully(m.ppu.oam)
            input.readFully(m.ppu.bgCram)
            input.readFully(m.ppu.objCram)
            m.ppu.rebuildColorCache()

            m.speed.restore(intArrayOf(input.readInt(), input.readInt()))

            input.readFully(m.bus.wram)
            input.readFully(m.bus.hram)
            val dmaCount = input.readInt()
            val expectedDmaCount = m.bus.stateDma().size
            if (dmaCount != expectedDmaCount) {
                throw SaveStateException("État instantané corrompu (DMA)")
            }
            m.bus.restoreDma(IntArray(dmaCount) { input.readInt() })

            m.apu.loadState(input)
            m.cartridge.loadState(input)
            if (input.read() != -1) {
                throw SaveStateException("État instantané corrompu (données excédentaires)")
            }
            return m
        } catch (e: SaveStateException) {
            throw e
        } catch (e: IOException) {
            throw SaveStateException("État instantané corrompu ou tronqué", e)
        } catch (e: IndexOutOfBoundsException) {
            throw SaveStateException("État instantané corrompu ou tronqué", e)
        } catch (e: IllegalArgumentException) {
            throw SaveStateException("État instantané corrompu", e)
        }
    }
}
