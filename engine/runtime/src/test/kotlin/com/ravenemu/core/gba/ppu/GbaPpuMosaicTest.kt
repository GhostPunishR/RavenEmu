package com.ravenemu.core.gba.ppu

import com.ravenemu.core.gba.KotlinGbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.memory.GbaBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Mosaïque de l'affichage Game Boy Advance, côté modèle Kotlin.
 *
 * Le registre `MOSAIC` (0x4000004C) découpe l'image en blocs et fait recopier à
 * chaque bloc la couleur de son coin haut-gauche. Trois activations distinctes
 * le gouvernent, et une seule des trois suffirait à donner l'illusion que
 * l'effet marche : le bit 6 de `BGxCNT` pour un plan, le bit 12 de l'attribut 0
 * pour un objet, et les quatre quartets de `MOSAIC` qui donnent séparément les
 * tailles horizontale et verticale des plans et des objets. Chacune est éprouvée
 * ici, en croisant volontairement les quartets pour qu'une inversion des champs
 * ne puisse pas passer.
 *
 * Ce fichier double les vérifications C++ de `cores/gba/tests/gba_ppu_test.cpp`.
 * Les deux implémentations doivent rester identiques point par point : c'est ce
 * que `NativeParityTest` compare, et une mosaïque présente d'un seul côté ferait
 * diverger la comparaison dès qu'un jeu l'activerait.
 *
 * Le motif de référence est une tuile dont chaque point porte un index de
 * palette différent : deux points voisins ne peuvent donc être de la même
 * couleur que si la mosaïque les a réunis.
 */
class GbaPpuMosaicTest {

    private fun newPpu(): Pair<GbaBus, GbaPpu> {
        val bus = GbaBus(GbaCartridge.create(SyntheticRom.build()))
        val ppu = GbaPpu(bus)
        bus.ppu = ppu
        // Une OAM vierge décrit 128 objets valides empilés en (0,0) : on les
        // écarte tous, les vérifications qui en veulent un le rétablissent.
        for (i in 0 until 128) bus.oam[i * 8 + 1] = 0x02
        return bus to ppu
    }

