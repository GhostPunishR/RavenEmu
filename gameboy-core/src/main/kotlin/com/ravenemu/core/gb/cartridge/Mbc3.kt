package com.ravenemu.core.gb.cartridge

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * MBC3 : banque ROM 7 bits (0 lu comme 1), banques RAM 0–3 ou registres RTC
 * 0x08–0x0C mappés en 0xA000–0xBFFF, verrouillage de l'horloge par écriture
 * 0x00 puis 0x01 en 0x6000–0x7FFF.
 *
 * L'horloge temps réel est synchronisée sur une source de temps injectable
 * (secondes Unix) pour rester déterministe en test. Le `.sav` exporté ajoute
 * à la RAM un pied de 48 octets au format horloge répandu (registres
 * courants, registres verrouillés, horodatage 64 bits, entiers petit-boutistes),
 * afin de rester lisible par les autres émulateurs.
 */
class Mbc3(
    rom: ByteArray,
    header: CartridgeHeader,
    private val clock: () -> Long,
) : Cartridge(rom, header) {

    private var ramEnabled = false
    private var romBank = 1
    private var ramBankOrRtc = 0
    private var latchArmed = false

    // Registres RTC courants.
    private var rtcSeconds = 0
    private var rtcMinutes = 0
    private var rtcHours = 0
    private var rtcDays = 0
    private var rtcHalt = false
    private var rtcDayCarry = false

    // Registres RTC verrouillés (exposés à la lecture).
    private var latchSeconds = 0
    private var latchMinutes = 0
    private var latchHours = 0
    private var latchDayLow = 0
    private var latchDayHigh = 0

    private var lastSyncEpoch = clock()

    private val hasRtc = header.hasRtc

    /** Rattrape le temps écoulé depuis la dernière synchronisation. */
    private fun syncRtc() {
        val now = clock()
        var elapsed = now - lastSyncEpoch
        lastSyncEpoch = now
        if (rtcHalt || elapsed <= 0) return
        elapsed += rtcSeconds + 60L * rtcMinutes + 3600L * rtcHours + 86400L * rtcDays
        rtcSeconds = (elapsed % 60).toInt()
        elapsed /= 60
        rtcMinutes = (elapsed % 60).toInt()
        elapsed /= 60
        rtcHours = (elapsed % 24).toInt()
        elapsed /= 24
        rtcDays = (elapsed and 0x1FF).toInt()
        if (elapsed > 0x1FF) rtcDayCarry = true
    }

    private fun latchRtc() {
        syncRtc()
        latchSeconds = rtcSeconds
        latchMinutes = rtcMinutes
        latchHours = rtcHours
        latchDayLow = rtcDays and 0xFF
        latchDayHigh = ((rtcDays shr 8) and 0x01) or
            (if (rtcHalt) 0x40 else 0) or
            (if (rtcDayCarry) 0x80 else 0)
    }

    override fun readRom(address: Int): Int {
        val bank = if (address < ROM_BANK_SIZE) 0 else normalizeRomBank(romBank)
        val offset = bank * ROM_BANK_SIZE + (address and (ROM_BANK_SIZE - 1))
        return if (offset < rom.size) rom[offset].toInt() and 0xFF else 0xFF
    }

    override fun writeControl(address: Int, value: Int) {
        when (address) {
            in 0x0000..0x1FFF -> ramEnabled = (value and 0x0F) == 0x0A
            in 0x2000..0x3FFF -> {
                val v = value and 0x7F
                romBank = if (v == 0) 1 else v
            }
            in 0x4000..0x5FFF -> ramBankOrRtc = value and 0x0F
            in 0x6000..0x7FFF -> {
                if (latchArmed && value == 0x01 && hasRtc) latchRtc()
                latchArmed = value == 0x00
            }
        }
    }

    override fun readRam(address: Int): Int {
        if (!ramEnabled) return 0xFF
        return when (ramBankOrRtc) {
            in 0x00..0x03 -> {
                val offset = ramBankOrRtc * RAM_BANK_SIZE + (address - 0xA000)
                if (offset < ram.size) ram[offset].toInt() and 0xFF else 0xFF
            }
            0x08 -> if (hasRtc) latchSeconds else 0xFF
            0x09 -> if (hasRtc) latchMinutes else 0xFF
            0x0A -> if (hasRtc) latchHours else 0xFF
            0x0B -> if (hasRtc) latchDayLow else 0xFF
            0x0C -> if (hasRtc) latchDayHigh else 0xFF
            else -> 0xFF
        }
    }

    override fun writeRam(address: Int, value: Int) {
        if (!ramEnabled) return
        when (ramBankOrRtc) {
            in 0x00..0x03 -> {
                val offset = ramBankOrRtc * RAM_BANK_SIZE + (address - 0xA000)
                if (offset < ram.size) {
                    ram[offset] = value.toByte()
                    ramDirty = true
                }
            }
            0x08 -> if (hasRtc) { syncRtc(); rtcSeconds = value and 0x3F; ramDirty = true }
            0x09 -> if (hasRtc) { syncRtc(); rtcMinutes = value and 0x3F; ramDirty = true }
            0x0A -> if (hasRtc) { syncRtc(); rtcHours = value and 0x1F; ramDirty = true }
            0x0B -> if (hasRtc) {
                syncRtc()
                rtcDays = (rtcDays and 0x100) or (value and 0xFF)
                ramDirty = true
            }
            0x0C -> if (hasRtc) {
                syncRtc()
                rtcDays = (rtcDays and 0xFF) or ((value and 0x01) shl 8)
                rtcHalt = (value and 0x40) != 0
                rtcDayCarry = (value and 0x80) != 0
                ramDirty = true
            }
        }
    }

    override fun exportBattery(): ByteArray? {
        if (!header.hasBattery) return null
        if (!hasRtc) return super.exportBattery()
        syncRtc()
        latchRtc()
        val out = ByteArray(ram.size + RTC_FOOTER_SIZE)
        ram.copyInto(out)
        var p = ram.size
        val values = intArrayOf(
            rtcSeconds, rtcMinutes, rtcHours, rtcDays and 0xFF,
            ((rtcDays shr 8) and 0x01) or (if (rtcHalt) 0x40 else 0) or
                (if (rtcDayCarry) 0x80 else 0),
            latchSeconds, latchMinutes, latchHours, latchDayLow, latchDayHigh,
        )
        for (v in values) {
            out[p] = (v and 0xFF).toByte()
            out[p + 1] = ((v shr 8) and 0xFF).toByte()
            out[p + 2] = ((v shr 16) and 0xFF).toByte()
            out[p + 3] = ((v shr 24) and 0xFF).toByte()
            p += 4
        }
        var ts = lastSyncEpoch
        for (i in 0 until 8) {
            out[p + i] = (ts and 0xFF).toByte()
            ts = ts shr 8
        }
        return out
    }

    override fun importBattery(data: ByteArray) {
        super.importBattery(data)
        if (!hasRtc || data.size < ram.size + RTC_FOOTER_MIN_SIZE) return
        var p = ram.size
        fun readU32(): Int {
            val v = (data[p].toInt() and 0xFF) or
                ((data[p + 1].toInt() and 0xFF) shl 8) or
                ((data[p + 2].toInt() and 0xFF) shl 16) or
                ((data[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        rtcSeconds = readU32() and 0x3F
        rtcMinutes = readU32() and 0x3F
        rtcHours = readU32() and 0x1F
        val dayLow = readU32() and 0xFF
        val dayHigh = readU32()
        rtcDays = dayLow or ((dayHigh and 0x01) shl 8)
        rtcHalt = (dayHigh and 0x40) != 0
        rtcDayCarry = (dayHigh and 0x80) != 0
        latchSeconds = readU32() and 0x3F
        latchMinutes = readU32() and 0x3F
        latchHours = readU32() and 0x1F
        latchDayLow = readU32() and 0xFF
        latchDayHigh = readU32() and 0xFF
        var ts = 0L
        val tsBytes = if (data.size >= ram.size + RTC_FOOTER_SIZE) 8 else 4
        for (i in tsBytes - 1 downTo 0) {
            ts = (ts shl 8) or (data[p + i].toLong() and 0xFF)
        }
        lastSyncEpoch = ts
        syncRtc()
        ramDirty = false
    }

    override fun saveState(out: DataOutputStream) {
        out.writeBoolean(ramEnabled)
        out.writeInt(romBank)
        out.writeInt(ramBankOrRtc)
        out.writeBoolean(latchArmed)
        out.writeInt(rtcSeconds)
        out.writeInt(rtcMinutes)
        out.writeInt(rtcHours)
        out.writeInt(rtcDays)
        out.writeBoolean(rtcHalt)
        out.writeBoolean(rtcDayCarry)
        out.writeInt(latchSeconds)
        out.writeInt(latchMinutes)
        out.writeInt(latchHours)
        out.writeInt(latchDayLow)
        out.writeInt(latchDayHigh)
        out.writeLong(lastSyncEpoch)
        out.write(ram)
    }

    override fun loadState(input: DataInputStream) {
        ramEnabled = input.readBoolean()
        romBank = input.readInt()
        ramBankOrRtc = input.readInt()
        latchArmed = input.readBoolean()
        rtcSeconds = input.readInt()
        rtcMinutes = input.readInt()
        rtcHours = input.readInt()
        rtcDays = input.readInt()
        rtcHalt = input.readBoolean()
        rtcDayCarry = input.readBoolean()
        latchSeconds = input.readInt()
        latchMinutes = input.readInt()
        latchHours = input.readInt()
        latchDayLow = input.readInt()
        latchDayHigh = input.readInt()
        lastSyncEpoch = input.readLong()
        input.readFully(ram)
    }

    companion object {
        /** 10 registres de 4 octets + horodatage 8 octets. */
        const val RTC_FOOTER_SIZE = 48

        /** Variante répandue avec horodatage 4 octets. */
        const val RTC_FOOTER_MIN_SIZE = 44
    }
}
