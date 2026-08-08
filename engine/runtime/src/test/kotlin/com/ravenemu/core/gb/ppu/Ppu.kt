package com.ravenemu.core.gb.ppu

import com.ravenemu.core.gb.Interrupt
import com.ravenemu.core.gb.InterruptController

/**
 * PPU Game Boy, modes DMG (monochrome, niveaux 0–3) et Game Boy Color
 * (couleur ARGB). Machine de modes au cycle (OAM 80, transfert 172,
 * HBlank 204, VBlank 10 lignes), rendu scanline au début du mode 3, fond,
 * fenêtre, sprites 8×8/8×16, interruptions STAT et comparaison LYC.
 *
 * En mode CGB : deux banques de VRAM, palettes couleur BG/OBJ (CRAM 15 bits),
 * attributs de tuiles en banque 1 (palette, banque de tuile, retournements,
 * priorité), priorité maître LCDC bit 0. Le framebuffer contient alors des
 * couleurs ARGB ; en DMG il contient les niveaux 0–3, colorisés par le
 * renderer.
 *
 * Limites documentées : pas d'effets mid-scanline, durée du mode 3 fixe (172),
 * file de pixels non émulée, registre OPRI (priorité sprites CGB/DMG) non
 * émulé (ordre par index OAM en CGB).
 */
