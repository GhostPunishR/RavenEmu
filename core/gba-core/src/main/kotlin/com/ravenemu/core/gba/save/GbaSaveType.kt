package com.ravenemu.core.gba.save

/** Types de mémoire persistante rencontrés sur cartouche Game Boy Advance. */
enum class GbaSaveType(val displayName: String, val sizeBytes: Int) {
    NONE("Aucune", 0),
    SRAM("SRAM 32 Kio", 32 * 1024),
    FLASH_64K("Flash 64 Kio", 64 * 1024),
    FLASH_128K("Flash 128 Kio", 128 * 1024),
    EEPROM_512("EEPROM 512 o", 512),
    EEPROM_8K("EEPROM 8 Kio", 8 * 1024),
    ;

    val isEeprom: Boolean get() = this == EEPROM_512 || this == EEPROM_8K
    val isFlash: Boolean get() = this == FLASH_64K || this == FLASH_128K

    companion object {
        /** Déduit le type des marqueurs ASCII alignés laissés dans la ROM. */
        fun detect(rom: ByteArray): GbaSaveType {
            val markers = listOf(
                "FLASH1M_V" to FLASH_128K,
                "FLASH512_V" to FLASH_64K,
                "FLASH_V" to FLASH_64K,
                "EEPROM_V" to EEPROM_512,
                "SRAM_F_V" to SRAM,
                "SRAM_V" to SRAM,
            )
            for ((marker, type) in markers) {
                if (contains(rom, marker)) return type
            }
            return NONE
        }

        private fun contains(rom: ByteArray, marker: String): Boolean {
            val bytes = marker.toByteArray(Charsets.US_ASCII)
            var offset = 0
            while (offset + bytes.size <= rom.size) {
                var match = true
                for (i in bytes.indices) {
                    if (rom[offset + i] != bytes[i]) {
                        match = false
                        break
                    }
                }
                if (match) return true
                offset += 4
            }
            return false
        }
    }
}
