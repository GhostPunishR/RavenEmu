package com.ravenemu.core.gba.ppu

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.memory.GbaBus
import kotlin.test.Test
import kotlin.test.assertEquals

class GbaSpriteTest {

    private val green = 0x03E0
    private val red = 0x001F
    private val blue = 0x7C00

    private fun newPpu(): Pair<GbaBus, GbaPpu> {
        val bus = GbaBus(GbaCartridge.create(SyntheticRom.build()))
        val ppu = GbaPpu(bus)
        bus.ppu = ppu
        // OAM vierge = 128 sprites valides en (0,0) : on les masque, comme un
        // jeu le fait au démarrage (attr0 mode objet = 2, caché).
        for (i in 0 until 128) bus.oam[i * 8 + 1] = 0x02
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

    private fun sprite(bus: GbaBus, i: Int, attr0: Int, attr1: Int, attr2: Int) {
        val b = i * 8
        bus.oam[b] = (attr0 and 0xFF).toByte()
        bus.oam[b + 1] = ((attr0 ushr 8) and 0xFF).toByte()
        bus.oam[b + 2] = (attr1 and 0xFF).toByte()
        bus.oam[b + 3] = ((attr1 ushr 8) and 0xFF).toByte()
        bus.oam[b + 4] = (attr2 and 0xFF).toByte()
        bus.oam[b + 5] = ((attr2 ushr 8) and 0xFF).toByte()
    }

    /** Remplit une tuile OBJ 4 bpp (32 octets) avec un même index de couleur. */
    private fun objTile4bpp(bus: GbaBus, tile: Int, colorIndex: Int) {
        val fill = (colorIndex or (colorIndex shl 4)).toByte()
        for (k in 0 until 32) bus.vram[0x1_0000 + tile * 32 + k] = fill
    }

    private fun renderFrame(ppu: GbaPpu) = ppu.tick(GbaCore.CYCLES_PER_FRAME)

    private fun pixel(ppu: GbaPpu, x: Int, y: Int): Int = ppu.frame[y * 240 + x]

    @Test
    fun `un sprite 8x8 est dessine a sa position`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x1000)              // OBJ activés, 2D
        palette(bus, 0, blue)               // fond
        palette(bus, 256 + 1, green)        // couleur OBJ index 1
        objTile4bpp(bus, 0, 1)
        sprite(bus, 0, attr0 = 30, attr1 = 20, attr2 = 0) // y=30, x=20, tuile 0
        renderFrame(ppu)

        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 20, 30))
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 27, 37))
        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 19, 30)) // hors sprite
        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 28, 30))
    }

    @Test
    fun `un sprite prioritaire passe devant l'arriere-plan`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x1100)              // mode 0 + BG0 + OBJ
        reg(bus, 0x08, 0x0005)              // BG0CNT : char base 1, priorité 1
        bus.vram[0x0000] = 0x01; bus.vram[0x0001] = 0x00 // carte tuile (0,0) = 1
        for (k in 0 until 32) bus.vram[0x4020 + k] = 0x11 // tuile BG 1 → index 1
        palette(bus, 1, red)                // BG rouge
        palette(bus, 256 + 1, green)        // OBJ vert
        objTile4bpp(bus, 0, 1)
        sprite(bus, 0, attr0 = 0, attr1 = 0, attr2 = 0) // priorité 0
        renderFrame(ppu)

        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0)) // sprite devant
    }

    @Test
    fun `un sprite de priorite inferieure passe derriere l'arriere-plan`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x1100)
        reg(bus, 0x08, 0x0004)              // BG0CNT : char base 1, priorité 0
        bus.vram[0x0000] = 0x01
        for (k in 0 until 32) bus.vram[0x4020 + k] = 0x11
        palette(bus, 1, red)
        palette(bus, 256 + 1, green)
        objTile4bpp(bus, 0, 1)
        sprite(bus, 0, attr0 = 0, attr1 = 0, attr2 = 2 shl 10) // priorité 2
        renderFrame(ppu)

        assertEquals(GbaPpu.bgr555ToArgb(red), pixel(ppu, 0, 0)) // BG devant
    }

    @Test
    fun `le retournement horizontal inverse le sprite`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x1000)
        palette(bus, 0, blue)
        palette(bus, 256 + 1, green)
        // Tuile 0 : colonne 0 = index 1, reste = 0 (transparent).
        for (k in 0 until 32) bus.vram[0x1_0000 + k] = 0
        for (rowY in 0 until 8) bus.vram[0x1_0000 + rowY * 4] = 0x01 // pixel (0,y) = index 1
        sprite(bus, 0, attr0 = 0, attr1 = 0x1000, attr2 = 0) // hflip (attr1 bit12)
        renderFrame(ppu)

        // Sans flip la colonne peinte serait x=0 ; avec hflip elle passe en x=7.
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 7, 0))
        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 0, 0))
    }

    @Test
    fun `un sprite 8 bpp lit la palette OBJ directement`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x1000)
        palette(bus, 0, blue)
        palette(bus, 256 + 5, green)
        // Tuile 8 bpp occupe 64 octets ; index 5 partout.
        for (k in 0 until 64) bus.vram[0x1_0000 + k] = 5
        sprite(bus, 0, attr0 = 0x2000, attr1 = 0, attr2 = 0) // attr0 bit13 = 8 bpp
        renderFrame(ppu)

        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))
    }

    @Test
    fun `le sprite d'index OAM inferieur passe devant en cas de recouvrement`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x1000)
        palette(bus, 0, blue)
        palette(bus, 256 + 1, green)
        palette(bus, 256 + 2, red)
        objTile4bpp(bus, 0, 1) // vert
        objTile4bpp(bus, 1, 2) // rouge
        // Deux sprites même priorité, même position ; index 0 (vert) doit gagner.
        sprite(bus, 0, attr0 = 0, attr1 = 0, attr2 = 0)
        sprite(bus, 1, attr0 = 0, attr1 = 0, attr2 = 1)
        renderFrame(ppu)

        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))
    }

    @Test
    fun `un sprite 16x16 couvre toute sa surface`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x1000)              // 2D mapping
        palette(bus, 0, blue)
        palette(bus, 256 + 1, green)
        // 4 tuiles (0..3) pour un carré 16×16 ; en 2D la 2e ligne est à +32.
        objTile4bpp(bus, 0, 1)
        objTile4bpp(bus, 1, 1)
        objTile4bpp(bus, 32, 1)
        objTile4bpp(bus, 33, 1)
        sprite(bus, 0, attr0 = 0, attr1 = 0x4000, attr2 = 0) // taille 1 (16×16) carré
        renderFrame(ppu)

        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 15, 15))
        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 16, 0))
    }

    @Test
    fun `un sprite partiellement hors de l'ecran a gauche est tronque`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x1000)
        palette(bus, 0, blue)
        palette(bus, 256 + 1, green)
        objTile4bpp(bus, 0, 1)
        // X = -4 (codé 0x1FC sur 9 bits) : seule la moitié droite est visible.
        sprite(bus, 0, attr0 = 0, attr1 = 0x1FC, attr2 = 0)
        renderFrame(ppu)

        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0)) // colonne 4 du sprite
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 3, 0))
    }
}
