package com.ravenemu.core.gba.ppu

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.memory.GbaBus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Non-régression de l'**ordre de composition** des arrière-plans, du tri des
 * priorités et des cinq modes vidéo.
 *
 * Le tri par ligne est réalisé sur place dans des tableaux réutilisés (aucune
 * allocation par ligne) : ces tests fixent le résultat visible attendu pour que
 * toute modification du tri ou de la sélection des plans soit détectée.
 */
class PpuLayerOrderTest {

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

    /** Remplit la tuile [tile] du bloc de caractères 1 avec l'index [colorIndex] (4 bpp). */
    private fun fillTile4bpp(bus: GbaBus, tile: Int, colorIndex: Int) {
        val nibbles = (colorIndex and 0xF) or ((colorIndex and 0xF) shl 4)
        for (i in 0 until 32) bus.vram[0x4000 + tile * 32 + i] = nibbles.toByte()
    }

    private fun pixel(ppu: GbaPpu, x: Int, y: Int): Int = ppu.frame[y * 240 + x]

    private val backdrop = 0x0000 // noir
    private val colors = intArrayOf(0x001F, 0x03E0, 0x7C00, 0x7FFF) // R, V, B, blanc

    /**
     * Prépare quatre arrière-plans texte couvrant tout l'écran, chacun avec sa
     * propre carte (blocs d'écran 0 à 3) et sa propre couleur opaque.
     */
    private fun setupFourBackgrounds(bus: GbaBus) {
        palette(bus, 0, backdrop)
        for (bg in 0 until 4) {
            // Chaque plan utilise la tuile `bg + 1`, d'index de couleur `bg + 1`.
            fillTile4bpp(bus, bg + 1, bg + 1)
            palette(bus, bg + 1, colors[bg])
            // Carte de 32 x 32 entrées au bloc d'écran `bg` : toutes les tuiles
            // pointent sur la tuile du plan, donc la ligne entière est opaque.
            val screenBase = bg * 0x800
            for (entry in 0 until 32 * 32) {
                vram16(bus, screenBase + entry * 2, bg + 1)
            }
        }
    }

    /** BGxCNT : priorité, bloc de caractères 1, bloc d'écran = index du plan. */
    private fun bgControl(bus: GbaBus, bg: Int, priority: Int) {
        reg(bus, 0x08 + bg * 2, priority or 0x0004 or (bg shl 8))
    }

    @Test
    fun `le plan de plus haute priorite est visible parmi quatre`() {
        val (bus, ppu) = newPpu()
        setupFourBackgrounds(bus)
        reg(bus, 0x00, 0x0F00) // mode 0, BG0-BG3
        // Priorités volontairement désordonnées : BG2 est le plus proche.
        bgControl(bus, 0, 3)
        bgControl(bus, 1, 2)
        bgControl(bus, 2, 0)
        bgControl(bus, 3, 1)
        ppu.tick(GbaCore.CYCLES_PER_FRAME)
        assertEquals(GbaPpu.bgr555ToArgb(colors[2]), pixel(ppu, 0, 0))
        assertEquals(GbaPpu.bgr555ToArgb(colors[2]), pixel(ppu, 239, 159))
    }

    @Test
    fun `a priorite egale le plan de plus faible index est devant`() {
        val (bus, ppu) = newPpu()
        setupFourBackgrounds(bus)
        reg(bus, 0x00, 0x0F00)
        for (bg in 0 until 4) bgControl(bus, bg, 1) // toutes égales
        ppu.tick(GbaCore.CYCLES_PER_FRAME)
        assertEquals(GbaPpu.bgr555ToArgb(colors[0]), pixel(ppu, 0, 0))
    }

    @Test
    fun `un plan desactive ne participe pas a la composition`() {
        val (bus, ppu) = newPpu()
        setupFourBackgrounds(bus)
        // BG2 aurait la priorité 0 mais n'est pas activé : BG3 doit apparaître.
        reg(bus, 0x00, 0x0B00) // BG0, BG1, BG3
        bgControl(bus, 0, 3)
        bgControl(bus, 1, 2)
        bgControl(bus, 2, 0)
        bgControl(bus, 3, 1)
        ppu.tick(GbaCore.CYCLES_PER_FRAME)
        assertEquals(GbaPpu.bgr555ToArgb(colors[3]), pixel(ppu, 0, 0))
    }

