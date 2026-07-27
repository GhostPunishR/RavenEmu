package com.ravenemu.core.gba.diag

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.RealisticRom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Mise en forme du texte de la surcouche de débogage. */
class GbaDebugTextTest {

    private fun snapshot(
        instructions: Int = 12_345,
        pc: Int = 0x0800_1234,
        thumb: Boolean = false,
        halted: Boolean = false,
        lastSwi: Int = -1,
        lastInterruptMask: Int = 0,
        vcount: Int = 42,
        lastDmaChannel: Int = -1,
        dmaActive: Boolean = false,
        fifoA: Int = 0,
        fifoB: Int = 0,
        fifoAEmptyReads: Int = 0,
        fifoBEmptyReads: Int = 0,
        underruns: Int = 0,
        unsupportedSwi: Int = 0,
        undefinedInstruction: Int = 0,
        unsupportedAccess: Int = 0,
        missingInterrupt: Int = 0,
        decompressionError: Int = 0,
        firstUnsupportedAddress: Int = 0,
        dispcnt: Int = 0,
        bg0Control: Int = 0,
        bg1Control: Int = 0,
        bg2Control: Int = 0,
        bg3Control: Int = 0,
        blendControl: Int = 0,
        layerPixels: IntArray = IntArray(0),
        bg2ReferenceX: Int = 0,
        bg2ReferenceY: Int = 0,
        bg2ScaleX: Int = 0x0100,
        bg2ScaleY: Int = 0x0100,
        ppuMillis: Double = 0.0,
        dmaMillis: Double = 0.0,
        apuMillis: Double = 0.0,
    ) = GbaDebugSnapshot(
        instructionsPerFrame = instructions,
        programCounter = pc,
        thumb = thumb,
        halted = halted,
        lastSwi = lastSwi,
        lastInterruptMask = lastInterruptMask,
        vcount = vcount,
        lastDmaChannel = lastDmaChannel,
        dmaActive = dmaActive,
        fifoASize = fifoA,
        fifoBSize = fifoB,
        fifoAEmptyReads = fifoAEmptyReads,
        fifoBEmptyReads = fifoBEmptyReads,
        audioUnderruns = underruns,
        unsupportedSwiCount = unsupportedSwi,
        undefinedInstructionCount = undefinedInstruction,
        unsupportedAccessCount = unsupportedAccess,
        missingInterruptCount = missingInterrupt,
        decompressionErrorCount = decompressionError,
        firstUnsupportedAddress = firstUnsupportedAddress,
        dispcnt = dispcnt,
        bg0Control = bg0Control,
        bg1Control = bg1Control,
        bg2Control = bg2Control,
        bg3Control = bg3Control,
        blendControl = blendControl,
        layerPixels = layerPixels,
        bg2ReferenceX = bg2ReferenceX,
        bg2ReferenceY = bg2ReferenceY,
        bg2ScaleX = bg2ScaleX,
        bg2ScaleY = bg2ScaleY,
        ppuMillis = ppuMillis,
        dmaMillis = dmaMillis,
        apuMillis = apuMillis,
    )

    @Test
    fun `toutes les grandeurs demandees apparaissent`() {
        val text = snapshot(
            thumb = true,
            lastSwi = 0x05,
            lastInterruptMask = 0x0001,
            lastDmaChannel = 3,
            dmaActive = true,
            fifoA = 16,
            fifoB = 8,
            fifoAEmptyReads = 3,
            fifoBEmptyReads = 4,
            underruns = 2,
        ).toDebugText(fps = 59.7, frameTimeMs = 8.25, audioTrackUnderruns = 5)

        for (expected in listOf(
            "59.7 FPS", "8.25 ms", "12345 instr", "PC 08001234", "THUMB",
            "SWI 05", "IRQ 0001", "VCOUNT  42", "canal 3", "FIFO A 16", "B  8",
            "vides A 3 B 4", "sortie moteur 2", "Android 5",
        )) {
            assertTrue(expected in text, "« $expected » absent de :\n$text")
        }
        assertTrue("actif" in text, "le DMA en cours doit être signalé")
    }

