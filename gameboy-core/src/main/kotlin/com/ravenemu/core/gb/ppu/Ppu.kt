package com.ravenemu.core.gb.ppu

import com.ravenemu.core.gb.Interrupt
import com.ravenemu.core.gb.InterruptController

/**
 * PPU DMG : machine de modes au cycle (OAM 80, transfert 172, HBlank 204,
 * VBlank 10 lignes), rendu scanline par scanline au début du mode 3, fond,
 * fenêtre (compteur de ligne interne), sprites 8×8 et 8×16 avec priorités
 * DMG (X le plus petit puis index OAM), interruptions STAT sur front montant
 * et comparaison LYC.
 *
 * Le framebuffer produit contient des indices de teinte 0–3 (après palettes
 * BGP/OBP) ; la conversion couleur est faite par le moteur. Deux tampons sont
 * entretenus : la ligne en cours s'écrit dans le tampon de travail, basculé
 * dans [completedFrame] à l'entrée en VBlank.
 *
 * Limites documentées : pas d'effets mid-scanline (SCX/BGP changés pendant le
 * mode 3), durée du mode 3 fixe (172), file de pixels non émulée.
 */
class Ppu(private val interrupts: InterruptController) {

    val vram = ByteArray(0x2000)
    val oam = ByteArray(0xA0)

    var lcdc = 0x91
        private set
    private var statEnableBits = 0 // bits 3–6 de STAT
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

    var mode = MODE_OAM_SCAN
        private set
    private var lineDot = 0
    private var windowLine = 0
    private var statLine = false

