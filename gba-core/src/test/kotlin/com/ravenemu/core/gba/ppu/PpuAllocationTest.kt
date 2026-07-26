package com.ravenemu.core.gba.ppu

import com.ravenemu.core.gba.AllocationProbe
import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.memory.GbaBus
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Le rendu d'une trame appelle la composition 160 fois. Toute collection créée
 * par ligne — un `filter`, un `sortedByDescending` — produit près de dix mille
 * objets par seconde, dans le chemin le plus chaud de l'émulateur.
 */
class PpuAllocationTest {

    private fun scene(): Pair<GbaBus, GbaPpu> {
        val bus = GbaBus(GbaCartridge.create(SyntheticRom.build()))
        val ppu = GbaPpu(bus)
        bus.ppu = ppu

        fun reg(offset: Int, value: Int) {
            bus.io[offset] = (value and 0xFF).toByte()
            bus.io[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        // Quatre arrière-plans texte opaques, priorités désordonnées pour que le
        // tri ait réellement du travail à chaque ligne.
        reg(0x00, 0x1F00) // mode 0, BG0-BG3, OBJ
        val priorities = intArrayOf(3, 1, 0, 2)
        for (bg in 0 until 4) {
            reg(0x08 + bg * 2, priorities[bg] or 0x0004 or (bg shl 8))
            for (i in 0 until 32) bus.vram[0x4000 + (bg + 1) * 32 + i] = 0x11
            for (entry in 0 until 32 * 32) {
                val addr = bg * 0x800 + entry * 2
                bus.vram[addr] = (bg + 1).toByte()
                bus.vram[addr + 1] = 0
            }
        }
        for (index in 0 until 8) {
            bus.paletteRam[index * 2] = (index * 17).toByte()
            bus.paletteRam[index * 2 + 1] = 0x3F
        }
        // 128 sprites répartis sur l'écran, plus les fenêtres et le mélange.
        for (i in 0 until 128) {
            val base = i * 8
            fun oam(offset: Int, value: Int) {
                bus.oam[base + offset] = (value and 0xFF).toByte()
                bus.oam[base + offset + 1] = ((value ushr 8) and 0xFF).toByte()
            }
            oam(0, (i % 160) or 0x2000)           // Y, 8 bpp
            oam(2, ((i * 2) % 240) or 0x4000)     // X, taille 2
            oam(4, 2 or ((i % 4) shl 10))         // tuile 2, priorité tournante
        }
        for (i in 0 until 64) bus.vram[0x1_0000 + 2 * 64 + i] = 3
        reg(0x40, 0x1020) // WIN0H
        reg(0x44, 0x1020) // WIN0V
        reg(0x48, 0x3F3F) // WININ
        reg(0x4A, 0x3F3F) // WINOUT
        reg(0x50, 0x3FFF) // BLDCNT : toutes les couches, mélange alpha
        reg(0x52, 0x0808) // BLDALPHA
        return bus to ppu
    }

    @Test
    fun `le rendu d'une trame chargee n'alloue rien`() {
        val (_, ppu) = scene()
        val allocated = AllocationProbe.measure { ppu.tick(GbaCore.CYCLES_PER_FRAME) }
        println("ALLOC[ppu]: $allocated octets par trame")
        if (!AllocationProbe.supported) return // JVM sans mesure par fil

        // Un `filter` + un `sortedByDescending` par ligne coûtaient à eux seuls
        // plus de 100 Kio par trame.
        assertTrue(
            allocated < 8192,
            "le rendu d'une trame a alloué $allocated octets (attendu ~0)",
        )
    }

    @Test
    fun `debit du rendu graphique`() {
        val (_, ppu) = scene()
        val frames = 120
        repeat(30) { ppu.tick(GbaCore.CYCLES_PER_FRAME) } // échauffement
        val start = System.nanoTime()
        repeat(frames) { ppu.tick(GbaCore.CYCLES_PER_FRAME) }
        val msPerFrame = (System.nanoTime() - start) / 1_000_000.0 / frames
        println("PERF[ppu]: %.3f ms de rendu par trame (4 BG + 128 sprites)".format(msPerFrame))
        assertTrue(msPerFrame < 20.0, "rendu anormalement lent : $msPerFrame ms/trame")
    }
}
