package com.ravenemu.core.gb.cartridge

import com.ravenemu.emulation.api.RomLoadException
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Cartouche Game Boy vue depuis le bus : ROM en 0x0000–0x7FFF, RAM externe
 * en 0xA000–0xBFFF, écritures ROM interprétées comme registres du contrôleur
 * mémoire. Les adresses reçues sont les adresses bus absolues.
 */
abstract class Cartridge(
    val rom: ByteArray,
    val header: CartridgeHeader,
) {
    /** RAM externe brute (vide si la cartouche n'en a pas). */
    val ram: ByteArray = ByteArray(header.ramSizeBytes)

    /** Vrai si la RAM a été modifiée depuis le dernier acquittement. */
    var ramDirty: Boolean = false
        protected set

    abstract fun readRom(address: Int): Int
    abstract fun writeControl(address: Int, value: Int)
    abstract fun readRam(address: Int): Int
    abstract fun writeRam(address: Int, value: Int)

    /** Avance l'horloge interne éventuelle (RTC MBC3) de [cycles] T-cycles. */
    open fun tick(cycles: Int) {}

    /**
     * Contenu persistant au format `.sav` brut : RAM telle quelle, suivie de
     * l'état RTC pour MBC3 (voir [Mbc3]). Retourne `null` sans pile.
     */
    open fun exportBattery(): ByteArray? =
        if (header.hasBattery && ram.isNotEmpty()) ram.copyOf() else null

    /** Restaure un contenu `.sav`. Les tailles excédentaires sont tronquées. */
    open fun importBattery(data: ByteArray) {
        if (ram.isEmpty()) return
        data.copyInto(ram, 0, 0, minOf(data.size, ram.size))
        ramDirty = false
    }

    fun acknowledgeRamSaved() {
        ramDirty = false
    }

    /** Sérialise l'état volatil (registres de banque, RAM, RTC…). */
    abstract fun saveState(out: DataOutputStream)

    /** Restaure l'état écrit par [saveState]. */
    abstract fun loadState(input: DataInputStream)

    protected fun romBankCount(): Int = maxOf(2, rom.size / ROM_BANK_SIZE)

    companion object {
        const val ROM_BANK_SIZE = 0x4000
        const val RAM_BANK_SIZE = 0x2000

        /**
         * Construit la cartouche adaptée au contrôleur déclaré par l'en-tête.
         * @param clock source de temps (secondes Unix) pour la RTC MBC3,
         *   injectable pour les tests.
         * @throws RomLoadException pour un contrôleur non pris en charge.
         */
        fun create(
            rom: ByteArray,
            clock: () -> Long = { System.currentTimeMillis() / 1000 },
        ): Cartridge {
            val header = CartridgeHeader.parse(rom)
            return when (header.mbcType) {
                MbcType.NONE -> Mbc0(rom, header)
                MbcType.MBC1 -> Mbc1(rom, header)
                MbcType.MBC2 -> Mbc2(rom, header)
                MbcType.MBC3 -> Mbc3(rom, header, clock)
                MbcType.MBC5 -> Mbc5(rom, header)
                MbcType.UNSUPPORTED -> throw RomLoadException(
                    "Type de cartouche non pris en charge : 0x%02X".format(
                        header.cartridgeTypeCode
                    )
                )
            }
        }
    }
}
