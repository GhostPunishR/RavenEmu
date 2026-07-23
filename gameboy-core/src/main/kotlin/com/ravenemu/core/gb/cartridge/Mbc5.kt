package com.ravenemu.core.gb.cartridge

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * MBC5 : banque ROM 9 bits (la banque 0 est réellement sélectionnable),
 * banque RAM 4 bits. Le moteur de vibration des cartouches « rumble »
 * (bit 3 de la banque RAM) est ignoré.
 */
class Mbc5(rom: ByteArray, header: CartridgeHeader) : Cartridge(rom, header) {

    private var ramEnabled = false
    private var romBankLow = 1
    private var romBankHigh = 0
    private var ramBank = 0

    private val hasRumble = header.cartridgeTypeCode in 0x1C..0x1E

    private fun currentRomBank(): Int =
        normalizeRomBank((romBankHigh shl 8) or romBankLow)

    override fun readRom(address: Int): Int {
        val bank = if (address < ROM_BANK_SIZE) 0 else currentRomBank()
        val offset = bank * ROM_BANK_SIZE + (address and (ROM_BANK_SIZE - 1))
        return if (offset < rom.size) rom[offset].toInt() and 0xFF else 0xFF
    }

    override fun writeControl(address: Int, value: Int) {
        when (address) {
            in 0x0000..0x1FFF -> ramEnabled = (value and 0x0F) == 0x0A
            in 0x2000..0x2FFF -> romBankLow = value and 0xFF
            in 0x3000..0x3FFF -> romBankHigh = value and 0x01
            in 0x4000..0x5FFF -> {
                // Sur cartouche rumble, le bit 3 pilote le moteur : il est
                // exclu de la sélection de banque.
                ramBank = value and (if (hasRumble) 0x07 else 0x0F)
            }
        }
    }

    override fun readRam(address: Int): Int {
        if (!ramEnabled || ram.isEmpty()) return 0xFF
        val offset = ramBank * RAM_BANK_SIZE + (address - 0xA000)
        return if (offset < ram.size) ram[offset].toInt() and 0xFF else 0xFF
    }

    override fun writeRam(address: Int, value: Int) {
        if (!ramEnabled || ram.isEmpty()) return
        val offset = ramBank * RAM_BANK_SIZE + (address - 0xA000)
        if (offset < ram.size) {
            ram[offset] = value.toByte()
            ramDirty = true
        }
    }

    override fun saveState(out: DataOutputStream) {
        out.writeBoolean(ramEnabled)
        out.writeInt(romBankLow)
        out.writeInt(romBankHigh)
        out.writeInt(ramBank)
        out.write(ram)
    }

    override fun loadState(input: DataInputStream) {
        ramEnabled = input.readBoolean()
        romBankLow = input.readInt()
        romBankHigh = input.readInt()
        ramBank = input.readInt()
        input.readFully(ram)
    }
}
