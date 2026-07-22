package com.ravenemu.core.gb

/**
 * Fabrique de ROM synthétiques pour les tests. Les images produites sont
 * générées de toutes pièces (aucun contenu tiers) avec un en-tête valide,
 * checksums compris.
 */
object TestRoms {

    const val ENTRY_POINT = 0x0100

    /**
     * Construit une ROM avec l'en-tête demandé.
     *
     * @param fill mutation du contenu appliquée avant le calcul des
     *   checksums, pour injecter du code ou des marqueurs de banque.
     */
    fun build(
        type: Int = 0x00,
        romSizeCode: Int = 0x00,
        ramSizeCode: Int = 0x00,
        title: String = "RAVENTEST",
        region: Int = 0x01,
        cgbFlag: Int = 0x00,
        fill: (ByteArray) -> Unit = {},
    ): ByteArray {
        val rom = ByteArray(0x8000 shl romSizeCode)
        for ((i, c) in title.take(15).withIndex()) rom[0x0134 + i] = c.code.toByte()
        rom[0x0143] = cgbFlag.toByte()
        rom[0x0147] = type.toByte()
        rom[0x0148] = romSizeCode.toByte()
        rom[0x0149] = ramSizeCode.toByte()
        rom[0x014A] = region.toByte()
        fill(rom)
        rom[0x014D] = headerChecksum(rom).toByte()
        val global = globalChecksum(rom)
        rom[0x014E] = ((global shr 8) and 0xFF).toByte()
        rom[0x014F] = (global and 0xFF).toByte()
        return rom
    }

    /** ROM dont chaque banque de 16 KiB commence par son numéro de banque. */
    fun buildBankMarked(type: Int, romSizeCode: Int, ramSizeCode: Int = 0): ByteArray =
        build(type = type, romSizeCode = romSizeCode, ramSizeCode = ramSizeCode) { rom ->
            var bank = 0
            var offset = 0
            while (offset < rom.size) {
                rom[offset] = (bank and 0xFF).toByte()
                rom[offset + 1] = ((bank shr 8) and 0xFF).toByte()
                bank++
                offset += 0x4000
            }
        }

    /** Écrit un programme à l'adresse d'entrée 0x0100. */
    fun withProgram(vararg opcodes: Int, type: Int = 0x00): ByteArray =
        build(type = type) { rom ->
            for ((i, op) in opcodes.withIndex()) {
                rom[ENTRY_POINT + i] = (op and 0xFF).toByte()
            }
        }

    fun headerChecksum(rom: ByteArray): Int {
        var chk = 0
        for (i in 0x0134..0x014C) chk = (chk - (rom[i].toInt() and 0xFF) - 1) and 0xFF
        return chk
    }

    fun globalChecksum(rom: ByteArray): Int {
        var sum = 0
        for (i in rom.indices) {
            if (i != 0x014E && i != 0x014F) sum += rom[i].toInt() and 0xFF
        }
        return sum and 0xFFFF
    }
}
