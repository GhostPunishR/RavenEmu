package com.ravenemu.core.gba.cartridge

import com.ravenemu.emulation.api.RomLoadException

/**
 * En-tête d'une cartouche Game Boy Advance (192 octets, `0x00`–`0xBF`).
 *
 * Champs extraits : point d'entrée, titre, code de jeu, code fabricant, version
 * et octet de somme de contrôle d'en-tête. Le logo Nintendo (`0x04`–`0x9F`)
 * n'est **jamais** embarqué ni exigé : RavenEmu ne distribue aucun contenu
 * protégé. La validité s'appuie sur la constante fixe `0x96` (offset `0xB2`) et
 * sur la somme de contrôle d'en-tête, toutes deux calculables sans le logo.
 */
data class GbaHeader(
    val entryPoint: Int,
    val title: String,
    val gameCode: String,
    val makerCode: String,
    val softwareVersion: Int,
    /** `true` si l'octet fixe `0x96` attendu est présent (marqueur GBA). */
    val fixedMarkerValid: Boolean,
    /** `true` si la somme de contrôle d'en-tête (`0xBD`) est cohérente. */
    val headerChecksumValid: Boolean,
) {
    companion object {
        const val HEADER_SIZE = 0xC0
        private const val FIXED_MARKER_OFFSET = 0xB2
        private const val FIXED_MARKER_VALUE = 0x96
        private const val CHECKSUM_OFFSET = 0xBD

        private fun byte(rom: ByteArray, offset: Int): Int = rom[offset].toInt() and 0xFF

        private fun ascii(rom: ByteArray, start: Int, length: Int): String {
            val sb = StringBuilder(length)
            for (i in 0 until length) {
                val c = byte(rom, start + i)
                if (c == 0) break
                if (c in 0x20..0x7E) sb.append(c.toChar())
            }
            return sb.toString().trim()
        }

        /**
         * Somme de contrôle d'en-tête GBA : `chk = (-(Σ octets 0xA0..0xBC) - 0x19)`
         * tronquée à 8 bits, comparée à l'octet `0xBD`.
         */
        fun computeHeaderChecksum(rom: ByteArray): Int {
            var chk = 0
            for (offset in 0xA0..0xBC) chk -= byte(rom, offset)
            chk -= 0x19
            return chk and 0xFF
        }

        /**
         * Analyse l'en-tête d'une ROM GBA.
         *
         * @throws RomLoadException si la ROM est trop courte pour contenir un
         *   en-tête complet.
         */
        fun parse(rom: ByteArray): GbaHeader {
            if (rom.size < HEADER_SIZE) {
                throw RomLoadException(
                    "ROM GBA trop courte : ${rom.size} octets (< $HEADER_SIZE)"
                )
            }
            val entry = byte(rom, 0) or
                (byte(rom, 1) shl 8) or
                (byte(rom, 2) shl 16) or
                (byte(rom, 3) shl 24)
            return GbaHeader(
                entryPoint = entry,
                title = ascii(rom, 0xA0, 12),
                gameCode = ascii(rom, 0xAC, 4),
                makerCode = ascii(rom, 0xB0, 2),
                softwareVersion = byte(rom, 0xBC),
                fixedMarkerValid = byte(rom, FIXED_MARKER_OFFSET) == FIXED_MARKER_VALUE,
                headerChecksumValid =
                    computeHeaderChecksum(rom) == byte(rom, CHECKSUM_OFFSET),
            )
        }
    }
}