    private fun reg(bus: GbaBus, offset: Int, value: Int) {
        bus.io[offset] = (value and 0xFF).toByte()
        bus.io[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun oam(bus: GbaBus, offset: Int, value: Int) {
        bus.oam[offset] = (value and 0xFF).toByte()
        bus.oam[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    /**
     * Tuile 8 bpp de 64 octets dont chaque point porte son propre index de
     * palette, de 1 à 64 : aucun voisin n'a la même couleur.
     */
    private fun writeGradientTile(bus: GbaBus, address: Int) {
        for (y in 0 until 8) {
            for (x in 0 until 8) bus.vram[address + y * 8 + x] = (y * 8 + x + 1).toByte()
        }
    }

    private fun writeGradientPalette(bus: GbaBus, base: Int) {
        for (index in 1..64) {
            bus.paletteRam[(base + index) * 2] = (index and 0xFF).toByte()
            bus.paletteRam[(base + index) * 2 + 1] = ((index ushr 8) and 0xFF).toByte()
        }
    }

    private fun renderFrame(ppu: GbaPpu) = ppu.tick(KotlinGbaCore.CYCLES_PER_FRAME)

    private fun pixel(ppu: GbaPpu, x: Int, y: Int): Int = ppu.frame[y * 240 + x]

    /** BG0 en mode texte 8 bpp, carte entière sur la tuile dégradée. */
    private fun setupTextBackground(bus: GbaBus, mosaic: Boolean, horizontalScroll: Int = 0) {
        reg(bus, 0x00, 0x0100)                                  // mode 0, BG0 actif
        reg(bus, 0x08, 0x1080 or if (mosaic) 0x40 else 0)       // 8 bpp, carte en 0x8000
        reg(bus, 0x10, horizontalScroll)                        // BG0HOFS
        writeGradientTile(bus, 0)
        writeGradientPalette(bus, 0)
    }

    /** BG2 affine identité, carte 128x128 répétée, sur la même tuile dégradée. */
    private fun setupAffineBackground(bus: GbaBus, mosaic: Boolean) {
        reg(bus, 0x00, 0x0402)                                  // mode 2, BG2 actif
        reg(bus, 0x0C, 0x2004 or if (mosaic) 0x40 else 0)       // répétition, tuiles en 0x4000
        reg(bus, 0x20, 0x0100)                                  // PA = 1
        reg(bus, 0x22, 0)                                       // PB = 0
        reg(bus, 0x24, 0)                                       // PC = 0
        reg(bus, 0x26, 0x0100)                                  // PD = 1
        writeGradientTile(bus, 0x4000)
        writeGradientPalette(bus, 0)
    }

    /** Objet 32x32 en 8 bpp posé en (0,0), toutes ses tuiles sur le dégradé. */
    private fun setupSprite(bus: GbaBus, mosaic: Boolean) {
        reg(bus, 0x00, 0x1040)                                  // objets actifs, mappage 1D
        oam(bus, 0, 0x2000 or if (mosaic) 0x1000 else 0)        // y = 0, 8 bpp, mosaïque
        oam(bus, 2, 0x8000)                                     // x = 0, taille 32x32
        oam(bus, 4, 0x0000)                                     // tuile 0, priorité 0
        for (tile in 0 until 16) writeGradientTile(bus, 0x10000 + tile * 64)
        writeGradientPalette(bus, 256)
    }

    /**
     * Le même objet, posé sur une scène qui affiche déjà un plan : l'objet
     * couvre le haut de l'écran, le plan reste seul en dessous, ce qui permet
     * de lire les deux couches dans une même image.
     */
    private fun setupSpriteOverBackground(bus: GbaBus, mosaic: Boolean) {
        setupSprite(bus, mosaic)
        reg(bus, 0x00, 0x1140)                                  // mode 0, BG0 et objets
        writeGradientPalette(bus, 0)
    }

    @Test
    fun `un plan sans son bit de mosaique reste net`() {
        val (bus, ppu) = newPpu()
        setupTextBackground(bus, mosaic = false)
        reg(bus, 0x4C, 0x0033)                                  // plans 4x4, objets 1x1
        renderFrame(ppu)
        assertNotEquals(pixel(ppu, 0, 0), pixel(ppu, 1, 0), "MOSAIC seul a groupé des colonnes")
        assertNotEquals(pixel(ppu, 0, 0), pixel(ppu, 0, 1), "MOSAIC seul a groupé des lignes")
    }

    @Test
    fun `la mosaique d un plan texte repete le coin du bloc`() {
        val (bus, ppu) = newPpu()
        setupTextBackground(bus, mosaic = true)
        reg(bus, 0x4C, 0x0033)
        renderFrame(ppu)
        val corner = pixel(ppu, 0, 0)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                assertEquals(corner, pixel(ppu, x, y), "le point ($x, $y) ne reprend pas son coin")
            }
        }
        assertNotEquals(corner, pixel(ppu, 4, 0), "le bloc horizontal suivant n'a pas changé")
        assertNotEquals(corner, pixel(ppu, 0, 4), "le bloc vertical suivant n'a pas changé")
    }

    @Test
    fun `les deux tailles d un plan sont independantes`() {
        val (bus, ppu) = newPpu()
        setupTextBackground(bus, mosaic = true)
        reg(bus, 0x4C, 0x0030)                                  // plans 1 de large, 4 de haut
        renderFrame(ppu)
        assertNotEquals(pixel(ppu, 0, 0), pixel(ppu, 1, 0), "une mosaïque large d'un point a groupé des colonnes")
        assertEquals(pixel(ppu, 0, 0), pixel(ppu, 0, 3), "une mosaïque haute de quatre n'a pas groupé les lignes")
        assertNotEquals(pixel(ppu, 0, 0), pixel(ppu, 0, 4), "la bande verticale déborde de sa hauteur")
    }

    /**
     * La grille des blocs appartient à l'écran, pas au plan : le repère est
     * ramené au coin du bloc avant l'ajout du défilement. Découper après
     * l'addition ferait glisser la grille avec le décor, et les quatre points
     * d'un bloc cesseraient d'être identiques dès que le défilement n'est pas un
     * multiple de la taille du bloc.
     */
    @Test
    fun `la grille de mosaique ne defile pas avec le plan`() {
        val (referenceBus, referencePpu) = newPpu()
        setupTextBackground(referenceBus, mosaic = false)
        renderFrame(referencePpu)
        val expected = pixel(referencePpu, 2, 0)                // colonne source du bloc

        val (bus, ppu) = newPpu()
        setupTextBackground(bus, mosaic = true, horizontalScroll = 2)
        reg(bus, 0x4C, 0x0033)
        renderFrame(ppu)
        for (x in 0 until 4) {
            assertEquals(expected, pixel(ppu, x, 0), "le bloc décalé ne montre pas la colonne source")
        }
        assertNotEquals(expected, pixel(ppu, 4, 0), "le bloc suivant montre encore la colonne du précédent")
    }

    /**
     * Sur un plan affine le parcours avance point par point : la mosaïque ne peut
     * pas se contenter d'arrondir une coordonnée d'écran, il faut retenir celle
     * du début du bloc. Verticalement, cela revient à figer le point de référence
     * interne, qui lui continue d'avancer à chaque ligne.
     */
    @Test
    fun `la mosaique d un plan affine fige le bloc`() {
        val (netBus, netPpu) = newPpu()
        setupAffineBackground(netBus, mosaic = false)
        renderFrame(netPpu)
        assertNotEquals(pixel(netPpu, 0, 0), pixel(netPpu, 1, 0), "précondition : plan affine net non dégradé")
        assertNotEquals(pixel(netPpu, 0, 0), pixel(netPpu, 0, 1), "précondition : plan affine net constant en hauteur")

        val (bus, ppu) = newPpu()
        setupAffineBackground(bus, mosaic = true)
        reg(bus, 0x4C, 0x0033)
        renderFrame(ppu)
        val corner = pixel(ppu, 0, 0)
        assertEquals(pixel(netPpu, 0, 0), corner, "le coin du bloc affine ne montre pas le point d'origine")
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                assertEquals(corner, pixel(ppu, x, y), "le point affine ($x, $y) ne reprend pas son coin")
            }
        }
        assertNotEquals(corner, pixel(ppu, 4, 0), "le bloc affine suivant n'a pas changé")
        assertNotEquals(corner, pixel(ppu, 0, 4), "la bande affine suivante n'a pas changé")
    }

