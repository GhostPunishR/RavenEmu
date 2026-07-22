package com.ravenemu.core.gb.cartridge

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * MBC1 : banque ROM 5 bits (0 lu comme 1), registre secondaire 2 bits servant
 * de banque RAM ou de bits hauts ROM selon le mode. Les multicarts MBC1M ne
 * sont pas pris en charge (limite documentée).
 */
class Mbc1(rom: ByteArray, header: CartridgeHeader) : Cartridge(rom, header) {

    private var ramEnabled = false
    private var romBankLow = 1
    private var bankHigh = 0
    private var advancedMode = false

    private val romBankMask = romBankCount() - 1

    private fun currentRomBank(): Int {
        val bank = (bankHigh shl 5) or romBankLow
        return bank and romBankMask
    }

    private fun fixedBank(): Int =
        if (advancedMode) (bankHigh shl 5) and romBankMask else 0

    private fun ramBank(): Int =
        if (advancedMode && ram.size > RAM_BANK_SIZE) bankHigh else 0

    override fun readRom(address: Int): Int {
        val bank = if (address < ROM_BANK_SIZE) fixedBank() else currentRomBank()
        val offset = bank * ROM_BANK_SIZE + (address and (ROM_BANK_SIZE - 1))
        return if (offset < rom.size) rom[offset].toInt() and 0xFF else 0xFF
    }

    override fun writeControl(address: Int, value: Int) {
        when (address) {
            in 0x0000..0x1FFF -> ramEnabled = (value and 0x0F) == 0x0A
            in 0x2000..0x3FFF -> {
                val v = value and 0x1F
                romBankLow = if (v == 0) 1 else v
            }
            in 0x4000..0x5FFF -> bankHigh = value and 0x03
            in 0x6000..0x7FFF -> advancedMode = (value and 0x01) != 0
        }
    }

    override fun readRam(address: Int): Int {
        if (!ramEnabled || ram.isEmpty()) return 0xFF
        val offset = ramBank() * RAM_BANK_SIZE + (address - 0xA000)
        return if (offset < ram.size) ram[offset].toInt() and 0xFF else 0xFF
    }

    override fun writeRam(address: Int, value: Int) {
        if (!ramEnabled || ram.isEmpty()) return
        val offset = ramBank() * RAM_BANK_SIZE + (address - 0xA000)
        if (offset < ram.size) {
            ram[offset] = value.toByte()
            ramDirty = true
        }
    }

    override fun saveState(out: DataOutputStream) {
        out.writeBoolean(ramEnabled)
        out.writeInt(romBankLow)
        out.writeInt(bankHigh)
        out.writeBoolean(advancedMode)
        out.write(ram)
    }

    override fun loadState(input: DataInputStream) {
        ramEnabled = input.readBoolean()
        romBankLow = input.readInt()
        bankHigh = input.readInt()
        advancedMode = input.readBoolean()
        input.readFully(ram)
    }
}