    @Test
    fun `les valeurs absentes sont affichees sobrement`() {
        val text = snapshot().toDebugText(fps = 60.0, frameTimeMs = 5.0)
        assertTrue("SWI —" in text, text)
        assertTrue("IRQ —" in text, text)
        assertTrue("DMA —" in text, text)
        assertTrue("ARM" in text)
        assertFalse("THUMB" in text)
        assertFalse("pause" in text)
        assertFalse("anomalies" in text, "sans anomalie, pas de ligne d'anomalies")
    }

    @Test
    fun `la pause du processeur est visible`() {
        val text = snapshot(halted = true).toDebugText(fps = 60.0, frameTimeMs = 5.0)
        assertTrue("en pause" in text, text)
    }

    @Test
    fun `l'adresse du premier acces fautif accompagne le decompte`() {
        val text = snapshot(unsupportedAccess = 64, firstUnsupportedAddress = 0xFFFF_FE00.toInt())
            .toDebugText(fps = 60.0, frameTimeMs = 16.0)
        assertTrue("accès 64 @FFFFFE00" in text, text)
    }

    @Test
    fun `l'etat des couches d'affichage est resume`() {
        // Mode 0, BG0 et BG2 actifs, sprites en projection 1D, fenêtre 0,
        // mélange alpha : de quoi expliquer un élément absent de l'écran.
        val text = snapshot(
            dispcnt = 0x0000 or 0x0100 or 0x0400 or 0x1000 or 0x0040 or 0x2000,
            bg0Control = 2,
            bg2Control = 0,
            blendControl = 1 shl 6,
        ).toDebugText(fps = 60.0, frameTimeMs = 16.0)
        assertTrue("m0" in text, text)
        assertTrue("BG0p2" in text, text)
        assertTrue("BG2p0" in text, text)
        assertFalse("BG1" in text, "BG1 n'est pas activé : il ne doit pas être listé")
        assertTrue("OBJ1D" in text, text)
        assertTrue("WIN0" in text, text)
        assertTrue("BLD alpha" in text, text)
    }

    @Test
    fun `un ecran blanc force est signale`() {
        val text = snapshot(dispcnt = 0x0080).toDebugText(fps = 60.0, frameTimeMs = 16.0)
        assertTrue("BLANC" in text, text)
    }

    @Test
    fun `sans sprites ni fenetre la ligne video reste sobre`() {
        val text = snapshot(dispcnt = 0x0100).toDebugText(fps = 60.0, frameTimeMs = 16.0)
        assertTrue("BG0p0" in text, text)
        assertFalse("OBJ" in text, text)
        assertFalse("WIN" in text, text)
        assertFalse("BLD" in text, text)
    }

    @Test
    fun `seules les anomalies non nulles sont listees`() {
        val text = snapshot(unsupportedSwi = 3, decompressionError = 1)
            .toDebugText(fps = 60.0, frameTimeMs = 5.0)
        assertTrue("anomalies" in text, text)
        assertTrue("SWI 3" in text, text)
        assertTrue("décompr. 1" in text, text)
        assertFalse("accès" in text, "aucun accès fautif : la mention doit être absente")
        assertFalse("IRQ absente" in text, text)
    }

    @Test
    fun `la repartition du temps apparait quand elle est mesuree`() {
        val text = snapshot(ppuMillis = 7.4, dmaMillis = 0.3, apuMillis = 1.1)
            .toDebugText(fps = 35.0, frameTimeMs = 28.5)
        // Le processeur est obtenu par différence : 28,5 - 7,4 - 0,3 - 1,1.
        assertTrue("cpu 19.7" in text, text)
        assertTrue("ppu 7.4" in text, text)
        assertTrue("dma 0.3" in text, text)
        assertTrue("apu 1.1" in text, text)
    }

    @Test
    fun `sans mesure aucune ligne de repartition n'est ajoutee`() {
        val text = snapshot().toDebugText(fps = 60.0, frameTimeMs = 5.0)
        assertFalse("cpu " in text, text)
    }