    @Test
    fun `la mosaique d un objet repete le coin du bloc`() {
        val (netBus, netPpu) = newPpu()
        setupSprite(netBus, mosaic = false)
        reg(netBus, 0x4C, 0x3300)
        renderFrame(netPpu)
        assertNotEquals(pixel(netPpu, 0, 0), pixel(netPpu, 1, 0), "précondition : objet sans son bit déjà groupé")

        val (bus, ppu) = newPpu()
        setupSprite(bus, mosaic = true)
        reg(bus, 0x4C, 0x3300)                                  // objets 4x4, plans 1x1
        renderFrame(ppu)
        val corner = pixel(ppu, 0, 0)
        assertEquals(pixel(netPpu, 0, 0), corner, "le coin du bloc de l'objet ne montre pas le point d'origine")
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                assertEquals(corner, pixel(ppu, x, y), "le point ($x, $y) de l'objet ne reprend pas son coin")
            }
        }
        assertNotEquals(corner, pixel(ppu, 4, 0), "le bloc suivant de l'objet n'a pas changé")
        assertNotEquals(corner, pixel(ppu, 0, 4), "la bande suivante de l'objet n'a pas changé")
    }

    /**
     * Les quartets des plans et ceux des objets vivent dans le même registre :
     * chaque paire ne doit agir que sur sa couche. La scène porte les deux, et
     * chaque activation est lue sur la couche qu'elle vise comme sur l'autre.
     */
    @Test
    fun `les mosaiques des plans et des objets ne se melangent pas`() {
        val (objetBus, objetPpu) = newPpu()
        setupTextBackground(objetBus, mosaic = true)
        setupSpriteOverBackground(objetBus, mosaic = true)
        reg(objetBus, 0x4C, 0x3300)                             // objets 4x4, plans 1x1
        renderFrame(objetPpu)
        assertEquals(pixel(objetPpu, 0, 0), pixel(objetPpu, 1, 0), "les quartets des objets n'ont pas groupé l'objet")
        assertNotEquals(pixel(objetPpu, 0, 100), pixel(objetPpu, 1, 100), "les quartets des objets ont groupé le plan")

        val (planBus, planPpu) = newPpu()
        setupTextBackground(planBus, mosaic = true)
        setupSpriteOverBackground(planBus, mosaic = true)
        reg(planBus, 0x4C, 0x0033)                              // plans 4x4, objets 1x1
        renderFrame(planPpu)
        assertNotEquals(pixel(planPpu, 0, 0), pixel(planPpu, 1, 0), "les quartets des plans ont groupé l'objet")
        assertEquals(pixel(planPpu, 0, 100), pixel(planPpu, 1, 100), "les quartets des plans n'ont pas groupé le plan")
    }
}
