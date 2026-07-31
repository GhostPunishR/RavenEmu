package com.ravenemu.core.gba.memory

/**
 * Régions du plan mémoire de la Game Boy Advance, identifiées par le quartet de
 * poids fort de l'adresse (bits 24–27). Chaque région indique sa base, sa
 * taille physique et le masque appliqué pour replier les accès dans ses zones
 * miroir.
 *
 * La VRAM fait exception (96 Kio non puissance de deux) : son repliement est
 * traité spécifiquement par [com.ravenemu.core.gba.memory.GbaBus].
 */
enum class MemoryRegion(val base: Int, val size: Int) {
    BIOS(0x0000_0000, 0x4000),
    EWRAM(0x0200_0000, 0x4_0000),
    IWRAM(0x0300_0000, 0x8000),
    IO(0x0400_0000, 0x400),
    PALETTE(0x0500_0000, 0x400),
    VRAM(0x0600_0000, 0x1_8000),
    OAM(0x0700_0000, 0x400),
    ROM(0x0800_0000, 0x0200_0000),
    SRAM(0x0E00_0000, 0x1_0000),
    ;

    /** Masque de repliement dans la zone physique (régions puissances de deux). */
    val mirrorMask: Int get() = size - 1

    companion object {
        /** Région correspondant au quartet de poids fort de [address], ou `null`. */
        fun of(address: Int): MemoryRegion? = when ((address ushr 24) and 0xFF) {
            0x00, 0x01 -> BIOS
            0x02 -> EWRAM
            0x03 -> IWRAM
            0x04 -> IO
            0x05 -> PALETTE
            0x06 -> VRAM
            0x07 -> OAM
            in 0x08..0x0D -> ROM
            0x0E, 0x0F -> SRAM
            else -> null
        }
    }
}