    @Test
    fun `le temps processeur ne devient jamais negatif`() {
        // Les relevés s'additionnent sur la trame écoulée, le temps de trame est
        // celui d'une moyenne : rien ne garantit leur cohérence exacte.
        val text = snapshot(ppuMillis = 40.0).toDebugText(fps = 30.0, frameTimeMs = 10.0)
        assertTrue("cpu 0.0" in text, text)
    }

    @Test
    fun `le texte d'un moteur reel contient toutes les rubriques`() {
        // Hors mesure : pas de ligne de répartition du temps, mais la ligne
        // d'état vidéo est toujours présente.
        val core = GbaCore()
        core.loadRom(RealisticRom.bootSequence())
        val framebuffer = IntArray(core.video.pixelCount)
        repeat(8) { core.runFrame(framebuffer) }

        val text = assertNotNull(core.debugSnapshot()).toDebugText(59.7, 4.2)
        val lines = text.lines()
        // Le contenu plutôt que le nombre de lignes : compter les lignes ne
        // protège rien et casse dès qu'une mesure s'ajoute.
        assertTrue(lines.any { it.startsWith("59.7 FPS") }, text)
        assertTrue(lines.any { it.startsWith("PC ") }, text)
        assertTrue(lines.any { it.startsWith("VCOUNT") }, text)
        assertTrue(lines.any { it.startsWith("FIFO") }, text)
        assertTrue(lines.any { it.startsWith("m3") }, "l'état vidéo doit figurer :\n$text")
        assertFalse(lines.any { it.contains("cpu ") }, "hors mesure : pas de répartition")
        assertTrue(lines.none { it.isBlank() }, text)
    }

    // ---- Pixels par couche et cadrage affine ----

    @Test
    fun `les pixels comptes n'apparaissent que pour les couches activees`() {
        val text = snapshot(
            dispcnt = 0x1300, // BG0, BG1 et OBJ ; BG2 et BG3 éteints
            layerPixels = intArrayOf(8000, 38400, 0, 0, 2400),
        ).toDebugText(fps = 60.0, frameTimeMs = 16.0)

        val line = text.lines().first { it.startsWith("px") }
        assertEquals("px  BG0 8000  BG1 38400  OBJ 2400", line)
    }

    /**
     * La mesure qui compte : une couche activée dont le compte reste à zéro n'a
     * rien dessiné, et c'est ce zéro qu'il faut pouvoir lire.
     */
    @Test
    fun `une couche activee qui ne dessine rien affiche zero`() {
        val text = snapshot(
            dispcnt = 0x0401, // mode 1, BG2 activé
            layerPixels = intArrayOf(0, 0, 0, 0, 0),
        ).toDebugText(fps = 60.0, frameTimeMs = 16.0)

        assertTrue(text.lines().any { it == "px  BG2 0" }, text)
    }

    @Test
    fun `sans comptage aucune ligne de pixels n'est ajoutee`() {
        val text = snapshot(dispcnt = 0x0100).toDebugText(fps = 60.0, frameTimeMs = 16.0)
        assertFalse(text.lines().any { it.startsWith("px") }, text)
    }

    @Test
    fun `le cadrage affine n'est publie qu'aux modes qui en ont un`() {
        val affine = snapshot(
            dispcnt = 0x0401, // mode 1
            bg2ReferenceX = -64,
            bg2ReferenceY = 32,
            bg2ScaleX = 0x0080,
            bg2ScaleY = 0x0100,
        ).toDebugText(fps = 60.0, frameTimeMs = 16.0)
        assertTrue(affine.lines().any { it == "aff BG2 x-64 y32  pa0080 pd0100" }, affine)

        // Mode 0 : aucun plan affine, donc rien à publier.
        val text = snapshot(dispcnt = 0x0100).toDebugText(fps = 60.0, frameTimeMs = 16.0)
        assertFalse(text.lines().any { it.startsWith("aff") }, text)
    }
}
