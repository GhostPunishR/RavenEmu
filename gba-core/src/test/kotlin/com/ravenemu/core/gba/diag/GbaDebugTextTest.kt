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
    fun `le texte d'un moteur reel est complet et tient en six lignes`() {
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
