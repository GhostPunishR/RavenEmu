package com.ravenemu.core.gba.ppu

import com.ravenemu.core.gba.dma.DmaController
import com.ravenemu.core.gba.interrupt.GbaInterruptController
import com.ravenemu.core.gba.interrupt.Interrupt
import com.ravenemu.core.gba.memory.GbaBus

/**
 * Unité graphique de la Game Boy Advance, rendue **ligne par ligne** au fil des
 * cycles.
 *
 * Périmètre de ce lot vidéo :
 * - registres `DISPCNT`, `DISPSTAT` (drapeaux VBlank/HBlank/coïncidence VCount)
 *   et `VCOUNT` (le bus expose ces états en lecture) ;
 * - modes **bitmap** 3 (16 bpp), 4 (8 bpp paletté, double page) et 5
 *   (16 bpp, 160×128, double page) ;
 * - modes **texte** 0 et 1 : arrière-plans BG0–BG3, tuiles 4/8 bpp,
 *   défilement, retournements horizontal/vertical, priorités.
 *
 * Différé (limites documentées) : arrière-plans **affines** (modes 1/2),
 * **sprites**, fenêtres, mosaïque, alpha blending, luminosité, et les
 * **interruptions** VBlank/HBlank/VCount (le contrôleur d'interruptions GBA
 * n'est pas encore implémenté ; seuls les drapeaux d'état sont fournis).
 *
 * Le renderer Android reçoit un framebuffer ARGB 8888 sans rien connaître de
 * ces détails.
 */
class GbaPpu(private val bus: GbaBus) {