    /** Dernière image complète (indices de teinte 0–3), figée en VBlank. */
    val completedFrame = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)
    private val workingFrame = IntArray(SCREEN_WIDTH * SCREEN_HEIGHT)

    /** Vrai lorsqu'une image a été terminée depuis le dernier acquittement. */
    var frameReady = false

    // Tampons de rendu réutilisés (aucune allocation pendant l'émulation).
    private val bgIndexLine = IntArray(SCREEN_WIDTH)
    private val spriteIndices = IntArray(10)

    val lcdEnabled: Boolean get() = (lcdc and 0x80) != 0

    // ---- Accès bus ----

    fun readVram(address: Int): Int {
        if (lcdEnabled && mode == MODE_TRANSFER) return 0xFF
        return vram[address and 0x1FFF].toInt() and 0xFF
    }

    fun writeVram(address: Int, value: Int) {
        if (lcdEnabled && mode == MODE_TRANSFER) return
        vram[address and 0x1FFF] = value.toByte()
    }

    fun readOam(address: Int): Int {
        if (lcdEnabled && (mode == MODE_OAM_SCAN || mode == MODE_TRANSFER)) return 0xFF
        return oam[address and 0xFF] .toInt() and 0xFF
    }

    fun writeOam(address: Int, value: Int) {
        if (lcdEnabled && (mode == MODE_OAM_SCAN || mode == MODE_TRANSFER)) return
        oam[address and 0xFF] = value.toByte()
    }

    /** Écriture directe utilisée par l'OAM DMA (jamais bloquée). */
    fun writeOamDirect(index: Int, value: Int) {
        oam[index] = value.toByte()
    }

    fun writeLcdc(value: Int) {
        val wasEnabled = lcdEnabled
        lcdc = value and 0xFF
        if (wasEnabled && !lcdEnabled) {
            // Extinction : LY repart à zéro, écran blanc.
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

    /** Cycles restants avant le prochain changement de mode ou de ligne. */
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
                if (expected == MODE_TRANSFER) renderLine()
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
        renderBackgroundAndWindow(rowBase)
        renderSprites(rowBase)
    }

    private fun renderBackgroundAndWindow(rowBase: Int) {
        val bgEnabled = (lcdc and 0x01) != 0
        if (!bgEnabled) {
            for (x in 0 until SCREEN_WIDTH) {
                bgIndexLine[x] = 0
                workingFrame[rowBase + x] = 0
            }
        } else {
            val mapBase = if ((lcdc and 0x08) != 0) 0x1C00 else 0x1800
            val bgY = (ly + scy) and 0xFF
            val tileRow = bgY shr 3
            val pixelRow = bgY and 0x07
            for (x in 0 until SCREEN_WIDTH) {
                val bgX = (x + scx) and 0xFF
                val tileIndex =
                    vram[mapBase + tileRow * 32 + (bgX shr 3)].toInt() and 0xFF
                val colorIndex = tilePixel(tileIndex, pixelRow, bgX and 0x07)
                bgIndexLine[x] = colorIndex
                workingFrame[rowBase + x] = (bgp shr (colorIndex * 2)) and 0x03
            }
        }

        // Fenêtre : dessinée par-dessus le fond à partir de WX-7.
        val windowEnabled = bgEnabled && (lcdc and 0x20) != 0
        if (windowEnabled && ly >= wy && wx <= 166) {
            val startX = maxOf(0, wx - 7)
            if (startX < SCREEN_WIDTH) {
                val mapBase = if ((lcdc and 0x40) != 0) 0x1C00 else 0x1800
                val tileRow = windowLine shr 3
                val pixelRow = windowLine and 0x07
                for (x in startX until SCREEN_WIDTH) {
                    val winX = x - (wx - 7)
                    val tileIndex =
                        vram[mapBase + tileRow * 32 + (winX shr 3)].toInt() and 0xFF
                    val colorIndex = tilePixel(tileIndex, pixelRow, winX and 0x07)
                    bgIndexLine[x] = colorIndex
                    workingFrame[rowBase + x] = (bgp shr (colorIndex * 2)) and 0x03
                }
                windowLine++
            }
        }
    }

    /** Indice de couleur 0–3 d'un pixel de tuile fond/fenêtre. */
    private fun tilePixel(tileIndex: Int, pixelRow: Int, pixelCol: Int): Int {
        val tileAddress = if ((lcdc and 0x10) != 0) {
            tileIndex * 16
        } else {
            0x1000 + tileIndex.toByte().toInt() * 16
        }
        val lo = vram[tileAddress + pixelRow * 2].toInt()
        val hi = vram[tileAddress + pixelRow * 2 + 1].toInt()
        val bit = 7 - pixelCol
        return (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)
    }

    private fun renderSprites(rowBase: Int) {
        if ((lcdc and 0x02) == 0) return
        val height = if ((lcdc and 0x04) != 0) 16 else 8

        // Balayage OAM : au plus 10 sprites visibles, dans l'ordre des index.
        var count = 0
        var i = 0
        while (i < 40 && count < 10) {
            val spriteY = (oam[i * 4].toInt() and 0xFF) - 16
            if (ly >= spriteY && ly < spriteY + height) {
                spriteIndices[count++] = i
            }
            i++
        }

        // Tri par priorité croissante (dessin du moins prioritaire d'abord) :
        // X décroissant puis index décroissant. Tri par insertion sans
        // allocation.
        for (a in 1 until count) {
            val key = spriteIndices[a]
            val keyX = oam[key * 4 + 1].toInt() and 0xFF
            var b = a - 1
            while (b >= 0) {
                val other = spriteIndices[b]
                val otherX = oam[other * 4 + 1].toInt() and 0xFF
                if (otherX > keyX || (otherX == keyX && other > key)) break
                spriteIndices[b + 1] = spriteIndices[b]
                b--
            }
            spriteIndices[b + 1] = key
        }

        for (s in 0 until count) {
            val index = spriteIndices[s] * 4
            val spriteY = (oam[index].toInt() and 0xFF) - 16
            val spriteX = (oam[index + 1].toInt() and 0xFF) - 8
            var tileIndex = oam[index + 2].toInt() and 0xFF
            val attributes = oam[index + 3].toInt() and 0xFF
            if (height == 16) tileIndex = tileIndex and 0xFE

            var row = ly - spriteY
            if ((attributes and 0x40) != 0) row = height - 1 - row // flip Y
            val tileAddress = tileIndex * 16 + row * 2
            val lo = vram[tileAddress].toInt()
            val hi = vram[tileAddress + 1].toInt()
            val palette = if ((attributes and 0x10) != 0) obp1 else obp0
            val behindBg = (attributes and 0x80) != 0

            for (px in 0 until 8) {
                val x = spriteX + px
                if (x < 0 || x >= SCREEN_WIDTH) continue
                val bit = if ((attributes and 0x20) != 0) px else 7 - px // flip X
                val colorIndex = (((hi shr bit) and 1) shl 1) or ((lo shr bit) and 1)
                if (colorIndex == 0) continue // transparent
                if (behindBg && bgIndexLine[x] != 0) continue
                workingFrame[rowBase + x] = (palette shr (colorIndex * 2)) and 0x03
            }
        }
    }

    // ---- Sérialisation ----

    fun stateFields(): IntArray = intArrayOf(
        lcdc, statEnableBits, scy, scx, ly, lyc, bgp, obp0, obp1, wy, wx,
        mode, lineDot, windowLine, if (statLine) 1 else 0,
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
    }
}
