package com.ravenemu.core.gb.io

/**
 * Emplacement de l'APU (0xFF10–0xFF3F). Cette phase du projet mémorise les
 * registres et la Wave RAM sans produire de son : les jeux peuvent écrire et
 * relire leurs réglages, [com.ravenemu.emulation.api.EmulatorCore.readAudio]
 * retourne 0 échantillon. La synthèse des 4 canaux est livrée dans la phase
 * audio dédiée (limite documentée, voir docs/ARCHITECTURE.md AD-11).
 */
class ApuStub {

    /** Registres bruts 0xFF10–0xFF3F. */
    val registers = ByteArray(0x30)

    fun read(address: Int): Int {
        val offset = address - 0xFF10
        if (offset !in registers.indices) return 0xFF
        // NR52 : bit 7 mémorisé, bits 4-6 câblés à 1, canaux inactifs.
        if (address == 0xFF26) return (registers[offset].toInt() and 0x80) or 0x70
        return registers[offset].toInt() and 0xFF
    }

    fun write(address: Int, value: Int) {
        val offset = address - 0xFF10
        if (offset in registers.indices) registers[offset] = value.toByte()
    }
}
