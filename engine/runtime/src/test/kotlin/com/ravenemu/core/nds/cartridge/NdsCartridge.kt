package com.ravenemu.core.nds.cartridge

/**
 * Cartouche Nintendo DS de synthèse, assemblée octet par octet.
 *
 * Aucune ROM n'est embarquée dans ce dépôt : ce qui est éprouvé est la lecture
 * d'un en-tête, et un en-tête se fabrique. Les deux blocs de code sont placés
 * après l'en-tête et remplis de zéros — leur contenu n'est pas lu ici, seule
 * leur position l'est.
 */
object NdsCartridge {

    /** Emplacement des deux blocs de code dans l'image construite. */
    const val ARM9_OFFSET = 0x4000
    const val ARM7_OFFSET = 0x6000
    const val CODE_SIZE = 0x400

    fun image(
        title: String = "RAVENEMU",
        gameCode: String = "ARVE",
        makerCode: String = "01",
        unitCode: Int = 0x00,
        romVersion: Int = 0,
        deviceCapacityCode: Int = 0,
        arm9Offset: Int = ARM9_OFFSET,
        arm9Size: Int = CODE_SIZE,
        arm7Offset: Int = ARM7_OFFSET,
        arm7Size: Int = CODE_SIZE,
        sizeBytes: Int = 0x8000,
        /** Somme de contrôle juste par défaut ; fausse pour éprouver le refus. */
        validChecksum: Boolean = true,
    ): ByteArray {
        val rom = ByteArray(sizeBytes)
        fun text(value: String, offset: Int, length: Int) {
            value.take(length).forEachIndexed { index, c -> rom[offset + index] = c.code.toByte() }
        }
        fun u32(offset: Int, value: Int) {
            for (byte in 0 until 4) {
                rom[offset + byte] = ((value ushr (byte * 8)) and 0xFF).toByte()
            }
        }

        text(title, 0x000, 12)
        text(gameCode, 0x00C, 4)
        text(makerCode, 0x010, 2)
        rom[0x012] = unitCode.toByte()
        rom[0x014] = deviceCapacityCode.toByte()
        rom[0x01E] = romVersion.toByte()

        u32(0x020, arm9Offset)
        u32(0x024, 0x0200_0000)
        u32(0x028, 0x0200_0000)
        u32(0x02C, arm9Size)

        u32(0x030, arm7Offset)
        u32(0x034, 0x0380_0000)
        u32(0x038, 0x0380_0000)
        u32(0x03C, arm7Size)

        u32(0x080, sizeBytes)
        u32(0x084, NdsHeader.HEADER_SIZE)

        // La somme se calcule en dernier : elle couvre tout ce qui précède.
        val crc = NdsHeader.crc16(rom, NdsHeader.CRC_COVERED_BYTES)
        val written = if (validChecksum) crc else crc xor 0xFFFF
        rom[NdsHeader.HEADER_CRC_OFFSET] = (written and 0xFF).toByte()
        rom[NdsHeader.HEADER_CRC_OFFSET + 1] = ((written ushr 8) and 0xFF).toByte()
        return rom
    }
}
