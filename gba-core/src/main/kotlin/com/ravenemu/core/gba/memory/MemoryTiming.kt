package com.ravenemu.core.gba.memory

/**
 * Modèle de temps d'attente mémoire de la Game Boy Advance.
 *
 * Toutes les régions ne répondent pas à la même vitesse : le CPU, la BIOS,
 * l'IWRAM, les registres d'E/S et l'OAM sont sur le bus interne 32 bits et
 * répondent en un cycle, tandis que l'EWRAM, la palette, la VRAM et la cartouche
 * passent par un bus 16 bits — un accès 32 bits y coûte donc deux accès. La
 * cartouche ajoute par-dessus les temps d'attente programmables de `WAITCNT`.
 *
 * Les valeurs retournées sont des **cycles supplémentaires** : un accès coûte
 * toujours au moins un cycle, et ce modèle indique ce qui s'y ajoute.
 *
 * ### `WAITCNT` (0x0400_0204)
 * | bits | rôle |
 * |---|---|
 * | 0-1 | temps d'attente SRAM |
 * | 2-3 | premier accès (`N`) de la zone d'attente 0 |
 * | 4 | accès suivant (`S`) de la zone 0 |
 * | 5-6 / 7 | zone 1 : `N` / `S` |
 * | 8-9 / 10 | zone 2 : `N` / `S` |
 * | 14 | file de préchargement Game Pak |
 *
 * Les zones d'attente correspondent aux fenêtres 0x0800_0000 (zone 0),
 * 0x0A00_0000 (zone 1) et 0x0C00_0000 (zone 2) : un même jeu peut cadencer
 * différemment plusieurs fenêtres de sa cartouche.
 *
 * ### Limite documentée
 * Le préchargement Game Pak est modélisé de façon simplifiée : quand il est
 * actif, une lecture d'instruction séquentielle en ROM est servie par la file en
 * un cycle. La file réelle se remplit pendant les cycles internes du CPU et peut
 * donc être vide juste après un saut ; ce modèle ne suit pas son remplissage,
 * il estime donc légèrement à la baisse le coût du code exécuté depuis la
 * cartouche.
 */
class MemoryTiming {

    /**
     * Registre `WAITCNT`. Le bit 15 (type de cartouche) est en lecture seule sur
     * le matériel ; il est conservé tel quel ici, il n'influe sur aucun calcul.
     */
    var waitControl = 0

    /** `true` si la file de préchargement Game Pak est activée (bit 14). */
    val prefetchEnabled: Boolean get() = waitControl and 0x4000 != 0

    /**
     * Largeur du bus desservant la région de [address], en octets. Un accès plus
     * large que le bus est découpé en plusieurs accès matériels.
     */
    fun busWidth(address: Int): Int = when ((address ushr 24) and 0xFF) {
        0x02, 0x05, 0x06 -> 2       // EWRAM, palette, VRAM
        in 0x08..0x0D -> 2          // cartouche
        0x0E, 0x0F -> 1             // SRAM / Flash : bus 8 bits
        else -> 4                   // BIOS, IWRAM, E/S, OAM : bus interne
    }

    /** Temps d'attente d'un accès unitaire (au plus la largeur du bus). */
    fun unitTiming(address: Int): MemoryAccessTiming = when ((address ushr 24) and 0xFF) {
        0x02 -> EWRAM_TIMING
        in 0x08..0x0D -> romTiming(address)
        0x0E, 0x0F -> sramTiming()
        // BIOS, IWRAM, E/S, palette, VRAM, OAM : un cycle, sans attente.
        else -> MemoryAccessTiming.NONE
    }

    /**
     * Temps d'attente d'un accès de [width] octets. Un bus plus étroit que
     * l'accès impose plusieurs accès matériels : le premier de la nature
     * demandée, les suivants séquentiels.
     */
    fun timing(address: Int, width: Int): MemoryAccessTiming {
        val unit = unitTiming(address)
        val parts = width / busWidth(address)
        return if (parts <= 1) unit else split(unit, parts)
    }

