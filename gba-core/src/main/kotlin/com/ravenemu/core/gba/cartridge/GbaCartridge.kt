package com.ravenemu.core.gba.cartridge

import com.ravenemu.emulation.api.RomLoadException

/**
 * Cartouche Game Boy Advance : conserve les octets de la ROM et son en-tête, et
 * fournit l'accès en lecture attendu par le bus.
 *
 * La ROM est mappée à partir de `0x0800_0000` sur trois zones miroir (états
 * d'attente WS0/WS1/WS2) ; le bus a déjà ramené l'offset dans `0x0..0x01FF_FFFF`
 * (32 Mio). Un accès au-delà de la taille réelle retourne `0` (pas de « open
 * bus » émulé dans le premier lot ; limite documentée).
 *
 * Les mémoires de sauvegarde (SRAM, Flash, EEPROM) ne sont pas prises en charge
 * dans ce premier lot.
 */
class GbaCartridge private constructor(
    val rom: ByteArray,
    val header: GbaHeader,
) {
    /** Lit un octet à l'offset ROM (déjà replié par le bus). */
    fun read8(offset: Int): Int =
        if (offset in rom.indices) rom[offset].toInt() and 0xFF else 0

    companion object {
        /** Taille maximale d'une ROM GBA : 32 Mio. */
        const val MAX_ROM_SIZE = 0x0200_0000

        /**
         * Crée une cartouche à partir des octets d'une ROM.
         *
         * @throws RomLoadException si la ROM est trop courte, trop volumineuse
         *   ou dépourvue du marqueur GBA `0x96`.
         */
        fun create(rom: ByteArray): GbaCartridge {
            if (rom.size > MAX_ROM_SIZE) {
                throw RomLoadException(
                    "ROM GBA trop volumineuse : ${rom.size} octets (> $MAX_ROM_SIZE)"
                )
            }
            val header = GbaHeader.parse(rom)
            if (!header.fixedMarkerValid) {
                throw RomLoadException(
                    "Marqueur GBA 0x96 absent : ce fichier n'est pas une ROM Game Boy Advance"
                )
            }
            return GbaCartridge(rom, header)
        }
    }
}