    /** Dernière trame produite, en ARGB 8888. */
    val frame = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)

    // Tampons de composition d'une ligne (réutilisés, sans allocation par ligne).
    private val lineColor = IntArray(SCREEN_WIDTH)
    private val linePriority = IntArray(SCREEN_WIDTH)
    private val objColor = IntArray(SCREEN_WIDTH)
    private val objPriority = IntArray(SCREEN_WIDTH)
    private val objOpaque = BooleanArray(SCREEN_WIDTH)

    /** Ligne courante (`VCOUNT`), 0..227. */
    var vcount = 0
        private set

    /** Drapeau VBlank (lignes 160..227). */
    var inVBlank = false
        private set

    /** Drapeau HBlank de la ligne courante. */
    var inHBlank = false
        private set

    /** Coïncidence VCount == valeur programmée dans DISPSTAT. */
    var vcountMatch = false
        private set

    private var lineCycles = 0

    /** Contrôleur d'interruptions et DMA, rattachés après construction. */
    var interrupts: GbaInterruptController? = null
    var dma: DmaController? = null

    /**
     * Avance l'horloge d'affichage de [cpuCycles] cycles CPU (4 cycles = 1 point).
     * Chaque ligne visible est rendue au début de son HBlank ; les interruptions
     * VBlank/HBlank/VCount (si activées dans DISPSTAT) et les DMA temporisés sur
     * ces événements sont déclenchés au passage.
     */
    fun tick(cpuCycles: Int) {
        var remaining = cpuCycles
        while (remaining > 0) {
            val step = minOf(remaining, LINE_CYCLES - lineCycles)
            lineCycles += step
            remaining -= step
            if (!inHBlank && lineCycles >= HDRAW_CYCLES) {
                inHBlank = true
                if (vcount < SCREEN_HEIGHT) {
                    renderScanline(vcount)
                    if (dispStatIrqEnabled(HBLANK_IRQ)) interrupts?.request(Interrupt.HBLANK)
                    dma?.triggerHBlank()
                }
            }
            if (lineCycles >= LINE_CYCLES) {
                lineCycles = 0
                inHBlank = false
                vcount = (vcount + 1) % TOTAL_LINES
                inVBlank = vcount >= SCREEN_HEIGHT
                if (vcount == SCREEN_HEIGHT) {
                    if (dispStatIrqEnabled(VBLANK_IRQ)) interrupts?.request(Interrupt.VBLANK)
                    dma?.triggerVBlank()
                }
                vcountMatch = vcount == (bus.io[0x05].toInt() and 0xFF)
                if (vcountMatch && dispStatIrqEnabled(VCOUNT_IRQ)) {
                    interrupts?.request(Interrupt.VCOUNT)
                }
            }
        }
    }

    private fun dispStatIrqEnabled(mask: Int): Boolean = bus.io[0x04].toInt() and mask != 0

    /** Octet de poids faible de DISPSTAT : bits d'état (RO) fusionnés aux bits RW. */
    fun dispStatLowByte(): Int {
        var status = 0
        if (inVBlank) status = status or 0x01
        if (inHBlank) status = status or 0x02
        if (vcountMatch) status = status or 0x04
        return (bus.io[0x04].toInt() and 0xF8) or status
    }

    fun reset() {
        frame.fill(0)
        vcount = 0
        lineCycles = 0
        inVBlank = false
        inHBlank = false
        vcountMatch = false
    }

    // ---- Rendu ----

    private fun renderScanline(y: Int) {
        val dispcnt = reg16(0x00)
        val rowBase = y * SCREEN_WIDTH
        // Écran blanc forcé (DISPCNT bit 7).
        if (dispcnt and 0x0080 != 0) {
            for (x in 0 until SCREEN_WIDTH) frame[rowBase + x] = WHITE
            return
        }
        val backdrop = paletteColor(0)
        for (x in 0 until SCREEN_WIDTH) {
            lineColor[x] = backdrop
            linePriority[x] = LAYER_BACKDROP
            objOpaque[x] = false
        }

        when (dispcnt and 0x7) {
            0 -> renderTextBackgrounds(y, dispcnt, 0..3)
            1 -> renderTextBackgrounds(y, dispcnt, 0..1) // BG2 affine différé
            2 -> Unit // BG2/BG3 affines différés : fond seul
            3 -> renderBitmapMode3(y, dispcnt)
            4 -> renderBitmapMode4(y, dispcnt)
            5 -> renderBitmapMode5(y, dispcnt)
        }
        renderSprites(y, dispcnt)

        // Composition : un sprite passe devant un arrière-plan si sa priorité
        // est inférieure ou égale à celle du pixel de fond (les sprites gagnent
        // les égalités).
        for (x in 0 until SCREEN_WIDTH) {
            frame[rowBase + x] =
                if (objOpaque[x] && objPriority[x] <= linePriority[x]) objColor[x] else lineColor[x]
        }
    }

    private fun renderTextBackgrounds(y: Int, dispcnt: Int, range: IntRange) {
        // Ordre de dessin arrière → avant : clé = priorité*4 + index (petit = devant).
        val enabled = range.filter { dispcnt and (1 shl (8 + it)) != 0 }
            .sortedByDescending { (reg16(0x08 + it * 2) and 0x3) * 4 + it }
        for (bg in enabled) drawTextBackgroundLine(bg, y)
    }

    private fun drawTextBackgroundLine(bg: Int, y: Int) {
        val control = reg16(0x08 + bg * 2)
        val priority = control and 0x3
        val charBase = ((control ushr 2) and 0x3) * 0x4000
        val screenBase = ((control ushr 8) and 0x1F) * 0x800
        val is8bpp = control and 0x0080 != 0
        val size = (control ushr 14) and 0x3
        val widthPixels = if (size == 1 || size == 3) 512 else 256
        val heightPixels = if (size == 2 || size == 3) 512 else 256

        val hofs = reg16(0x10 + bg * 4) and 0x1FF
        val vofs = reg16(0x12 + bg * 4) and 0x1FF

        val effY = (y + vofs) and (heightPixels - 1)
        val tileY = effY / 8
        val py0 = effY and 7

        for (x in 0 until SCREEN_WIDTH) {
            val effX = (x + hofs) and (widthPixels - 1)
            val tileX = effX / 8

            val mapAddr = screenBase + screenBlockOffset(tileX, tileY, size) +
                ((tileY and 31) * 32 + (tileX and 31)) * 2
            val entry = vram16(mapAddr)
            val tileNum = entry and 0x3FF
            val hflip = entry and 0x400 != 0
            val vflip = entry and 0x800 != 0
            val palBank = (entry ushr 12) and 0xF

            val px = if (hflip) 7 - (effX and 7) else (effX and 7)
            val py = if (vflip) 7 - py0 else py0

            val colorIndex = if (is8bpp) {
                val addr = charBase + tileNum * 64 + py * 8 + px
                vramByte(addr)
            } else {
                val addr = charBase + tileNum * 32 + py * 4 + px / 2
                val byte = vramByte(addr)
                val nibble = if (px and 1 == 0) byte and 0xF else (byte ushr 4) and 0xF
                if (nibble == 0) 0 else palBank * 16 + nibble
            }
            if (colorIndex != 0) {
                lineColor[x] = paletteColor(colorIndex)
                linePriority[x] = priority
            }
        }
    }

    /**
     * Dessine les sprites de la ligne [y] dans les tampons objet. Périmètre :
     * sprites **normaux** (tailles carrées/rectangulaires, 4/8 bpp, mappage
     * 1D/2D, retournements, priorité). Les sprites **affines** (rotation/mise à
     * l'échelle) et les fenêtres objet sont différés.
     */
    private fun renderSprites(y: Int, dispcnt: Int) {
        if (dispcnt and 0x1000 == 0) return // OBJ désactivés
        val oneDimensional = dispcnt and 0x0040 != 0
        for (i in 0 until 128) {
            val base = i * 8
            val attr0 = oam16(base)
            val objMode = (attr0 ushr 8) and 0x3
            if (objMode == 2) continue          // sprite caché
            if (objMode == 1 || objMode == 3) continue // affine : différé
            val shape = (attr0 ushr 14) and 0x3
            if (shape == 3) continue            // forme interdite

            val attr1 = oam16(base + 2)
            val attr2 = oam16(base + 4)
            val sizeIdx = (attr1 ushr 14) and 0x3
            val w = OBJ_WIDTH[shape][sizeIdx]
            val h = OBJ_HEIGHT[shape][sizeIdx]

            val yPos = attr0 and 0xFF
            val sy = (y - yPos) and 0xFF
            if (sy >= h) continue

            var xPos = attr1 and 0x1FF
            if (xPos >= 0x100) xPos -= 0x200    // X signé 9 bits

            val is8bpp = attr0 and 0x2000 != 0
            val palBank = (attr2 ushr 12) and 0xF
            val priority = (attr2 ushr 10) and 0x3
            val hflip = attr1 and 0x1000 != 0
            val vflip = attr1 and 0x2000 != 0
            val tileBase = attr2 and 0x3FF
            val slots = if (is8bpp) 2 else 1
            val rowStride = if (oneDimensional) (w / 8) * slots else 32

            val row = if (vflip) h - 1 - sy else sy
            val tileRow = row / 8
            val inY = row and 7

            for (col in 0 until w) {
                val screenX = xPos + col
                if (screenX < 0 || screenX >= SCREEN_WIDTH) continue
                if (objOpaque[screenX]) continue // un sprite d'index inférieur a priorité
                val sc = if (hflip) w - 1 - col else col
                val tileIndex = tileBase + tileRow * rowStride + (sc / 8) * slots
                val inX = sc and 7
                val colorIndex = if (is8bpp) {
                    vramByte(OBJ_TILE_BASE + tileIndex * 32 + inY * 8 + inX)
                } else {
                    val byte = vramByte(OBJ_TILE_BASE + tileIndex * 32 + inY * 4 + inX / 2)
                    val nibble = if (inX and 1 == 0) byte and 0xF else (byte ushr 4) and 0xF
                    if (nibble == 0) 0 else palBank * 16 + nibble
                }
                if (colorIndex != 0) {
                    objColor[screenX] = paletteColor(OBJ_PALETTE_BASE + colorIndex)
                    objPriority[screenX] = priority
                    objOpaque[screenX] = true
                }
            }
        }
    }

    /** Décalage du bloc d'écran (32×32 tuiles) selon la taille du BG. */
    private fun screenBlockOffset(tileX: Int, tileY: Int, size: Int): Int {
        val sbX = if (tileX >= 32) 1 else 0
        val sbY = if (tileY >= 32) 1 else 0
        val index = when (size) {
            1 -> sbX
            2 -> sbY
            3 -> sbY * 2 + sbX
            else -> 0
        }
        return index * 0x800
    }

    /** Priorité de BG2 (les modes bitmap dessinent sur BG2). */
    private fun bg2Priority(): Int = reg16(0x0C) and 0x3

    private fun renderBitmapMode3(y: Int, dispcnt: Int) {
        if (dispcnt and 0x0400 == 0) return // BG2 désactivé
        val priority = bg2Priority()
        for (x in 0 until SCREEN_WIDTH) {
            lineColor[x] = bgr555ToArgb(vram16((y * SCREEN_WIDTH + x) * 2))
            linePriority[x] = priority
        }
    }

    private fun renderBitmapMode4(y: Int, dispcnt: Int) {
        if (dispcnt and 0x0400 == 0) return
        val page = if (dispcnt and 0x0010 != 0) 0xA000 else 0
        val priority = bg2Priority()
        for (x in 0 until SCREEN_WIDTH) {
            lineColor[x] = paletteColor(vramByte(page + y * SCREEN_WIDTH + x))
            linePriority[x] = priority
        }
    }

    private fun renderBitmapMode5(y: Int, dispcnt: Int) {
        if (dispcnt and 0x0400 == 0 || y >= MODE5_HEIGHT) return
        val page = if (dispcnt and 0x0010 != 0) 0xA000 else 0
        val priority = bg2Priority()
        for (x in 0 until MODE5_WIDTH) {
            lineColor[x] = bgr555ToArgb(vram16(page + (y * MODE5_WIDTH + x) * 2))
            linePriority[x] = priority
        }
    }

    // ---- Accès mémoire ----

    private fun reg16(offset: Int): Int =
        (bus.io[offset].toInt() and 0xFF) or ((bus.io[offset + 1].toInt() and 0xFF) shl 8)

    private fun vramByte(offset: Int): Int =
        if (offset in bus.vram.indices) bus.vram[offset].toInt() and 0xFF else 0

    private fun vram16(offset: Int): Int = vramByte(offset) or (vramByte(offset + 1) shl 8)

    private fun oam16(offset: Int): Int =
        (bus.oam[offset].toInt() and 0xFF) or ((bus.oam[offset + 1].toInt() and 0xFF) shl 8)

    /** Couleur de la palette BG à l'index [index] (0..255), en ARGB. */
    private fun paletteColor(index: Int): Int {
        val a = index * 2
        val lo = bus.paletteRam[a].toInt() and 0xFF
        val hi = bus.paletteRam[a + 1].toInt() and 0xFF
        return bgr555ToArgb(lo or (hi shl 8))
    }

    companion object {
        const val SCREEN_WIDTH = 240
        const val SCREEN_HEIGHT = 160

        private const val MODE5_WIDTH = 160
        private const val MODE5_HEIGHT = 128

        /** Fond : priorité la plus basse (4 = derrière tout arrière-plan 0..3). */
        private const val LAYER_BACKDROP = 4

        /** Base des tuiles de sprites en VRAM (0x0601_0000). */
        private const val OBJ_TILE_BASE = 0x1_0000

        /** Décalage d'index de la palette OBJ (256 couleurs après la palette BG). */
        private const val OBJ_PALETTE_BASE = 256

        // Largeur/hauteur des sprites selon (forme, taille).
        private val OBJ_WIDTH = arrayOf(
            intArrayOf(8, 16, 32, 64),  // carré
            intArrayOf(16, 32, 32, 64), // horizontal
            intArrayOf(8, 8, 16, 32),   // vertical
        )
        private val OBJ_HEIGHT = arrayOf(
            intArrayOf(8, 16, 32, 64),  // carré
            intArrayOf(8, 8, 16, 32),   // horizontal
            intArrayOf(16, 32, 32, 64), // vertical
        )

        // Bits d'activation d'IRQ dans l'octet bas de DISPSTAT.
        private const val VBLANK_IRQ = 0x08
        private const val HBLANK_IRQ = 0x10
        private const val VCOUNT_IRQ = 0x20

        private const val HDRAW_CYCLES = 240 * 4   // 960 : début du HBlank
        private const val LINE_CYCLES = 308 * 4    // 1232 cycles par ligne
        private const val TOTAL_LINES = 228        // 160 visibles + 68 VBlank

        private const val WHITE = 0xFFFFFFFF.toInt()

        /** Convertit une couleur BGR555 (15 bits) en ARGB 8888 opaque. */
        fun bgr555ToArgb(color: Int): Int {
            val r5 = color and 0x1F
            val g5 = (color ushr 5) and 0x1F
            val b5 = (color ushr 10) and 0x1F
            val r8 = (r5 shl 3) or (r5 ushr 2)
            val g8 = (g5 shl 3) or (g5 ushr 2)
            val b8 = (b5 shl 3) or (b5 ushr 2)
            return (0xFF shl 24) or (r8 shl 16) or (g8 shl 8) or b8
        }
    }
}