    /** Temps d'attente d'un accès 8 ou 16 bits dans la région de [address]. */
    fun timing16(address: Int): MemoryAccessTiming = timing(address, 2)

    /** Temps d'attente d'un accès 32 bits dans la région de [address]. */
    fun timing32(address: Int): MemoryAccessTiming = timing(address, 4)

    /**
     * Coût d'un accès découpé en [parts] accès matériels : le premier garde la
     * nature demandée, les suivants sont séquentiels, et chaque accès
     * supplémentaire ajoute son propre cycle de base.
     */
    private fun split(unit: MemoryAccessTiming, parts: Int): MemoryAccessTiming {
        val extra = parts - 1
        return MemoryAccessTiming(
            sequential = unit.sequential * parts + extra,
            nonSequential = unit.nonSequential + unit.sequential * extra + extra,
        )
    }

    /**
     * Temps d'attente d'un accès de [width] octets à [address], selon qu'il est
     * [sequential] ou non.
     */
    fun waitStates(address: Int, width: Int, sequential: Boolean): Int =
        timing(address, width).waitStates(sequential)

    /**
     * Temps d'attente d'une **lecture d'instruction** de [width] octets. Le
     * préchargement Game Pak sert les lectures séquentielles en ROM sans attente.
     */
    fun instructionWaitStates(address: Int, width: Int, sequential: Boolean): Int {
        val isRom = (address ushr 24) and 0xFF in 0x08..0x0D
        if (isRom && sequential && prefetchEnabled) return 0
        return waitStates(address, width, sequential)
    }

    /** Temps d'attente de la fenêtre cartouche contenant [address]. */
    private fun romTiming(address: Int): MemoryAccessTiming {
        // Deux fenêtres de 32 Mio par zone d'attente : 0x08/0x09, 0x0A/0x0B,
        // 0x0C/0x0D. La fenêtre 0x0D est celle de l'EEPROM, cadencée comme la
        // zone 2.
        return when ((address ushr 25) and 0x3) {
            0x0 -> waitZone0()
            0x1 -> waitZone1()
            else -> waitZone2()
        }
    }

    private fun waitZone0() = MemoryAccessTiming(
        sequential = if (waitControl and 0x0010 != 0) WS0_S_FAST else WS0_S_SLOW,
        nonSequential = N_WAIT[(waitControl ushr 2) and 0x3],
    )

    private fun waitZone1() = MemoryAccessTiming(
        sequential = if (waitControl and 0x0080 != 0) WS1_S_FAST else WS1_S_SLOW,
        nonSequential = N_WAIT[(waitControl ushr 5) and 0x3],
    )

    private fun waitZone2() = MemoryAccessTiming(
        sequential = if (waitControl and 0x0400 != 0) WS2_S_FAST else WS2_S_SLOW,
        nonSequential = N_WAIT[(waitControl ushr 8) and 0x3],
    )

    /** La SRAM est sur un bus 8 bits : même temps d'attente dans les deux sens. */
    private fun sramTiming(): MemoryAccessTiming {
        val wait = N_WAIT[waitControl and 0x3]
        return MemoryAccessTiming(wait, wait)
    }

    fun reset() {
        waitControl = 0
    }

    companion object {
        /** L'EWRAM est un bus 16 bits avec deux cycles d'attente câblés. */
        private val EWRAM_TIMING = MemoryAccessTiming(2, 2)

        /** Temps d'attente d'un premier accès cartouche : 4, 3, 2 ou 8 cycles. */
        private val N_WAIT = intArrayOf(4, 3, 2, 8)

        // Accès suivants, selon le bit `S` de chaque zone d'attente.
        private const val WS0_S_SLOW = 2
        private const val WS0_S_FAST = 1
        private const val WS1_S_SLOW = 4
        private const val WS1_S_FAST = 1
        private const val WS2_S_SLOW = 8
        private const val WS2_S_FAST = 1

        /** Offset du registre `WAITCNT` dans la région d'E/S. */
        const val WAITCNT_OFFSET = 0x204
    }
}
