package com.ravenemu.core.gb.cartridge

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * MBC2 : banque ROM 4 bits, RAM interne de 512 quartets. Le bit 8 de
 * l'adresse d'écriture 0x0000–0x3FFF distingue activation RAM (bit 8 = 0) et
 * sélection de banque (bit 8 = 1). Les lectures RAM renvoient le quartet bas
 * avec les 4 bits hauts à 1, et la zone se répète tous les 512 octets.
 */
class Mbc2(rom: ByteArray, header: CartridgeHeader) : Cartridge(rom, header) {

    private var ramEnabled = false
    private var romBank = 1

    private val romBankMask = romBankCount() - 1

    override fun readRom(address: Int): Int {
        val bank = if (address < ROM_BANK_SIZE) 0 else romBank and romBankMask
        val offset = bank * ROM_BANK_SIZE + (address and (ROM_BANK_SIZE - 1))
        return if (offset < rom.size) rom[offset].toInt() and 0xFF else 0xFF
    }

    override fun writeControl(address: Int, value: Int) {
        if (address > 0x3FFF) return
        if ((address and 0x0100) == 0) {
            ramEnabled = (value and 0x0F) == 0x0A
        } else {
            val v = value and 0x0F
            romBank = if (v == 0) 1 else v
        }
    }

    override fun readRam(address: Int): Int {
        if (!ramEnabled) return 0xFF
        val offset = (address - 0xA000) and 0x1FF
        return 0xF0 or (ram[offset].toInt() and 0x0F)
    }

    override fun writeRam(address: Int, value: Int) {
        if (!ramEnabled) return
        val offset = (address - 0xA000) and 0x1FF
        ram[offset] = (value and 0x0F).toByte()
        ramDirty = true
    }

    override fun saveState(out: DataOutputStream) {
        out.writeBoolean(ramEnabled)
        out.writeInt(romBank)
        out.write(ram)
    }

    override fun loadState(input: DataInputStream) {
        ramEnabled = input.readBoolean()
        romBank = input.readInt()
        input.readFully(ram)
    }
}
