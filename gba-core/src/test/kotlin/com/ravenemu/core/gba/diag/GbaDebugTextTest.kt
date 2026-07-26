package com.ravenemu.core.gba.diag

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.RealisticRom
import kotlin.test.Test
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
        underruns: Int = 0,
        unsupportedSwi: Int = 0,
        undefinedInstruction: Int = 0,
        unsupportedAccess: Int = 0,
        missingInterrupt: Int = 0,
        decompressionError: Int = 0,
        firstUnsupportedAddress: Int = 0,
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
        audioUnderruns = underruns,
        unsupportedSwiCount = unsupportedSwi,
        undefinedInstructionCount = undefinedInstruction,
        unsupportedAccessCount = unsupportedAccess,
        missingInterruptCount = missingInterrupt,
        decompressionErrorCount = decompressionError,
        firstUnsupportedAddress = firstUnsupportedAddress,
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
            underruns = 2,
        ).toDebugText(fps = 59.7, frameTimeMs = 8.25)

        for (expected in listOf(
            "59.7 FPS", "8.25 ms", "12345 instr", "PC 08001234", "THUMB",
            "SWI 05", "IRQ 0001", "VCOUNT  42", "canal 3", "FIFO A 16", "B  8",
            "sous-alim. 2",
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
    fun `le texte d'un moteur reel est complet et tient en six lignes`() {
        // Hors mesure : pas de ligne de répartition du temps.
        val core = GbaCore()
        core.loadRom(RealisticRom.bootSequence())
        val framebuffer = IntArray(core.video.pixelCount)
        repeat(8) { core.runFrame(framebuffer) }

        val text = assertNotNull(core.debugSnapshot()).toDebugText(59.7, 4.2)
        val lines = text.lines()
        assertTrue(lines.size == 6, "six lignes attendues sans anomalie, obtenu :\n$text")
        assertTrue(lines.none { it.isBlank() }, text)
    }
}
