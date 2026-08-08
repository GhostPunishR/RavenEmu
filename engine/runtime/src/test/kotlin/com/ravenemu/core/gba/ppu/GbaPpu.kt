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
 *   défilement, retournements horizontal/vertical, priorités ;
 * - modes **affines** 1 et 2 : rotation et mise à l'échelle avec points de
 *   référence internes ;
 * - **sprites** (dont affines et à surface doublée), fenêtres 0/1 et fenêtre
 *   objet, mélange alpha, éclaircissement et assombrissement.
 *
 * Les événements VBlank/HBlank/VCount alimentent le contrôleur d'interruptions
 * et le DMA. Limite documentée : la mosaïque n'est pas émulée.
 *
 * Le rendu d'une ligne n'alloue rien : tampons de composition et ordre de tracé
 * des plans sont des tableaux réutilisés.
 *
 * Le renderer Android reçoit un framebuffer ARGB 8888 sans rien connaître de
 * ces détails.
 */
class GbaPpu(private val bus: GbaBus) {

    /** Dernière trame produite, en ARGB 8888. */
    val frame = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)

    /**
     * Désactive uniquement la composition des pixels de la trame courante.
     * Les compteurs, références affines, interruptions et DMA continuent.
     */
    internal var renderEnabled = true

    /**
     * Compte les pixels que chaque couche produit. Réservé au diagnostic : c'est
     * un incrément par pixel dessiné, qu'on ne paie pas quand on ne mesure pas.
     */
    var collectLayerStats = false

    /**
     * Pixels produits par chaque couche à la trame précédente, indexés `BG0`…
     * `BG3` puis `OBJ`.
     *
     * Un zéro sur une couche pourtant activée est sans appel : elle n'a rien
     * dessiné du tout, et l'élément qu'on y cherche est ailleurs — ou son décor
     * n'est jamais arrivé en mémoire vidéo. Un nombre élevé sur une couche dont
     * on ne voit rien dit l'inverse : elle dessine, mais quelque chose la
     * recouvre.
     */
    val layerPixels = IntArray(5)

    /** Comptage de la trame en cours, publié dans [layerPixels] à sa fin. */
    private val layerPixelsPending = IntArray(5)

    /**
     * Mesure la dynamique de la trame produite. Réservé au diagnostic : c'est un
     * balayage complet de l'image une fois par trame, qu'on ne paie pas quand on
     * ne mesure pas.
     */
    var collectFrameStats = false

    /**
     * Luminance la plus sombre, la plus claire et moyenne de la trame précédente,
     * sur `0..255`. Zéro tant que [collectFrameStats] est faux.
     *
     * C'est la mesure qui tranche un voile signalé à l'écran. Un voile est, par
     * définition, une dynamique comprimée : le noir n'est plus noir. Si
     * [frameLumaMin] reste élevé alors que l'image devrait comporter du noir,
     * c'est le moteur qui produit déjà l'image délavée. S'il descend à zéro, le
     * moteur est hors de cause et le voile vient de ce qui suit — post-traitement
     * d'affichage, profil d'écran, ou composition de la surface Android.
     *
     * La luminance suit la pondération Rec. 709, la même que le post-traitement
     * d'affichage : les deux étages parlent ainsi du même gris.
     */
    var frameLumaMin = 0
        private set

    var frameLumaMax = 0
        private set

    var frameLumaMean = 0
        private set

    // Tampons de composition d'une ligne (réutilisés, sans allocation par ligne).
    // Couche la plus proche de l'observateur, et celle juste derrière : le
    // mélange alpha combine ces deux niveaux.
    private val lineColor = IntArray(SCREEN_WIDTH)
    private val linePriority = IntArray(SCREEN_WIDTH)
    private val lineLayer = IntArray(SCREEN_WIDTH)
    private val lineColor2 = IntArray(SCREEN_WIDTH)
    private val linePriority2 = IntArray(SCREEN_WIDTH)
    private val lineLayer2 = IntArray(SCREEN_WIDTH)

    // Cache paresseux des 512 couleurs. Une couleur est lue au plus une fois
    // par ligne et n'est reconvertie que si sa valeur BGR555 a changé.
    private val paletteCache = IntArray(PALETTE_COLORS)
    private val paletteRaw = IntArray(PALETTE_COLORS) { -1 }
    private val paletteStamp = IntArray(PALETTE_COLORS)
    private var paletteEpoch = 0

    // Vrai lorsque BLDCNT ne peut demander aucun effet. Le second plan de
    // composition est alors inutile.
    private var simpleComposition = false

    private val objColor = IntArray(SCREEN_WIDTH)
    private val objPriority = IntArray(SCREEN_WIDTH)
    private val objOpaque = BooleanArray(SCREEN_WIDTH)

    /** Sprite en mode semi-transparent : force le mélange alpha. */
    private val objSemiTransparent = BooleanArray(SCREEN_WIDTH)

    /** Pixel couvert par un sprite de type « fenêtre objet ». */
    private val objWindow = BooleanArray(SCREEN_WIDTH)

    /** Masque de fenêtre par pixel : bits 0–3 = BG0–3, bit 4 = OBJ, bit 5 = effets. */
    private val windowMask = IntArray(SCREEN_WIDTH)

    // Ordre de tracé des arrière-plans de la ligne courante et clés de tri
    // associées, triés sur place à chaque ligne (voir renderBackgrounds).
    private val bgOrder = IntArray(4)
    private val bgOrderKeys = IntArray(4)

    // Points de référence internes des arrière-plans affines (16.8 signé).
    // Rechargés depuis BGxX/BGxY au VBlank, incrémentés à chaque ligne.
    private var bg2RefX = 0
    private var bg2RefY = 0
    private var bg3RefX = 0
    private var bg3RefY = 0

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
     * Chaque ligne visible est rendue au début de son HBlank.
     */
    fun tick(cpuCycles: Int) {
        // Chemin rapide, exact : si l'incrément n'atteint ni le début du HBlank
        // ni la fin de la ligne, il n'y a rien d'autre à faire qu'avancer
        // l'horloge. C'est le cas de la quasi-totalité des appels, le processeur
        // rendant la main après chaque instruction.
        val nextEvent = if (inHBlank) LINE_CYCLES else HDRAW_CYCLES
        if (lineCycles + cpuCycles < nextEvent) {
            lineCycles += cpuCycles
            return
        }
        var remaining = cpuCycles
        while (remaining > 0) {
            val step = minOf(remaining, LINE_CYCLES - lineCycles)
            lineCycles += step
            remaining -= step
            if (!inHBlank && lineCycles >= HDRAW_CYCLES) {
                inHBlank = true
                if (vcount < SCREEN_HEIGHT) {
                    if (renderEnabled) {
                        val diagnostics = bus.diagnostics
                        if (diagnostics.measuringTime) {
                            val start = System.nanoTime()
                            renderScanline(vcount)
                            diagnostics.addPpuNanos(System.nanoTime() - start)
                        } else {
                            renderScanline(vcount)
                        }
                    }
                    // Les points de référence affines avancent même lorsque la
                    // composition est sautée : leur état appartient au matériel.
                    bg2RefX += signed16(reg16(0x22)) // BG2PB
                    bg2RefY += signed16(reg16(0x26)) // BG2PD
                    bg3RefX += signed16(reg16(0x32)) // BG3PB
                    bg3RefY += signed16(reg16(0x36)) // BG3PD
                    if (dispStatIrqEnabled(HBLANK_IRQ)) interrupts?.request(Interrupt.HBLANK)
                    dma?.triggerHBlank()
                }
            }
            if (lineCycles >= LINE_CYCLES) {
                lineCycles = 0
                inHBlank = false
                vcount = (vcount + 1) % TOTAL_LINES
                inVBlank = vcount >= SCREEN_HEIGHT
                // Les points de référence affines sont verrouillés au début de
                // l'image, pas à l'entrée du VBlank : c'est pendant le VBlank que
                // le jeu écrit `BGxX`/`BGxY` pour l'image à venir, et les
                // verrouiller avant son gestionnaire reviendrait à toujours
                // afficher l'image précédente.
                if (vcount == 0) {
                    reloadAffineReferencePoints()
                    if (collectLayerStats) {
                        layerPixelsPending.copyInto(layerPixels)
                        layerPixelsPending.fill(0)
                    }
                    // La trame qui vient de s'achever est encore dans [frame] :
                    // c'est ici, et une seule fois, qu'on la mesure.
                    if (collectFrameStats) measureFrameLuma()
                }
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
        bg2RefX = 0
        bg2RefY = 0
        bg3RefX = 0
        bg3RefY = 0
        paletteStamp.fill(0)
        paletteEpoch = 0
        simpleComposition = false
    }

    /** État temporel minimal requis pour une reprise déterministe. */
    fun stateFields(): IntArray = intArrayOf(
        vcount,
        lineCycles,
        if (inVBlank) 1 else 0,
        if (inHBlank) 1 else 0,
        if (vcountMatch) 1 else 0,
        bg2RefX,
        bg2RefY,
        bg3RefX,
        bg3RefY,
    )

    fun restoreState(fields: IntArray) {
        require(fields.size == STATE_FIELD_COUNT) { "État PPU GBA invalide" }
        require(fields[0] in 0 until TOTAL_LINES) { "VCOUNT GBA invalide" }
        require(fields[1] in 0 until LINE_CYCLES) { "Position de ligne GBA invalide" }
        require(fields[2] in 0..1 && fields[3] in 0..1 && fields[4] in 0..1) {
            "Drapeaux PPU GBA invalides"
        }
        vcount = fields[0]
        lineCycles = fields[1]
        inVBlank = fields[2] != 0
        inHBlank = fields[3] != 0
        vcountMatch = fields[4] != 0
        bg2RefX = fields[5]
        bg2RefY = fields[6]
        bg3RefX = fields[7]
        bg3RefY = fields[8]
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
        beginPaletteLine()
        val bldcnt = reg16(0x50)
        simpleComposition = bldcnt and SIMPLE_COMPOSITION_MASK == 0
        val backdrop = paletteColor(0)
        for (x in 0 until SCREEN_WIDTH) {
            lineColor[x] = backdrop
            linePriority[x] = LAYER_BACKDROP
            lineLayer[x] = LAYER_ID_BACKDROP
            if (!simpleComposition) {
                lineColor2[x] = backdrop
                linePriority2[x] = LAYER_BACKDROP
                lineLayer2[x] = LAYER_ID_BACKDROP
            }
            objOpaque[x] = false
            objSemiTransparent[x] = false
            objWindow[x] = false
        }

        // Les sprites sont dessinés en premier car la fenêtre objet dépend
        // des pixels qu'ils couvrent. Leur composition vient ensuite.
        renderSprites(y, dispcnt)
        computeWindowMask(y, dispcnt)

        when (dispcnt and 0x7) {
            0, 1, 2 -> renderBackgrounds(y, dispcnt)
            3 -> renderBitmapMode3(y, dispcnt)
            4 -> renderBitmapMode4(y, dispcnt)
            5 -> renderBitmapMode5(y, dispcnt)
        }

        if (simpleComposition) composeSimpleLine(rowBase) else composeLine(rowBase, bldcnt)
    }

    /**
     * Dessine les arrière-plans actifs du mode courant, du plus lointain au plus
     * proche. En mode 1, `BG2` est affine ; en mode 2, `BG2` et `BG3` le sont.
     */
    private fun renderBackgrounds(y: Int, dispcnt: Int) {
        val mode = dispcnt and 0x7
        // Plans disponibles selon le mode : 0-3 en mode 0, 0-2 en mode 1, 2-3 en
        // mode 2 (les modes bitmap ne passent pas ici).
        val firstBg = if (mode == 2) 2 else 0
        val lastBg = if (mode == 1) 2 else 3

        // Tri par insertion décroissant, dans des tableaux réutilisés : cette
        // fonction est appelée 160 fois par trame et ne doit rien allouer.
        var count = 0
        for (bg in firstBg..lastBg) {
            if (dispcnt and (1 shl (8 + bg)) == 0) continue
            // Clé de tri : priorité puis index ; on dessine du fond vers l'avant.
            val key = (reg16(0x08 + bg * 2) and 0x3) * 4 + bg
            var i = count
            while (i > 0 && bgOrderKeys[i - 1] < key) {
                bgOrderKeys[i] = bgOrderKeys[i - 1]
                bgOrder[i] = bgOrder[i - 1]
                i--
            }
            bgOrderKeys[i] = key
            bgOrder[i] = bg
            count++
        }

        for (i in 0 until count) {
            val bg = bgOrder[i]
            val isAffine = (mode == 1 && bg == 2) || (mode == 2 && bg >= 2)
            if (isAffine) drawAffineBackgroundLine(bg) else drawTextBackgroundLine(bg, y)
        }
    }

    /**
     * Arrière-plan **affine** (rotation / mise à l'échelle) : la carte ne contient
     * qu'un index de tuile par octet et les tuiles sont toujours en 8 bpp. Les
     * coordonnées sont calculées en virgule fixe 20.8 à partir du point de
     * référence interne et des paramètres `PA`–`PD`.
     */
    private fun drawAffineBackgroundLine(bg: Int) {
        val control = reg16(0x08 + bg * 2)
        val priority = control and 0x3
        val charBase = ((control ushr 2) and 0x3) * 0x4000
        val screenBase = ((control ushr 8) and 0x1F) * 0x800
        val wraps = control and 0x2000 != 0
        // Taille : 128, 256, 512 ou 1024 pixels de côté.
        val sizeTiles = 16 shl ((control ushr 14) and 0x3)
        val sizePixels = sizeTiles * 8

        val paramBase = if (bg == 2) 0x20 else 0x30
        val pa = signed16(reg16(paramBase))
        val pc = signed16(reg16(paramBase + 4))
        var currentX = if (bg == 2) bg2RefX else bg3RefX
        var currentY = if (bg == 2) bg2RefY else bg3RefY

        val layerBit = 1 shl bg
        for (x in 0 until SCREEN_WIDTH) {
            // Partie entière des coordonnées texture (8 bits fractionnaires).
            var texX = currentX shr 8
            var texY = currentY shr 8
            currentX += pa
            currentY += pc

            if (windowMask[x] and layerBit == 0) continue
            if (wraps) {
                texX = texX mod sizePixels
                texY = texY mod sizePixels
            } else if (texX < 0 || texX >= sizePixels || texY < 0 || texY >= sizePixels) {
                continue // hors carte et sans répétition : transparent
            }

            val tileIndex = vramByte(screenBase + (texY / 8) * sizeTiles + (texX / 8))
            val colorIndex =
                vramByte(charBase + tileIndex * 64 + (texY and 7) * 8 + (texX and 7))
            if (colorIndex != 0) pushPixel(x, paletteColor(colorIndex), priority, bg)
        }
    }

    /** Reste positif : `mod` mathématique, contrairement à `%` sur les négatifs. */
    private infix fun Int.mod(modulus: Int): Int {
        val r = this % modulus
        return if (r < 0) r + modulus else r
    }

    /**
     * Dépose un pixel d'arrière-plan : il devient la couche visible et l'ancienne
     * passe au second rang (nécessaire au mélange alpha). Les arrière-plans étant
     * dessinés du fond vers l'avant, un nouveau pixel est toujours plus proche.
     */
    private fun pushPixel(x: Int, color: Int, priority: Int, layerId: Int) {
        if (collectLayerStats) layerPixelsPending[layerId]++
        if (!simpleComposition) {
            lineColor2[x] = lineColor[x]
            linePriority2[x] = linePriority[x]
            lineLayer2[x] = lineLayer[x]
        }
        lineColor[x] = color
        linePriority[x] = priority
        lineLayer[x] = layerId
    }

    /**
     * Balaie la trame achevée et publie sa dynamique de luminance.
     *
     * Un seul passage, sans allocation : trois multiplications et un décalage par
     * pixel. La somme tient largement dans un entier signé — 38 400 pixels d'une
     * luminance au plus égale à 255 plafonnent sous dix millions.
     */
    private fun measureFrameLuma() {
        var min = 255
        var max = 0
        var sum = 0
        for (pixel in frame) {
            val luma = (
                54 * ((pixel ushr 16) and 0xFF) +
                    183 * ((pixel ushr 8) and 0xFF) +
                    19 * (pixel and 0xFF)
                ) ushr 8
            if (luma < min) min = luma
            if (luma > max) max = luma
            sum += luma
        }
        frameLumaMin = min
        frameLumaMax = max
        frameLumaMean = sum / frame.size
    }

    /**
     * Calcule, pour chaque pixel de la ligne, quelles couches sont visibles et
     * si les effets de couleur s'y appliquent (registres `WIN0H`–`WINOUT`).
     * Sans aucune fenêtre active, tout est autorisé.
     */
    private fun computeWindowMask(y: Int, dispcnt: Int) {
        val win0On = dispcnt and 0x2000 != 0
        val win1On = dispcnt and 0x4000 != 0
        val objWinOn = dispcnt and 0x8000 != 0
        if (!win0On && !win1On && !objWinOn) {
            windowMask.fill(WINDOW_ALL)
            return
        }
        val winin = reg16(0x48)
        val winout = reg16(0x4A)
        val win0Mask = winin and 0x3F
        val win1Mask = (winin ushr 8) and 0x3F
        val outsideMask = winout and 0x3F
        val objWinMask = (winout ushr 8) and 0x3F
        for (x in 0 until SCREEN_WIDTH) {
            windowMask[x] = when {
                win0On && insideWindow(0, x, y) -> win0Mask
                win1On && insideWindow(1, x, y) -> win1Mask
                objWinOn && objWindow[x] -> objWinMask
                else -> outsideMask
            }
        }
    }

    /**
     * `true` si le point ([x], [y]) est dans la fenêtre [index]. Les bornes
     * droite et basse sont exclues ; lorsque la borne de fin précède celle de
     * début, la fenêtre s'enroule (comportement matériel).
     */
    private fun insideWindow(index: Int, x: Int, y: Int): Boolean {
        val horizontal = reg16(0x40 + index * 2)
        val vertical = reg16(0x44 + index * 2)
        val left = (horizontal ushr 8) and 0xFF
        val right = horizontal and 0xFF
        val top = (vertical ushr 8) and 0xFF
        val bottom = vertical and 0xFF
        val inX = if (left <= right) x >= left && x < right else x >= left || x < right
        val inY = if (top <= bottom) y >= top && y < bottom else y >= top || y < bottom
        return inX && inY
    }

    /**
     * Composition finale : insère le sprite selon sa priorité, puis applique les
     * effets de couleur (mélange alpha, éclaircissement, assombrissement) selon
     * `BLDCNT` et la fenêtre.
     */
    private fun composeSimpleLine(rowBase: Int) {
        for (x in 0 until SCREEN_WIDTH) {
            val showObj = objOpaque[x] &&
                windowMask[x] and OBJ_BIT != 0 &&
                objPriority[x] <= linePriority[x]
            frame[rowBase + x] = if (showObj) objColor[x] else lineColor[x]
        }
    }

    private fun composeLine(rowBase: Int, bldcnt: Int) {
        val effectMode = (bldcnt ushr 6) and 0x3
        for (x in 0 until SCREEN_WIDTH) {
            var topColor = lineColor[x]
            var topLayer = lineLayer[x]
            var secondColor = lineColor2[x]
            var secondLayer = lineLayer2[x]
            var semiTransparent = false

            if (objOpaque[x] && windowMask[x] and OBJ_BIT != 0) {
                if (objPriority[x] <= linePriority[x]) {
                    // Le sprite passe devant : l'ancien sommet recule d'un rang.
                    secondColor = topColor
                    secondLayer = topLayer
                    topColor = objColor[x]
                    topLayer = LAYER_ID_OBJ
                    semiTransparent = objSemiTransparent[x]
                } else if (objPriority[x] <= linePriority2[x]) {
                    secondColor = objColor[x]
                    secondLayer = LAYER_ID_OBJ
                }
            }

            frame[rowBase + x] = applyColorEffects(
                x, effectMode, bldcnt, topColor, topLayer, secondColor, secondLayer, semiTransparent
            )
        }
    }

    private fun applyColorEffects(
        x: Int,
        effectMode: Int,
        bldcnt: Int,
        topColor: Int,
        topLayer: Int,
        secondColor: Int,
        secondLayer: Int,
        semiTransparent: Boolean,
    ): Int {
        if (windowMask[x] and WINDOW_EFFECTS == 0) return topColor
        val isTarget1 = bldcnt and (1 shl topLayer) != 0
        val isTarget2 = bldcnt and (1 shl (8 + secondLayer)) != 0
        // Un sprite semi-transparent impose le mélange alpha, quel que soit le
        // mode d'effet programmé.
        if (semiTransparent && isTarget2) return alphaBlend(topColor, secondColor)
        if (!isTarget1) return topColor
        return when (effectMode) {
            1 -> if (isTarget2) alphaBlend(topColor, secondColor) else topColor
            2 -> adjustBrightness(topColor, toWhite = true)
            3 -> adjustBrightness(topColor, toWhite = false)
            else -> topColor
        }
    }

    /** Mélange alpha pondéré par `BLDALPHA` (coefficients EVA et EVB, /16). */
    private fun alphaBlend(top: Int, bottom: Int): Int {
        val bldalpha = reg16(0x52)
        val eva = minOf(bldalpha and 0x1F, 16)
        val evb = minOf((bldalpha ushr 8) and 0x1F, 16)
        val r = minOf(255, (channel(top, 16) * eva + channel(bottom, 16) * evb) shr 4)
        val g = minOf(255, (channel(top, 8) * eva + channel(bottom, 8) * evb) shr 4)
        val b = minOf(255, (channel(top, 0) * eva + channel(bottom, 0) * evb) shr 4)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Éclaircissement vers le blanc ou assombrissement vers le noir (`BLDY`). */
    private fun adjustBrightness(color: Int, toWhite: Boolean): Int {
        val evy = minOf(reg16(0x54) and 0x1F, 16)
        val r = brightnessChannel(channel(color, 16), evy, toWhite)
        val g = brightnessChannel(channel(color, 8), evy, toWhite)
        val b = brightnessChannel(channel(color, 0), evy, toWhite)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun brightnessChannel(value: Int, evy: Int, toWhite: Boolean): Int =
        if (toWhite) value + (((255 - value) * evy) shr 4) else value - ((value * evy) shr 4)

    private fun channel(color: Int, shift: Int): Int = (color ushr shift) and 0xFF

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
        val tileY = effY ushr 3
        val py0 = effY and 7
        val layerBit = 1 shl bg

        // L'entrée de carte ne change que tous les huit pixels. La conserver
        // évite jusqu'à sept lectures VRAM et sept décodages identiques.
        var cachedTileX = -1
        var tileNum = 0
        var hflip = false
        var vflip = false
        var palBank = 0

        for (x in 0 until SCREEN_WIDTH) {
            if (windowMask[x] and layerBit == 0) continue
            val effX = (x + hofs) and (widthPixels - 1)
            val tileX = effX ushr 3

            if (tileX != cachedTileX) {
                val mapAddr = screenBase + screenBlockOffset(tileX, tileY, size) +
                    ((tileY and 31) * 32 + (tileX and 31)) * 2
                val entry = vram16(mapAddr)
                tileNum = entry and 0x3FF
                hflip = entry and 0x400 != 0
                vflip = entry and 0x800 != 0
                palBank = (entry ushr 12) and 0xF
                cachedTileX = tileX
            }

            val inTileX = effX and 7
            val px = if (hflip) 7 - inTileX else inTileX
            val py = if (vflip) 7 - py0 else py0

            val colorIndex = if (is8bpp) {
                vramByte(charBase + tileNum * 64 + py * 8 + px)
            } else {
                val byte = vramByte(charBase + tileNum * 32 + py * 4 + (px ushr 1))
                val nibble = if (px and 1 == 0) byte and 0xF else (byte ushr 4) and 0xF
                if (nibble == 0) 0 else palBank * 16 + nibble
            }
            if (colorIndex != 0) pushPixel(x, paletteColor(colorIndex), priority, bg)
        }
    }

    /**
     * Dessine les sprites de la ligne [y] dans les tampons objet : tailles
     * carrées et rectangulaires, 4/8 bpp, mappage 1D/2D, retournements,
     * priorité, **rotation/mise à l'échelle** (avec ou sans surface doublée),
     * sprites **semi-transparents** et sprites de **fenêtre objet**.
     */
    private fun renderSprites(y: Int, dispcnt: Int) {
        if (dispcnt and 0x1000 == 0) return // OBJ désactivés
        val oneDimensional = dispcnt and 0x0040 != 0
        for (i in 0 until 128) {
            val base = i * 8
            val attr0 = oam16(base)
            // Bits 8-9 : 0 normal, 1 affine, 2 masqué, 3 affine à surface doublée.
            val transform = (attr0 ushr 8) and 0x3
            if (transform == 2) continue
            val shape = (attr0 ushr 14) and 0x3
            if (shape == 3) continue            // forme interdite
            val graphicsMode = (attr0 ushr 10) and 0x3
            if (graphicsMode == 3) continue     // mode interdit

            val affine = transform == 1 || transform == 3
            val doubleSize = transform == 3
            val isWindowSprite = graphicsMode == 2
            val semiTransparent = graphicsMode == 1

            val attr1 = oam16(base + 2)
            val attr2 = oam16(base + 4)
            val sizeIdx = (attr1 ushr 14) and 0x3
            val sizeKey = shape * 4 + sizeIdx
            val w = OBJ_WIDTH[sizeKey]
            val h = OBJ_HEIGHT[sizeKey]
            // Emprise à l'écran : doublée pour un sprite affine « double size ».
            val boxW = if (doubleSize) w * 2 else w
            val boxH = if (doubleSize) h * 2 else h

            val yPos = attr0 and 0xFF
            val sy = (y - yPos) and 0xFF
            if (sy >= boxH) continue

            var xPos = attr1 and 0x1FF
            if (xPos >= 0x100) xPos -= 0x200    // X signé 9 bits

            val is8bpp = attr0 and 0x2000 != 0
            val palBank = (attr2 ushr 12) and 0xF
            val priority = (attr2 ushr 10) and 0x3
            val tileBase = attr2 and 0x3FF
            val slots = if (is8bpp) 2 else 1
            val rowStride = if (oneDimensional) (w / 8) * slots else 32

            // Matrice affine : quatre mots répartis dans le champ `attr3` d'un
            // groupe de 32 entrées OAM. Identité si le sprite ne tourne pas.
            var pa = 0x100
            var pb = 0
            var pc = 0
            var pd = 0x100
            if (affine) {
                val group = ((attr1 ushr 9) and 0x1F) * 32
                pa = signed16(oam16(group + 0x06))
                pb = signed16(oam16(group + 0x0E))
                pc = signed16(oam16(group + 0x16))
                pd = signed16(oam16(group + 0x1E))
            }
            val hflip = !affine && attr1 and 0x1000 != 0
            val vflip = !affine && attr1 and 0x2000 != 0

            for (col in 0 until boxW) {
                val screenX = xPos + col
                if (screenX < 0 || screenX >= SCREEN_WIDTH) continue

                val texX: Int
                val texY: Int
                if (affine) {
                    // Transformation autour du centre de l'emprise, résultat
                    // ramené dans les dimensions réelles du sprite.
                    val dx = col - boxW / 2
                    val dy = sy - boxH / 2
                    texX = ((pa * dx + pb * dy) shr 8) + w / 2
                    texY = ((pc * dx + pd * dy) shr 8) + h / 2
                    if (texX < 0 || texX >= w || texY < 0 || texY >= h) continue
                } else {
                    texX = if (hflip) w - 1 - col else col
                    texY = if (vflip) h - 1 - sy else sy
                }

                val tileIndex = tileBase + (texY / 8) * rowStride + (texX / 8) * slots
                val inX = texX and 7
                val inY = texY and 7
                val colorIndex = if (is8bpp) {
                    vramByte(OBJ_TILE_BASE + tileIndex * 32 + inY * 8 + inX)
                } else {
                    val byte = vramByte(OBJ_TILE_BASE + tileIndex * 32 + inY * 4 + inX / 2)
                    val nibble = if (inX and 1 == 0) byte and 0xF else (byte ushr 4) and 0xF
                    if (nibble == 0) 0 else palBank * 16 + nibble
                }
                if (colorIndex == 0) continue

                if (isWindowSprite) {
                    // Ne s'affiche pas : délimite la « fenêtre objet ».
                    objWindow[screenX] = true
                    continue
                }
                if (objOpaque[screenX]) continue // un sprite d'index inférieur prime
                if (collectLayerStats) layerPixelsPending[LAYER_ID_OBJ]++
                objColor[screenX] = paletteColor(OBJ_PALETTE_BASE + colorIndex)
                objPriority[screenX] = priority
                objOpaque[screenX] = true
                objSemiTransparent[screenX] = semiTransparent
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
            if (windowMask[x] and BG2_BIT == 0) continue
            pushPixel(x, bgr555ToArgb(vram16((y * SCREEN_WIDTH + x) * 2)), priority, 2)
        }
    }

    private fun renderBitmapMode4(y: Int, dispcnt: Int) {
        if (dispcnt and 0x0400 == 0) return
        val page = if (dispcnt and 0x0010 != 0) 0xA000 else 0
        val priority = bg2Priority()
        for (x in 0 until SCREEN_WIDTH) {
            if (windowMask[x] and BG2_BIT == 0) continue
            pushPixel(x, paletteColor(vramByte(page + y * SCREEN_WIDTH + x)), priority, 2)
        }
    }

    private fun renderBitmapMode5(y: Int, dispcnt: Int) {
        if (dispcnt and 0x0400 == 0 || y >= MODE5_HEIGHT) return
        val page = if (dispcnt and 0x0010 != 0) 0xA000 else 0
        val priority = bg2Priority()
        for (x in 0 until MODE5_WIDTH) {
            if (windowMask[x] and BG2_BIT == 0) continue
            pushPixel(x, bgr555ToArgb(vram16(page + (y * MODE5_WIDTH + x) * 2)), priority, 2)
        }
    }

    // ---- Accès mémoire ----

    /**
     * Écriture d'un point de référence affine (`BG2X`, `BG2Y`, `BG3X`, `BG3Y`).
     *
     * Le matériel recopie aussitôt la valeur écrite dans le registre interne que
     * suit le rendu, sans attendre l'image suivante. C'est ce qui permet à un jeu
     * de déplacer une couche affine en cours d'affichage, ligne par ligne ; sans
     * cela, l'écriture resterait sans effet jusqu'au prochain verrouillage.
     */
    fun onAffineReferenceWrite(offset: Int) {
        when {
            offset < 0x2C -> bg2RefX = signed28(reg32(0x28))
            offset < 0x30 -> bg2RefY = signed28(reg32(0x2C))
            offset < 0x3C -> bg3RefX = signed28(reg32(0x38))
            else -> bg3RefY = signed28(reg32(0x3C))
        }
    }

    /** Recharge les points de référence affines depuis `BGxX`/`BGxY`. */
    private fun reloadAffineReferencePoints() {
        bg2RefX = signed28(reg32(0x28))
        bg2RefY = signed28(reg32(0x2C))
        bg3RefX = signed28(reg32(0x38))
        bg3RefY = signed28(reg32(0x3C))
    }

    /** Étend le signe d'une valeur 16 bits (paramètres affines, format 8.8). */
    private fun signed16(value: Int): Int = (value shl 16) shr 16

    /** Étend le signe d'une valeur 28 bits (points de référence, format 20.8). */
    private fun signed28(value: Int): Int = (value shl 4) shr 4

    private fun reg32(offset: Int): Int = reg16(offset) or (reg16(offset + 2) shl 16)

    private fun reg16(offset: Int): Int =
        (bus.io[offset].toInt() and 0xFF) or ((bus.io[offset + 1].toInt() and 0xFF) shl 8)

    private fun vramByte(offset: Int): Int =
        if (offset in bus.vram.indices) bus.vram[offset].toInt() and 0xFF else 0

    private fun vram16(offset: Int): Int = vramByte(offset) or (vramByte(offset + 1) shl 8)

    private fun oam16(offset: Int): Int =
        (bus.oam[offset].toInt() and 0xFF) or ((bus.oam[offset + 1].toInt() and 0xFF) shl 8)

    private fun beginPaletteLine() {
        if (paletteEpoch == Int.MAX_VALUE) {
            paletteStamp.fill(0)
            paletteEpoch = 1
        } else {
            paletteEpoch++
        }
    }

    /** Couleur de la palette BG ou OBJ à l'index [index], en ARGB. */
    private fun paletteColor(index: Int): Int {
        if (paletteStamp[index] != paletteEpoch) {
            val a = index * 2
            val raw = (bus.paletteRam[a].toInt() and 0xFF) or
                ((bus.paletteRam[a + 1].toInt() and 0xFF) shl 8)
            if (paletteRaw[index] != raw) {
                paletteRaw[index] = raw
                paletteCache[index] = bgr555ToArgb(raw)
            }
            paletteStamp[index] = paletteEpoch
        }
        return paletteCache[index]
    }

    companion object {
        const val SCREEN_WIDTH = 240
        const val SCREEN_HEIGHT = 160
        const val STATE_FIELD_COUNT = 9

        private const val MODE5_WIDTH = 160
        private const val MODE5_HEIGHT = 128
        private const val PALETTE_COLORS = 512

        // Bits d'effet et cibles secondaires de BLDCNT. Tous à zéro, aucune
        // composition avec le second plan n'est possible.
        private const val SIMPLE_COMPOSITION_MASK = 0x3FC0

        /** Fond : priorité la plus basse (4 = derrière tout arrière-plan 0..3). */
        private const val LAYER_BACKDROP = 4

        // Identifiants de couche pour BLDCNT : BG0..BG3 = 0..3, OBJ = 4, fond = 5.
        private const val LAYER_ID_OBJ = 4
        private const val LAYER_ID_BACKDROP = 5

        // Bits du masque de fenêtre.
        private const val BG2_BIT = 1 shl 2
        private const val OBJ_BIT = 1 shl 4
        private const val WINDOW_EFFECTS = 1 shl 5

        /** Toutes les couches et les effets autorisés (aucune fenêtre active). */
        private const val WINDOW_ALL = 0x3F

        /** Base des tuiles de sprites en VRAM (0x0601_0000). */
        private const val OBJ_TILE_BASE = 0x1_0000

        /** Décalage d'index de la palette OBJ (256 couleurs après la palette BG). */
        private const val OBJ_PALETTE_BASE = 256

        // Largeur/hauteur des sprites, tables plates indexées `forme * 4 + taille`
        // (un seul déréférencement dans la boucle des 128 sprites).
        private val OBJ_WIDTH = intArrayOf(
            8, 16, 32, 64,  // carré
            16, 32, 32, 64, // horizontal
            8, 8, 16, 32,   // vertical
        )
        private val OBJ_HEIGHT = intArrayOf(
            8, 16, 32, 64,  // carré
            8, 8, 16, 32,   // horizontal
            16, 32, 32, 64, // vertical
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
