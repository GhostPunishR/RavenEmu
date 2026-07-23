package com.ravenemu.core.gba.ppu

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.memory.GbaBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GbaPpuTest {

    private fun newPpu(): Pair<GbaBus, GbaPpu> {
        val bus = GbaBus(GbaCartridge.create(SyntheticRom.build()))
        val ppu = GbaPpu(bus)
        bus.ppu = ppu
        return bus to ppu
    }

    private fun reg(bus: GbaBus, offset: Int, value: Int) {
        bus.io[offset] = (value and 0xFF).toByte()
        bus.io[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun palette(bus: GbaBus, index: Int, color: Int) {
        bus.paletteRam[index * 2] = (color and 0xFF).toByte()
        bus.paletteRam[index * 2 + 1] = ((color ushr 8) and 0xFF).toByte()
    }

    private fun vram16(bus: GbaBus, addr: Int, value: Int) {
        bus.vram[addr] = (value and 0xFF).toByte()
        bus.vram[addr + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun renderFrame(ppu: GbaPpu) = ppu.tick(GbaCore.CYCLES_PER_FRAME)

    private fun pixel(ppu: GbaPpu, x: Int, y: Int): Int = ppu.frame[y * 240 + x]

    private val red = 0x001F   // BGR555 rouge
    private val green = 0x03E0 // BGR555 vert
    private val blue = 0x7C00  // BGR555 bleu

    @Test
    fun `fond uni quand aucun arriere-plan n'est actif`() {
        val (bus, ppu) = newPpu()
        palette(bus, 0, red)
        reg(bus, 0x00, 0x0000) // mode 0, aucun BG
        renderFrame(ppu)
        assertTrue(ppu.frame.all { it == GbaPpu.bgr555ToArgb(red) })
    }

    @Test
    fun `l'ecran blanc force remplit de blanc`() {
        val (bus, ppu) = newPpu()
        palette(bus, 0, red)
        reg(bus, 0x00, 0x0080) // DISPCNT bit 7 : blanc forcé
        renderFrame(ppu)
        assertEquals(0xFFFFFFFF.toInt(), pixel(ppu, 0, 0))
        assertEquals(0xFFFFFFFF.toInt(), pixel(ppu, 239, 159))
    }

    @Test
    fun `mode 3 bitmap 16 bpp`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x0403) // mode 3 + BG2
        vram16(bus, 0, blue)
        vram16(bus, (5 * 240 + 10) * 2, green)
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 0, 0))
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 10, 5))
    }

    @Test
    fun `mode 4 bitmap paletté avec bascule de page`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x0404) // mode 4 + BG2, page 0
        palette(bus, 7, green)
        bus.vram[0] = 7
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))

        // Page 1 (DISPCNT bit 4).
        reg(bus, 0x00, 0x0414)
        palette(bus, 9, blue)
        bus.vram[0xA000] = 9
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 0, 0))
    }

    @Test
    fun `mode 0 arriere-plan texte 4 bpp`() {
        val (bus, ppu) = newPpu()
        // BG0 : char base bloc 1 (0x4000), screen base bloc 0, 4 bpp, taille 0.
        reg(bus, 0x00, 0x0100)       // mode 0 + BG0
        reg(bus, 0x08, 0x0004)       // BG0CNT : char base = 1
        // Carte : tuile (0,0) = tuile 1.
        vram16(bus, 0x0000, 0x0001)
        // Données de la tuile 1 (0x4020) : tous pixels = index 1.
        for (i in 0 until 32) bus.vram[0x4020 + i] = 0x11
        palette(bus, 1, green)
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 7, 7))
    }

    @Test
    fun `le defilement horizontal decale l'arriere-plan`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x0100)
        reg(bus, 0x08, 0x0004)
        // Seule la tuile (1,0) porte la tuile 1 ; le reste est transparent (tuile 0).
        vram16(bus, (0 * 32 + 1) * 2, 0x0001)
        for (i in 0 until 32) bus.vram[0x4020 + i] = 0x11
        palette(bus, 0, red)   // fond
        palette(bus, 1, green) // tuile 1
        // Sans défilement : pixel (0,0) montre le fond.
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(red), pixel(ppu, 0, 0))
        // Défilement de 8 pixels : la tuile (1,0) arrive en (0,0).
        reg(bus, 0x10, 8) // BG0HOFS
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))
    }

    @Test
    fun `la priorite place l'arriere-plan de plus faible numero devant`() {
        val (bus, ppu) = newPpu()
        // BG0 priorité 0 (devant, vert), BG1 priorité 1 (derrière, bleu).
        reg(bus, 0x00, 0x0300)  // mode 0 + BG0 + BG1
        reg(bus, 0x08, 0x0004)  // BG0CNT : char base 1, priorité 0
        reg(bus, 0x0A, 0x0005)  // BG1CNT : char base 1, priorité 1
        // BG0 carte au screen base 0, BG1 au screen base 1 (0x0800).
        vram16(bus, 0x0000, 0x0001)          // BG0 tuile (0,0) = tuile 1
        vram16(bus, 0x0800, 0x0002)          // BG1 tuile (0,0) = tuile 2
        reg(bus, 0x0A, 0x0105)               // BG1CNT : + screen base 1
        for (i in 0 until 32) bus.vram[0x4020 + i] = 0x11 // tuile 1 → index 1
        for (i in 0 until 32) bus.vram[0x4040 + i] = 0x22 // tuile 2 → index 2
        palette(bus, 1, green)
        palette(bus, 2, blue)
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))
    }

    @Test
    fun `VCOUNT et le drapeau VBlank progressent`() {
        val (bus, ppu) = newPpu()
        ppu.tick(160 * 1232) // 160 lignes
        assertEquals(160, ppu.vcount)
        assertTrue(ppu.inVBlank)
        // Lisible via le bus (VCOUNT et DISPSTAT).
        assertEquals(160, bus.read8(0x0400_0006))
        assertTrue(bus.read8(0x0400_0004) and 0x01 != 0) // bit VBlank
    }

    @Test
    fun `la coincidence VCount est signalee`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x04, 0x5000) // DISPSTAT : VCount visé = 0x50 (80)
        ppu.tick(80 * 1232)
        assertEquals(80, ppu.vcount)
        assertTrue(ppu.vcountMatch)
        assertTrue(bus.read8(0x0400_0004) and 0x04 != 0)
    }
}