    @Test
    fun `en mode 1 BG3 est ignore meme s'il est active`() {
        val (bus, ppu) = newPpu()
        setupFourBackgrounds(bus)
        // Mode 1 : BG0 et BG1 en texte, BG2 en affine, BG3 inexistant.
        reg(bus, 0x00, 0x0F01)
        bgControl(bus, 0, 2)
        bgControl(bus, 1, 1)
        bgControl(bus, 2, 3)
        bgControl(bus, 3, 0) // priorité la plus forte, mais le plan n'existe pas
        ppu.tick(GbaCore.CYCLES_PER_FRAME)
        assertEquals(
            GbaPpu.bgr555ToArgb(colors[1]),
            pixel(ppu, 0, 0),
            "BG1 est le plan texte le plus proche encore existant",
        )
    }

    @Test
    fun `en mode 2 seuls BG2 et BG3 sont composes`() {
        val (bus, ppu) = newPpu()
        palette(bus, 0, backdrop)
        // Cartes affines : un octet d'index de tuile par entrée, tuiles 8 bpp.
        // BG2 (bloc d'écran 2) et BG3 (bloc d'écran 3) pointent sur des tuiles
        // distinctes du bloc de caractères 1.
        for (entry in 0 until 16 * 16) {
            bus.vram[0x1000 + entry] = 1 // BG2 → tuile 1
            bus.vram[0x1800 + entry] = 2 // BG3 → tuile 2
        }
        for (i in 0 until 64) {
            bus.vram[0x4000 + 1 * 64 + i] = 5 // tuile 1 : index 5
            bus.vram[0x4000 + 2 * 64 + i] = 6 // tuile 2 : index 6
        }
        palette(bus, 5, colors[0])
        palette(bus, 6, colors[1])

        reg(bus, 0x00, 0x0F02) // mode 2, tous les bits de plan levés
        // BG2CNT : priorité 1, char base 1, screen base 2. BG3CNT : priorité 0.
        reg(bus, 0x0C, 1 or 0x0004 or (2 shl 8))
        reg(bus, 0x0E, 0 or 0x0004 or (3 shl 8))
        // Matrices affines identité (PA = PD = 1,0 en 8.8).
        reg(bus, 0x20, 0x0100)
        reg(bus, 0x26, 0x0100)
        reg(bus, 0x30, 0x0100)
        reg(bus, 0x36, 0x0100)
        ppu.tick(GbaCore.CYCLES_PER_FRAME)
        assertEquals(
            GbaPpu.bgr555ToArgb(colors[1]),
            pixel(ppu, 0, 0),
            "BG3 (priorité 0) passe devant BG2",
        )
    }

    @Test
    fun `mode 5 bitmap reduit avec bascule de page`() {
        val (bus, ppu) = newPpu()
        palette(bus, 0, backdrop)
        reg(bus, 0x00, 0x0405) // mode 5 + BG2, page 0
        // Matrice identité, sinon le plan affine ne balaie pas la bitmap.
        reg(bus, 0x20, 0x0100)
        reg(bus, 0x26, 0x0100)
        vram16(bus, 0, colors[0])
        vram16(bus, (3 * 160 + 20) * 2, colors[1])
        ppu.tick(GbaCore.CYCLES_PER_FRAME)
        assertEquals(GbaPpu.bgr555ToArgb(colors[0]), pixel(ppu, 0, 0))
        assertEquals(GbaPpu.bgr555ToArgb(colors[1]), pixel(ppu, 20, 3))
        // Hors des 160 x 128 utiles : fond.
        assertEquals(GbaPpu.bgr555ToArgb(backdrop), pixel(ppu, 200, 3))

        // Page 1 (DISPCNT bit 4) : la seconde moitié de la bitmap.
        reg(bus, 0x00, 0x0415)
        vram16(bus, 0xA000, colors[2])
        ppu.tick(GbaCore.CYCLES_PER_FRAME)
        assertEquals(GbaPpu.bgr555ToArgb(colors[2]), pixel(ppu, 0, 0))
    }

    @Test
    fun `deux trames successives produisent la meme image`() {
        // Garde-fou contre un état de tri résiduel entre lignes ou entre trames :
        // les tableaux d'ordre sont réutilisés, ils doivent être reconstruits à
        // chaque ligne.
        val (bus, ppu) = newPpu()
        setupFourBackgrounds(bus)
        reg(bus, 0x00, 0x0F00)
        bgControl(bus, 0, 3)
        bgControl(bus, 1, 0)
        bgControl(bus, 2, 2)
        bgControl(bus, 3, 1)
        ppu.tick(GbaCore.CYCLES_PER_FRAME)
        val first = ppu.frame.copyOf()
        ppu.tick(GbaCore.CYCLES_PER_FRAME)
        assertEquals(
            first.toList(),
            ppu.frame.toList(),
            "le rendu doit être reproductible d'une trame à l'autre",
        )
    }
}
