package com.ravenemu.core.gba.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Temps d'attente par région. Les valeurs attendues sont exprimées en **cycles
 * totaux** (un cycle de base plus les temps d'attente), pour être comparables à
 * la documentation matérielle.
 */
class MemoryTimingTest {

    private val timing = MemoryTiming()

    private val bios = 0x0000_0000
    private val ewram = 0x0200_0000
    private val iwram = 0x0300_0000
    private val io = 0x0400_0000
    private val palette = 0x0500_0000
    private val vram = 0x0600_0000
    private val oam = 0x0700_0000
    private val rom0 = 0x0800_0000
    private val rom1 = 0x0A00_0000
    private val rom2 = 0x0C00_0000
    private val sram = 0x0E00_0000

    /** Cycles totaux d'un accès de [width] octets. */
    private fun cycles(address: Int, width: Int, sequential: Boolean): Int =
        1 + timing.waitStates(address, width, sequential)

    @Test
    fun `les regions du bus interne repondent en un cycle`() {
        for (address in listOf(bios, iwram, io, oam)) {
            for (width in listOf(1, 2, 4)) {
                assertEquals(1, cycles(address, width, sequential = false))
                assertEquals(1, cycles(address, width, sequential = true))
            }
        }
    }

    @Test
    fun `l'EWRAM coute trois cycles en 16 bits et six en 32 bits`() {
        assertEquals(3, cycles(ewram, 1, sequential = false))
        assertEquals(3, cycles(ewram, 2, sequential = false))
        assertEquals(3, cycles(ewram, 2, sequential = true))
        // Bus 16 bits : un mot demande deux accès de trois cycles.
        assertEquals(6, cycles(ewram, 4, sequential = false))
        assertEquals(6, cycles(ewram, 4, sequential = true))
    }

    @Test
    fun `la palette et la VRAM coutent un cycle en 16 bits et deux en 32 bits`() {
        for (address in listOf(palette, vram)) {
            assertEquals(1, cycles(address, 2, sequential = false))
            assertEquals(2, cycles(address, 4, sequential = false))
            assertEquals(2, cycles(address, 4, sequential = true))
        }
        // L'OAM est sur un bus 32 bits : un mot y coûte un seul cycle.
        assertEquals(1, cycles(oam, 4, sequential = false))
    }

    @Test
    fun `la ROM distingue premier acces et acces suivants`() {
        // WAITCNT à zéro : zone 0 en 4 cycles d'attente pour N, 2 pour S.
        assertEquals(5, cycles(rom0, 2, sequential = false), "premier accès 16 bits")
        assertEquals(3, cycles(rom0, 2, sequential = true), "accès suivant 16 bits")
        // Un mot est lu en deux accès : N puis S.
        assertEquals(8, cycles(rom0, 4, sequential = false))
        assertEquals(6, cycles(rom0, 4, sequential = true))
    }

    @Test
    fun `WAITCNT accelere le premier acces de la zone 0`() {
        // Bits 2-3 = 2 : deux cycles d'attente au lieu de quatre.
        timing.waitControl = 0x0008
        assertEquals(3, cycles(rom0, 2, sequential = false))
        // Bit 4 : accès suivants à un seul cycle d'attente.
        timing.waitControl = 0x0008 or 0x0010
        assertEquals(2, cycles(rom0, 2, sequential = true))
        assertEquals(5, cycles(rom0, 4, sequential = false), "N + S + un cycle de base")
        assertEquals(4, cycles(rom0, 4, sequential = true), "deux accès S")
    }

    @Test
    fun `les zones d'attente sont independantes`() {
        // Zone 0 rapide, zone 1 par défaut, zone 2 lente.
        timing.waitControl = 0x0008 or 0x0010 or (0x3 shl 8)
        assertEquals(3, cycles(rom0, 2, sequential = false), "zone 0 : 2 cycles d'attente")
        assertEquals(5, cycles(rom1, 2, sequential = false), "zone 1 inchangée")
        assertEquals(9, cycles(rom2, 2, sequential = false), "zone 2 : 8 cycles d'attente")
    }

    @Test
    fun `la SRAM est sur un bus 8 bits`() {
        assertEquals(5, cycles(sram, 1, sequential = false))
        assertEquals(10, cycles(sram, 2, sequential = false), "deux accès d'octet")
        assertEquals(20, cycles(sram, 4, sequential = false), "quatre accès d'octet")
        // Bits 0-1 = 2 : deux cycles d'attente.
        timing.waitControl = 0x0002
        assertEquals(3, cycles(sram, 1, sequential = false))
    }

    @Test
    fun `le prechargement Game Pak sert les lectures sequentielles`() {
        assertFalse(timing.prefetchEnabled)
        // Sans préchargement, une lecture d'instruction séquentielle paie S.
        assertEquals(6, 1 + timing.instructionWaitStates(rom0, 4, sequential = true))

        timing.waitControl = 0x4000
        assertTrue(timing.prefetchEnabled)
        assertEquals(
            1,
            1 + timing.instructionWaitStates(rom0, 4, sequential = true),
            "la file sert l'instruction suivante sans attente",
        )
        // Un premier accès reste facturé : la file est vide après un saut.
        assertEquals(8, 1 + timing.instructionWaitStates(rom0, 4, sequential = false))
        // Le préchargement ne concerne pas les accès aux données.
        assertEquals(6, cycles(rom0, 4, sequential = true))
        // Ni les autres régions.
        assertEquals(6, 1 + timing.instructionWaitStates(ewram, 4, sequential = true))
    }

    @Test
    fun `le couple sequentiel non sequentiel est expose`() {
        val rom = timing.timing16(rom0)
        assertEquals(MemoryAccessTiming(sequential = 2, nonSequential = 4), rom)
        assertEquals(2, rom.waitStates(sequential = true))
        assertEquals(4, rom.waitStates(sequential = false))
        assertEquals(MemoryAccessTiming(2, 2), timing.timing16(ewram))
        assertEquals(MemoryAccessTiming.NONE, timing.timing16(iwram))
    }

    @Test
    fun `la largeur du bus est correcte par region`() {
        assertEquals(4, timing.busWidth(iwram))
        assertEquals(4, timing.busWidth(oam))
        assertEquals(2, timing.busWidth(ewram))
        assertEquals(2, timing.busWidth(vram))
        assertEquals(2, timing.busWidth(rom0))
        assertEquals(1, timing.busWidth(sram))
    }
}