class Ppu(
    private val interrupts: InterruptController,
    private val cgbMode: Boolean = false,
) {

    val vram = ByteArray(0x4000) // 2 banques (bank 1 inutilisée en DMG)
    val oam = ByteArray(0xA0)

    var lcdc = 0x91
        private set
    private var statEnableBits = 0
    var scy = 0
    var scx = 0
    var ly = 0
        private set
    var lyc = 0
        private set
    var bgp = 0xFC
    var obp0 = 0x00
    var obp1 = 0x00
    var wy = 0
    var wx = 0

    // ---- État CGB ----
    var vramBank = 0
        private set
    val bgCram = ByteArray(64) // 8 palettes × 4 couleurs × 2 octets
    val objCram = ByteArray(64)
    private var bcpsIndex = 0
    private var bcpsAutoInc = false
    private var ocpsIndex = 0
    private var ocpsAutoInc = false

    /** Couleurs ARGB pré-calculées à partir de la CRAM (mises à jour à l'écriture). */
    private val bgArgb = IntArray(32)
    private val objArgb = IntArray(32)

    var mode = MODE_OAM_SCAN
        private set
    private var lineDot = 0
    private var windowLine = 0
    private var statLine = false

    /** `true` juste après l'entrée en HBlank d'une ligne visible (HDMA). */
    var enteredHBlank = false

    val completedFrame = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)
    private val workingFrame = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)

    var frameReady = false

    // Tampons de ligne (aucune allocation pendant l'émulation).
    private val bgColorIndexLine = IntArray(SCREEN_WIDTH)
    private val bgPriorityLine = BooleanArray(SCREEN_WIDTH)
    private val spriteIndices = IntArray(10)

    val lcdEnabled: Boolean get() = (lcdc and 0x80) != 0

    // ---- Accès bus ----

    fun readVram(address: Int): Int {
        if (lcdEnabled && mode == MODE_TRANSFER) return 0xFF
        return vram[vramBank * 0x2000 + (address and 0x1FFF)].toInt() and 0xFF
    }

    fun writeVram(address: Int, value: Int) {
        if (lcdEnabled && mode == MODE_TRANSFER) return
        vram[vramBank * 0x2000 + (address and 0x1FFF)] = value.toByte()
    }

    fun writeVramBank(value: Int) {
        if (cgbMode) vramBank = value and 0x01
    }

    fun readVramBank(): Int = if (cgbMode) (vramBank or 0xFE) else 0xFF

    fun readOam(address: Int): Int {
        if (lcdEnabled && (mode == MODE_OAM_SCAN || mode == MODE_TRANSFER)) return 0xFF
        return oam[address and 0xFF].toInt() and 0xFF
    }

    fun writeOam(address: Int, value: Int) {
        if (lcdEnabled && (mode == MODE_OAM_SCAN || mode == MODE_TRANSFER)) return
        oam[address and 0xFF] = value.toByte()
    }

    fun writeOamDirect(index: Int, value: Int) {
        oam[index] = value.toByte()
    }

    // ---- Palettes couleur CGB ----

    fun writeBcps(value: Int) {
        bcpsIndex = value and 0x3F
        bcpsAutoInc = (value and 0x80) != 0
    }

    fun readBcps(): Int = bcpsIndex or (if (bcpsAutoInc) 0x80 else 0) or 0x40

    fun writeBcpd(value: Int) {
        bgCram[bcpsIndex] = value.toByte()
        recomputeArgb(bgCram, bgArgb, bcpsIndex)
        if (bcpsAutoInc) bcpsIndex = (bcpsIndex + 1) and 0x3F
    }

    fun readBcpd(): Int = bgCram[bcpsIndex].toInt() and 0xFF

    fun writeOcps(value: Int) {
        ocpsIndex = value and 0x3F
        ocpsAutoInc = (value and 0x80) != 0
    }

    fun readOcps(): Int = ocpsIndex or (if (ocpsAutoInc) 0x80 else 0) or 0x40

    fun writeOcpd(value: Int) {
        objCram[ocpsIndex] = value.toByte()
        recomputeArgb(objCram, objArgb, ocpsIndex)
        if (ocpsAutoInc) ocpsIndex = (ocpsIndex + 1) and 0x3F
    }

    fun readOcpd(): Int = objCram[ocpsIndex].toInt() and 0xFF

    /** Recalcule la couleur ARGB affectée par l'octet CRAM [byteIndex]. */
    private fun recomputeArgb(cram: ByteArray, argb: IntArray, byteIndex: Int) {
        val colorIndex = byteIndex / 2
        val lo = cram[colorIndex * 2].toInt() and 0xFF
        val hi = cram[colorIndex * 2 + 1].toInt() and 0xFF
        argb[colorIndex] = bgr555ToArgb(lo or (hi shl 8))
    }

    fun writeLcdc(value: Int) {
        val wasEnabled = lcdEnabled
        lcdc = value and 0xFF
        if (wasEnabled && !lcdEnabled) {
            ly = 0
            lineDot = 0
            mode = MODE_HBLANK
            windowLine = 0
            completedFrame.fill(0)
            frameReady = true
            statLine = false
        } else if (!wasEnabled && lcdEnabled) {
            ly = 0
            lineDot = 0
            windowLine = 0
            setMode(MODE_OAM_SCAN)
            checkLycAndStat()
        }
    }

    fun readStat(): Int {
        val coincidence = if (ly == lyc) 0x04 else 0
        val modeBits = if (lcdEnabled) mode else 0
        return 0x80 or statEnableBits or coincidence or modeBits
    }

    fun writeStat(value: Int) {
        statEnableBits = value and 0x78
        checkLycAndStat()
    }

    fun writeLyc(value: Int) {
        lyc = value and 0xFF
        checkLycAndStat()
    }

    // ---- Horloge ----

    fun tick(cycles: Int) {
        if (!lcdEnabled) return
        var remaining = cycles
        while (remaining > 0) {
            val step = minOf(remaining, nextEventDelay())
            lineDot += step
            remaining -= step
            processLinePosition()
        }
    }

    private fun nextEventDelay(): Int = when {
        ly >= SCREEN_HEIGHT -> DOTS_PER_LINE - lineDot
        lineDot < OAM_DOTS -> OAM_DOTS - lineDot
        lineDot < OAM_DOTS + TRANSFER_DOTS -> OAM_DOTS + TRANSFER_DOTS - lineDot
        else -> DOTS_PER_LINE - lineDot
    }

    private fun processLinePosition() {
        if (lineDot >= DOTS_PER_LINE) {
            lineDot -= DOTS_PER_LINE
            ly++
            when {
                ly == SCREEN_HEIGHT -> enterVBlank()
                ly > LAST_LINE -> {
                    ly = 0
                    windowLine = 0
                    setMode(MODE_OAM_SCAN)
                }
                ly < SCREEN_HEIGHT -> setMode(MODE_OAM_SCAN)
            }
            checkLycAndStat()
            return
        }
        if (ly < SCREEN_HEIGHT) {
            val expected = when {
                lineDot < OAM_DOTS -> MODE_OAM_SCAN
                lineDot < OAM_DOTS + TRANSFER_DOTS -> MODE_TRANSFER
                else -> MODE_HBLANK
            }
            if (expected != mode) {
                setMode(expected)
                if (expected == MODE_TRANSFER) {
                    renderLine()
                } else if (expected == MODE_HBLANK) {
                    enteredHBlank = true
                }
            }
        }
    }

    private fun enterVBlank() {
        setMode(MODE_VBLANK)
        interrupts.request(Interrupt.VBLANK)
        System.arraycopy(workingFrame, 0, completedFrame, 0, completedFrame.size)
        frameReady = true
    }

    private fun setMode(newMode: Int) {
        mode = newMode
        checkLycAndStat()
    }

    private fun checkLycAndStat() {
        if (!lcdEnabled) {
            statLine = false
            return
        }
        val line =
            ((statEnableBits and 0x08) != 0 && mode == MODE_HBLANK) ||
                ((statEnableBits and 0x10) != 0 && mode == MODE_VBLANK) ||
                ((statEnableBits and 0x20) != 0 && mode == MODE_OAM_SCAN) ||
                ((statEnableBits and 0x40) != 0 && ly == lyc)
        if (line && !statLine) interrupts.request(Interrupt.STAT)
        statLine = line
    }

    // ---- Rendu ----

    private fun renderLine() {
        val rowBase = ly * SCREEN_WIDTH
        if (cgbMode) {
            renderBackgroundAndWindowCgb(rowBase)
            renderSpritesCgb(rowBase)
        } else {
            renderBackgroundAndWindowDmg(rowBase)
            renderSpritesDmg(rowBase)
        }
    }

    private fun vramByte(bank: Int, address: Int): Int =
        vram[bank * 0x2000 + (address and 0x1FFF)].toInt() and 0xFF

    // ---- Rendu DMG ----

    private fun renderBackgroundAndWindowDmg(rowBase: Int) {
        val bgEnabled = (lcdc and 0x01) != 0
        if (!bgEnabled) {
            for (x in 0 until SCREEN_WIDTH) {
                bgColorIndexLine[x] = 0
                workingFrame[rowBase + x] = 0
            }
        } else {
            val mapBase = if ((lcdc and 0x08) != 0) 0x1C00 else 0x1800
            val bgY = (ly + scy) and 0xFF
            val tileRow = bgY shr 3
            val pixelRow = bgY and 0x07
            for (x in 0 until SCREEN_WIDTH) {
                val bgX = (x + scx) and 0xFF
                val tileIndex = vramByte(0, mapBase + tileRow * 32 + (bgX shr 3))
                val colorIndex = tilePixelDmg(tileIndex, pixelRow, bgX and 0x07)
                bgColorIndexLine[x] = colorIndex
                workingFrame[rowBase + x] = (bgp shr (colorIndex * 2)) and 0x03
            }
        }

        val windowEnabled = bgEnabled && (lcdc and 0x20) != 0
        if (windowEnabled && ly >= wy && wx <= 166) {
            val startX = maxOf(0, wx - 7)
            if (startX < SCREEN_WIDTH) {
                val mapBase = if ((lcdc and 0x40) != 0) 0x1C00 else 0x1800
                val tileRow = windowLine shr 3
                val pixelRow = windowLine and 0x07
                for (x in startX until SCREEN_WIDTH) {
                    val winX = x - (wx - 7)
                    val tileIndex = vramByte(0, mapBase + tileRow * 32 + (winX shr 3))
                    val colorIndex = tilePixelDmg(tileIndex, pixelRow, winX and 0x07)
                    bgColorIndexLine[x] = colorIndex
                    workingFrame[rowBase + x] = (bgp shr (colorIndex * 2)) and 0x03
                }
                windowLine++
            }
        }
    }

    private fun tilePixelDmg(tileIndex: Int, pixelRow: Int, pixelCol: Int): Int {
        val tileAddress = if ((lcdc and 0x10) != 0) {
            tileIndex * 16
        } else {
            0x1000 + tileIndex.toByte().toInt() * 16
        }
        val lo = vramByte(0, tileAddress + pixelRow * 2)
        val hi = vramByte(0, tileAddress + pixelRow * 2 + 1)
        val bit = 7 - pixelCol
        return (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)
    }

    private fun renderSpritesDmg(rowBase: Int) {
        if ((lcdc and 0x02) == 0) return
        val height = if ((lcdc and 0x04) != 0) 16 else 8
        val count = scanSprites(height)

        // Priorité DMG : X le plus petit, puis index OAM le plus petit.
        sortSprites(count) { a, b ->
            val ax = oam[a * 4 + 1].toInt() and 0xFF
            val bx = oam[b * 4 + 1].toInt() and 0xFF
            if (ax != bx) ax > bx else a > b
        }

        for (s in 0 until count) {
            val index = spriteIndices[s] * 4
            val spriteY = (oam[index].toInt() and 0xFF) - 16
            val spriteX = (oam[index + 1].toInt() and 0xFF) - 8
            var tileIndex = oam[index + 2].toInt() and 0xFF
            val attributes = oam[index + 3].toInt() and 0xFF
            if (height == 16) tileIndex = tileIndex and 0xFE
            var row = ly - spriteY
            if ((attributes and 0x40) != 0) row = height - 1 - row
            val tileAddress = tileIndex * 16 + row * 2
            val lo = vramByte(0, tileAddress)
            val hi = vramByte(0, tileAddress + 1)
            val palette = if ((attributes and 0x10) != 0) obp1 else obp0
            val behindBg = (attributes and 0x80) != 0
            for (px in 0 until 8) {
                val x = spriteX + px
                if (x < 0 || x >= SCREEN_WIDTH) continue
                val bit = if ((attributes and 0x20) != 0) px else 7 - px
                val colorIndex = (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)
                if (colorIndex == 0) continue
                if (behindBg && bgColorIndexLine[x] != 0) continue
                workingFrame[rowBase + x] = (palette shr (colorIndex * 2)) and 0x03
            }
        }
    }

    // ---- Rendu CGB ----

    private fun renderBackgroundAndWindowCgb(rowBase: Int) {
        // En CGB le fond est toujours dessiné ; LCDC bit 0 ne fait que régler
        // la priorité maître (traitée au dessin des sprites).
        val mapBase = if ((lcdc and 0x08) != 0) 0x1C00 else 0x1800
        val bgY = (ly + scy) and 0xFF
        val tileRow = bgY shr 3
        val pixelRow = bgY and 0x07
        for (x in 0 until SCREEN_WIDTH) {
            val bgX = (x + scx) and 0xFF
            drawBgPixelCgb(rowBase, x, mapBase, tileRow, pixelRow, bgX shr 3, bgX and 0x07)
        }

        val windowEnabled = (lcdc and 0x20) != 0
        if (windowEnabled && ly >= wy && wx <= 166) {
            val startX = maxOf(0, wx - 7)
            if (startX < SCREEN_WIDTH) {
                val winMap = if ((lcdc and 0x40) != 0) 0x1C00 else 0x1800
                val wTileRow = windowLine shr 3
                val wPixelRow = windowLine and 0x07
                for (x in startX until SCREEN_WIDTH) {
                    val winX = x - (wx - 7)
                    drawBgPixelCgb(
                        rowBase, x, winMap, wTileRow, wPixelRow, winX shr 3, winX and 0x07,
                    )
                }
                windowLine++
            }
        }
    }

    private fun drawBgPixelCgb(
        rowBase: Int, x: Int, mapBase: Int, tileRow: Int, pixelRow: Int,
        tileCol: Int, pixelCol: Int,
    ) {
        val mapAddr = mapBase + tileRow * 32 + tileCol
        val tileIndex = vramByte(0, mapAddr)
        val attr = vramByte(1, mapAddr)
        val paletteNum = attr and 0x07
        val tileBank = (attr shr 3) and 0x01
        val xFlip = (attr and 0x20) != 0
        val yFlip = (attr and 0x40) != 0
        val priority = (attr and 0x80) != 0

        val tileAddress = if ((lcdc and 0x10) != 0) {
            tileIndex * 16
        } else {
            0x1000 + tileIndex.toByte().toInt() * 16
        }
        val row = if (yFlip) 7 - pixelRow else pixelRow
        val lo = vramByte(tileBank, tileAddress + row * 2)
        val hi = vramByte(tileBank, tileAddress + row * 2 + 1)
        val bit = if (xFlip) pixelCol else 7 - pixelCol
        val colorIndex = (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)

        bgColorIndexLine[x] = colorIndex
        bgPriorityLine[x] = priority
        workingFrame[rowBase + x] = bgArgb[paletteNum * 4 + colorIndex]
    }

    private fun renderSpritesCgb(rowBase: Int) {
        if ((lcdc and 0x02) == 0) return
        val height = if ((lcdc and 0x04) != 0) 16 else 8
        val count = scanSprites(height)

        // Priorité CGB : index OAM le plus petit au-dessus.
        sortSprites(count) { a, b -> a > b }

        val masterPriority = (lcdc and 0x01) != 0
        for (s in 0 until count) {
            val index = spriteIndices[s] * 4
            val spriteY = (oam[index].toInt() and 0xFF) - 16
            val spriteX = (oam[index + 1].toInt() and 0xFF) - 8
            var tileIndex = oam[index + 2].toInt() and 0xFF
            val attributes = oam[index + 3].toInt() and 0xFF
            if (height == 16) tileIndex = tileIndex and 0xFE
            var row = ly - spriteY
            if ((attributes and 0x40) != 0) row = height - 1 - row
            val tileBank = (attributes shr 3) and 0x01
            val paletteNum = attributes and 0x07
            val tileAddress = tileIndex * 16 + row * 2
            val lo = vramByte(tileBank, tileAddress)
            val hi = vramByte(tileBank, tileAddress + 1)
            val spriteBehindBg = (attributes and 0x80) != 0
            for (px in 0 until 8) {
                val x = spriteX + px
                if (x < 0 || x >= SCREEN_WIDTH) continue
                val bit = if ((attributes and 0x20) != 0) px else 7 - px
                val colorIndex = (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)
                if (colorIndex == 0) continue
                if (!spriteVisibleOverBg(x, spriteBehindBg, masterPriority)) continue
                workingFrame[rowBase + x] = objArgb[paletteNum * 4 + colorIndex]
            }
        }
    }

    /** Règle de priorité BG/sprite en CGB. */
    private fun spriteVisibleOverBg(x: Int, spriteBehindBg: Boolean, masterPriority: Boolean): Boolean {
        if (bgColorIndexLine[x] == 0) return true // fond transparent
        if (!masterPriority) return true // priorité maître désactivée
        if (bgPriorityLine[x]) return false // attribut BG prioritaire
        return !spriteBehindBg
    }

    // ---- Sprites communs ----

    private fun scanSprites(height: Int): Int {
        var count = 0
        var i = 0
        while (i < 40 && count < 10) {
            val spriteY = (oam[i * 4].toInt() and 0xFF) - 16
            if (ly >= spriteY && ly < spriteY + height) spriteIndices[count++] = i
            i++
        }
        return count
    }

    /** Tri par insertion : `higherPriority(a, b)` vrai si a doit être dessiné avant b. */
    private inline fun sortSprites(count: Int, higherPriority: (Int, Int) -> Boolean) {
        for (a in 1 until count) {
            val key = spriteIndices[a]
            var b = a - 1
            while (b >= 0 && !higherPriority(spriteIndices[b], key)) {
                spriteIndices[b + 1] = spriteIndices[b]
                b--
            }
            spriteIndices[b + 1] = key
        }
    }

    // ---- Sérialisation ----

    fun stateFields(): IntArray = intArrayOf(
        lcdc, statEnableBits, scy, scx, ly, lyc, bgp, obp0, obp1, wy, wx,
        mode, lineDot, windowLine, if (statLine) 1 else 0,
        vramBank, bcpsIndex, if (bcpsAutoInc) 1 else 0,
        ocpsIndex, if (ocpsAutoInc) 1 else 0,
    )

    fun restoreState(fields: IntArray) {
        lcdc = fields[0]
        statEnableBits = fields[1]
        scy = fields[2]
        scx = fields[3]
        ly = fields[4]
        lyc = fields[5]
        bgp = fields[6]
        obp0 = fields[7]
        obp1 = fields[8]
        wy = fields[9]
        wx = fields[10]
        mode = fields[11]
        lineDot = fields[12]
        windowLine = fields[13]
        statLine = fields[14] != 0
        vramBank = fields[15]
        bcpsIndex = fields[16]
        bcpsAutoInc = fields[17] != 0
        ocpsIndex = fields[18]
        ocpsAutoInc = fields[19] != 0
    }

    /** Reconstruit les couleurs ARGB après restauration de la CRAM. */
    fun rebuildColorCache() {
        for (i in 0 until 32) {
            recomputeArgb(bgCram, bgArgb, i * 2)
            recomputeArgb(objCram, objArgb, i * 2)
        }
    }

    companion object {
        const val SCREEN_WIDTH = 160
        const val SCREEN_HEIGHT = 144
        const val LAST_LINE = 153
        const val DOTS_PER_LINE = 456
        const val OAM_DOTS = 80
        const val TRANSFER_DOTS = 172

        const val MODE_HBLANK = 0
        const val MODE_VBLANK = 1
        const val MODE_OAM_SCAN = 2
        const val MODE_TRANSFER = 3

        /** Convertit une couleur BGR555 (15 bits) en ARGB 8888 opaque. */
        fun bgr555ToArgb(value: Int): Int {
            val r5 = value and 0x1F
            val g5 = (value shr 5) and 0x1F
            val b5 = (value shr 10) and 0x1F
            val r = (r5 shl 3) or (r5 shr 2)
            val g = (g5 shl 3) or (g5 shr 2)
            val b = (b5 shl 3) or (b5 shr 2)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
