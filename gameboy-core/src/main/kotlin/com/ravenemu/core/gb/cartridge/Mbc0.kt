package com.ravenemu.core.gb.cartridge

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Cartouche sans contrôleur mémoire : ROM 32 KiB fixe, RAM externe éventuelle
 * (types 0x08/0x09) non bancarisée.
 */
class Mbc0(rom: ByteArray, header: CartridgeHeader) : Cartridge(rom, header) {

    override fun readRom(address: Int): Int =
        if (address < rom.size) rom[address].toInt() and 0xFF else 0xFF

    override fun writeControl(address: Int, value: Int) {
        // Aucun registre : écritures ignorées.
    }

    override fun readRam(address: Int): Int {
        val offset = address - 0xA000
        return if (offset < ram.size) ram[offset].toInt() and 0xFF else 0xFF
    }

    override fun writeRam(address: Int, value: Int) {
        val offset = address - 0xA000
        if (offset < ram.size) {
            ram[offset] = value.toByte()
            ramDirty = true
        }
    }

    override fun saveState(out: DataOutputStream) {
        out.write(ram)
    }

    override fun loadState(input: DataInputStream) {
        input.readFully(ram)
    }
}
