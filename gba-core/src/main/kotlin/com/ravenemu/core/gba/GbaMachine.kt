package com.ravenemu.core.gba

import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.cpu.Arm7Tdmi
import com.ravenemu.core.gba.memory.GbaBus
import com.ravenemu.core.gba.ppu.GbaPpu

/**
 * Machine Game Boy Advance complète, reconstruite à chaque chargement de ROM :
 * cartouche, bus mémoire, CPU ARM7TDMI et unité graphique.
 *
 * Le CPU démarre au point d'entrée fixe de la cartouche (`0x0800_0000`), à
 * l'état laissé par le BIOS (mode système, exécution ARM), sans nécessiter de
 * BIOS Nintendo.
 */
class GbaMachine(rom: ByteArray) {

    val cartridge: GbaCartridge = GbaCartridge.create(rom)
    val bus: GbaBus = GbaBus(cartridge)
    val ppu: GbaPpu = GbaPpu(bus)
    val cpu: Arm7Tdmi = Arm7Tdmi(bus)

    init {
        cpu.reset(ROM_ENTRY_POINT)
    }

    /**
     * Exécute au moins [cycles] cycles CPU (comptage approximatif dans ce
     * premier lot) puis produit la trame vidéo.
     */
    fun runFrame(cycles: Int) {
        var elapsed = 0
        while (elapsed < cycles) {
            elapsed += cpu.step()
        }
        ppu.renderFrame()
    }

    companion object {
        /** Adresse du premier octet de la ROM cartouche, où le CPU démarre. */
        const val ROM_ENTRY_POINT = 0x0800_0000
    }
}
