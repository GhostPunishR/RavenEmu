package com.ravenemu.core.gba.ppu

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.memory.GbaBus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Arrière-plans affines, sprites affines, fenêtres et effets de couleur. */
class GbaPpuEffectsTest {

    private val red = 0x001F
    private val green = 0x03E0
    private val blue = 0x7C00

    private fun newPpu(): Pair<GbaBus, GbaPpu> {
        val bus = GbaBus(GbaCartridge.create(SyntheticRom.build()))
        val ppu = GbaPpu(bus)
        bus.ppu = ppu
        // OAM vierge = 128 sprites valides en (0,0) : on les masque.
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

    private fun renderFrame(ppu: GbaPpu) = ppu.tick(GbaCore.CYCLES_PER_FRAME)

    private fun pixel(ppu: GbaPpu, x: Int, y: Int): Int = ppu.frame[y * 240 + x]

    /** Prépare un BG affine (BG2) identité dont toute la carte pointe la tuile 1. */
    private fun setupAffineBg(bus: GbaBus, wrap: Boolean) {
        reg(bus, 0x00, 0x0402)                        // mode 2 + BG2
        reg(bus, 0x0C, 0x0004 or if (wrap) 0x2000 else 0) // charBase 1, taille 128px
        reg(bus, 0x20, 0x0100)                        // PA = 1.0
        reg(bus, 0x22, 0)                             // PB = 0
        reg(bus, 0x24, 0)                             // PC = 0
        reg(bus, 0x26, 0x0100)                        // PD = 1.0
        // Carte affine : 1 octet par tuile, 16×16 tuiles, toutes = tuile 1.
        for (i in 0 until 256) bus.vram[i] = 1
        // Tuile 1 en 8 bpp (64 octets) remplie de l'index 1.
        for (i in 0 until 64) bus.vram[0x4040 + i] = 1
        palette(bus, 0, blue)   // fond
        palette(bus, 1, green)  // tuile
    }

    @Test
    fun `arriere-plan affine en transformation identite`() {
        val (bus, ppu) = newPpu()
        setupAffineBg(bus, wrap = false)
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 100, 100))
    }

    @Test
    fun `hors de la carte affine sans repetition le fond apparait`() {
        val (bus, ppu) = newPpu()
        setupAffineBg(bus, wrap = false)
        renderFrame(ppu)
        // La carte fait 128 px de côté : au-delà, rien n'est dessiné.
        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 200, 0))
    }

    @Test
    fun `la repetition affine reboucle la carte`() {
        val (bus, ppu) = newPpu()
        setupAffineBg(bus, wrap = true)
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 200, 0))
    }

    @Test
    fun `une mise a l'echelle affine agrandit la carte`() {
        val (bus, ppu) = newPpu()
        setupAffineBg(bus, wrap = false)
        // PA = 0.5 : la texture est étirée deux fois horizontalement, donc les
        // 128 px de carte couvrent désormais les 240 px de l'écran.
        reg(bus, 0x20, 0x0080)
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 200, 0))
    }

    @Test
    fun `la fenetre 0 limite l'arriere-plan a son rectangle`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x2100)   // mode 0 + BG0 + fenêtre 0
        reg(bus, 0x08, 0x0004)   // BG0CNT : charBase 1
        bus.vram[0x0000] = 0x01  // carte : tuile (0,0) = tuile 1
        for (i in 0 until 32) bus.vram[0x4020 + i] = 0x11
        palette(bus, 0, blue)
        palette(bus, 1, green)
        reg(bus, 0x40, 0x0008)   // WIN0H : gauche 0, droite 8 (exclue)
        reg(bus, 0x44, 0x0008)   // WIN0V : haut 0, bas 8 (exclu)
        reg(bus, 0x48, 0x003F)   // dans la fenêtre : tout autorisé
        reg(bus, 0x4A, 0x0000)   // dehors : rien
        renderFrame(ppu)

        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))  // dans la fenêtre
        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 4, 9))   // sous la fenêtre
    }

    /** Prépare BG0 (rouge, priorité 0) devant BG1 (bleu, priorité 1). */
    private fun setupTwoBackgrounds(bus: GbaBus) {
        reg(bus, 0x00, 0x0300)   // mode 0 + BG0 + BG1
        reg(bus, 0x08, 0x0004)   // BG0CNT : charBase 1, priorité 0
        reg(bus, 0x0A, 0x0105)   // BG1CNT : charBase 1, screenBase 1, priorité 1
        bus.vram[0x0000] = 0x01  // BG0 : tuile 1
        bus.vram[0x0800] = 0x02  // BG1 : tuile 2
        for (i in 0 until 32) bus.vram[0x4020 + i] = 0x11 // tuile 1 → index 1
        for (i in 0 until 32) bus.vram[0x4040 + i] = 0x22 // tuile 2 → index 2
        palette(bus, 1, red)
        palette(bus, 2, blue)
    }

    @Test
    fun `le melange alpha combine les deux couches`() {
        val (bus, ppu) = newPpu()
        setupTwoBackgrounds(bus)
        // BLDCNT : cible 1 = BG0, cible 2 = BG1, mode alpha.
        reg(bus, 0x50, 0x0001 or 0x0040 or 0x0200)
        reg(bus, 0x52, 0x0808)   // EVA = EVB = 8/16
        renderFrame(ppu)

        val result = pixel(ppu, 0, 0)
        val r = (result ushr 16) and 0xFF
        val b = result and 0xFF
        // Moitié rouge, moitié bleu : les deux canaux sont à mi-intensité.
        assertTrue(r in 100..140, "rouge attendu à mi-intensité, obtenu $r")
        assertTrue(b in 100..140, "bleu attendu à mi-intensité, obtenu $b")
    }

    @Test
    fun `l'eclaircissement maximal amene la couche au blanc`() {
        val (bus, ppu) = newPpu()
        setupTwoBackgrounds(bus)
        reg(bus, 0x50, 0x0001 or 0x0080) // cible 1 = BG0, mode éclaircissement
        reg(bus, 0x54, 0x0010)           // EVY = 16/16
        renderFrame(ppu)
        assertEquals(0xFFFFFFFF.toInt(), pixel(ppu, 0, 0))
    }

    @Test
    fun `l'assombrissement maximal amene la couche au noir`() {
        val (bus, ppu) = newPpu()
        setupTwoBackgrounds(bus)
        reg(bus, 0x50, 0x0001 or 0x00C0) // cible 1 = BG0, mode assombrissement
        reg(bus, 0x54, 0x0010)
        renderFrame(ppu)
        assertEquals(0xFF000000.toInt(), pixel(ppu, 0, 0))
    }

    @Test
    fun `sans cible declaree aucun effet n'est applique`() {
        val (bus, ppu) = newPpu()
        setupTwoBackgrounds(bus)
        reg(bus, 0x50, 0x0080) // mode éclaircissement mais BG0 n'est pas cible
        reg(bus, 0x54, 0x0010)
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(red), pixel(ppu, 0, 0))
    }

    @Test
    fun `un sprite affine identite s'affiche comme un sprite normal`() {
        val (bus, ppu) = newPpu()
        reg(bus, 0x00, 0x1000)  // OBJ activés, mappage 2D
        palette(bus, 0, blue)
        palette(bus, 256 + 1, green)
        for (i in 0 until 32) bus.vram[0x1_0000 + i] = 0x11 // tuile OBJ 0 → index 1
        // Sprite 0 : affine (attr0 bit 8), groupe de paramètres 0.
        bus.oam[0] = 0x00           // Y = 0
        bus.oam[1] = 0x01           // bit 8 : rotation/mise à l'échelle
        bus.oam[2] = 0x00           // X = 0
        bus.oam[3] = 0x00
        bus.oam[4] = 0x00           // tuile 0
        bus.oam[5] = 0x00
        // Matrice identité dans le champ attr3 du groupe 0.
        bus.oam[0x06] = 0x00; bus.oam[0x07] = 0x01  // PA = 1.0
        bus.oam[0x0E] = 0x00; bus.oam[0x0F] = 0x00  // PB = 0
        bus.oam[0x16] = 0x00; bus.oam[0x17] = 0x00  // PC = 0
        bus.oam[0x1E] = 0x00; bus.oam[0x1F] = 0x01  // PD = 1.0
        renderFrame(ppu)

        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 7, 7))
    }

    @Test
    fun `un sprite de fenetre objet ne s'affiche pas mais ouvre la fenetre`() {
        val (bus, ppu) = newPpu()
        // BG0 visible seulement dans la fenêtre objet.
        reg(bus, 0x00, 0x9100)   // mode 0 + BG0 + OBJ + fenêtre objet
        reg(bus, 0x08, 0x0004)
        bus.vram[0x0000] = 0x01
        for (i in 0 until 32) bus.vram[0x4020 + i] = 0x11
        palette(bus, 0, blue)
        palette(bus, 1, green)
        palette(bus, 256 + 1, red)
        for (i in 0 until 32) bus.vram[0x1_0000 + i] = 0x11 // tuile OBJ 0
        reg(bus, 0x4A, 0x3F00)   // WINOUT : dehors rien, fenêtre objet tout
        // Sprite 0 en mode « fenêtre objet » (attr0 bits 10-11 = 10).
        bus.oam[0] = 0x00
        bus.oam[1] = 0x08        // bits 10-11 = 2 → fenêtre objet
        bus.oam[2] = 0x00
        bus.oam[3] = 0x00
        bus.oam[4] = 0x00
        bus.oam[5] = 0x00
        renderFrame(ppu)

        // Sous le sprite : BG0 visible (vert), et le sprite lui-même invisible.
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0))
        // Hors de la fenêtre objet : plus rien, donc le fond.
        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 100, 0))
    }

    // ---- Verrouillage des points de référence affines ----

    /** Cycles d'une ligne complète, intervalle horizontal compris. */
    private val lineCycles = GbaCore.CYCLES_PER_FRAME / 228

    private fun advanceLines(ppu: GbaPpu, count: Int) = ppu.tick(lineCycles * count)

    /**
     * Décale la carte de 100 pixels vers la droite : sans ce décalage, la colonne
     * 200 tombe hors d'une carte de 128 pixels et reste au fond.
     */
    private fun writeBg2X(bus: GbaBus, pixels: Int) =
        bus.write32(0x0400_0028, pixels shl 8)

    /**
     * Un jeu écrit ses registres d'affichage **pendant le VBlank**, pour l'image
     * qui suit. Verrouiller les points de référence à l'entrée du VBlank, donc
     * avant son gestionnaire d'interruption, revenait à afficher indéfiniment le
     * cadrage de l'image précédente.
     */
    @Test
    fun `un point de reference ecrit pendant le VBlank sert des l'image suivante`() {
        val (bus, ppu) = newPpu()
        setupAffineBg(bus, wrap = false)
        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 200, 0), "cadrage de départ")

        // Entrée dans le VBlank, puis écriture comme le ferait le jeu.
        advanceLines(ppu, 161)
        writeBg2X(bus, -100)
        advanceLines(ppu, 228 - 161)

        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 200, 0))
    }

    /**
     * Le matériel recopie aussitôt `BGxX`/`BGxY` dans le registre interne que
     * suit le rendu : une couche affine peut donc être déplacée en cours
     * d'image, ce dont se servent les effets ligne par ligne.
     */
    @Test
    fun `ecrire un point de reference agit des la ligne suivante`() {
        val (bus, ppu) = newPpu()
        setupAffineBg(bus, wrap = false)

        advanceLines(ppu, 80)
        writeBg2X(bus, -100)
        advanceLines(ppu, 80)

        assertEquals(GbaPpu.bgr555ToArgb(blue), pixel(ppu, 200, 10), "haut de l'image")
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 200, 120), "bas de l'image")
    }

    // ---- Comptage des pixels par couche ----

    /**
     * Le comptage ne sert à rien s'il ne reflète pas le rendu. Ici la carte
     * affine couvre 128 × 128 pixels de l'écran : le compte doit valoir
     * exactement cela, ni la surface de l'écran ni zéro.
     */
    @Test
    fun `le comptage par couche suit les pixels reellement dessines`() {
        val (bus, ppu) = newPpu()
        ppu.collectLayerStats = true
        setupAffineBg(bus, wrap = false)

        renderFrame(ppu) // trame comptée
        renderFrame(ppu) // publication du comptage précédent

        assertEquals(128 * 128, ppu.layerPixels[2], "BG2 affine")
        assertEquals(0, ppu.layerPixels[0], "BG0 éteint")
        assertEquals(0, ppu.layerPixels[4], "aucun sprite visible")
    }

    /**
     * Le décompte des écritures vers la matrice affine doit suivre toutes les
     * largeurs d'accès : un jeu y écrit indifféremment par mot, demi-mot ou
     * copie DMA, et un décompte aveugle à l'une d'elles mènerait droit à la
     * mauvaise conclusion.
     */
    @Test
    fun `les ecritures vers la matrice affine sont comptees quelle qu'en soit la largeur`() {
        val (bus, _) = newPpu()
        val diagnostics = bus.diagnostics
        assertEquals(0, diagnostics.bg2MatrixWrites, "aucune écriture au départ")

        bus.write16(0x0400_0020, 0x0100)          // BG2PA seul
        assertEquals(1, diagnostics.bg2MatrixWrites)

        bus.write32(0x0400_0024, 0x0100_0000)     // BG2PC et BG2PD d'un coup
        assertEquals(3, diagnostics.bg2MatrixWrites)

        // Le point de référence a son propre décompte, distinct de la matrice.
        assertEquals(0, diagnostics.bg2ReferenceWrites)
        bus.write32(0x0400_0028, 0)               // BG2X
        assertEquals(2, diagnostics.bg2ReferenceWrites)
        assertEquals(3, diagnostics.bg2MatrixWrites, "la matrice n'a pas bougé")
    }

    /** Sans activation, le comptage reste à zéro : on ne paie pas ce qu'on ne mesure pas. */
    @Test
    fun `sans activation aucun pixel n'est compte`() {
        val (bus, ppu) = newPpu()
        setupAffineBg(bus, wrap = false)

        renderFrame(ppu)
        renderFrame(ppu)

        assertTrue(ppu.layerPixels.all { it == 0 }, ppu.layerPixels.toList().toString())
    }

    /**
     * Un plan affine dont le jeu n'écrit **jamais** la matrice doit s'afficher à
     * l'échelle 1. Pokémon Rouge Feu en dépend : il active un plan affine pour
     * l'image du professeur, lui fixe son point de référence, et ne touche jamais
     * `BG2PA`–`BG2PD`. Avec une matrice partie de zéro, la couche se réduit à un
     * point et le professeur n'apparaît pas.
     */
    @Test
    fun `un plan affine s'affiche sans que le jeu ecrive sa matrice`() {
        val (bus, ppu) = newPpu()
        // La carte doit être **non uniforme**, sinon une matrice nulle — qui
        // ramène tout l'écran sur la tuile (0,0) — donnerait la même couleur
        // partout et le test ne prouverait rien.
        reg(bus, 0x00, 0x0402)   // mode 2 + BG2
        reg(bus, 0x0C, 0x0004)   // charBase 1, taille 128 px
        for (i in 0 until 256) bus.vram[i] = 2   // partout la tuile 2
        bus.vram[0] = 1                          // sauf le coin, tuile 1
        for (i in 0 until 64) bus.vram[0x4040 + i] = 1
        for (i in 0 until 64) bus.vram[0x4080 + i] = 2
        palette(bus, 0, blue)
        palette(bus, 1, green)
        palette(bus, 2, red)

        renderFrame(ppu)
        assertEquals(GbaPpu.bgr555ToArgb(green), pixel(ppu, 0, 0), "coin haut-gauche")
        // Sans transformation, ce point resterait sur la tuile du coin.
        assertEquals(GbaPpu.bgr555ToArgb(red), pixel(ppu, 100, 100), "ailleurs sur la carte")
    }
}
