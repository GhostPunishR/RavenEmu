package com.ravenemu.core.gba.diag

import com.ravenemu.core.gba.KotlinGbaCore
import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.RealisticRom
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Diagnostics du moteur : compteurs de la surcouche de débogage et signalement
 * bridé des anomalies.
 */
class GbaDiagnosticsTest {

    private fun machine() = GbaMachine(SyntheticRom.build())

    // ---- Journalisation bridée ----

    @Test
    fun `un evenement repete n'est journalise qu'un nombre limite de fois`() {
        val diagnostics = GbaDiagnostics()
        val messages = mutableListOf<String>()
        diagnostics.onEvent = { _, message -> messages += message }

        repeat(1000) { diagnostics.report(GbaDiagnostics.Event.UNDEFINED_INSTRUCTION) { "boum" } }

        assertEquals(
            GbaDiagnostics.MAX_REPORTS_PER_EVENT,
            messages.size,
            "le journal doit être plafonné",
        )
        assertEquals(
            1000,
            diagnostics.count(GbaDiagnostics.Event.UNDEFINED_INSTRUCTION),
            "le compteur, lui, doit tout voir",
        )
    }

    @Test
    fun `le detail n'est pas construit sans auditeur`() {
        val diagnostics = GbaDiagnostics()
        var built = 0
        repeat(10) {
            diagnostics.report(GbaDiagnostics.Event.UNSUPPORTED_SWI) {
                built++
                "message coûteux"
            }
        }
        assertEquals(0, built, "aucune chaîne ne doit être construite sans auditeur")
        assertEquals(10, diagnostics.count(GbaDiagnostics.Event.UNSUPPORTED_SWI))
    }

    @Test
    fun `le detail n'est plus construit une fois le quota epuise`() {
        val diagnostics = GbaDiagnostics()
        var built = 0
        diagnostics.onEvent = { _, _ -> }
        repeat(100) {
            diagnostics.report(GbaDiagnostics.Event.UNSUPPORTED_ACCESS) {
                built++
                "détail"
            }
        }
        assertEquals(GbaDiagnostics.MAX_REPORTS_PER_EVENT, built)
    }

    @Test
    fun `chaque categorie a son propre quota`() {
        val diagnostics = GbaDiagnostics()
        val seen = mutableMapOf<GbaDiagnostics.Event, Int>()
        diagnostics.onEvent = { event, _ -> seen[event] = (seen[event] ?: 0) + 1 }
        for (event in GbaDiagnostics.Event.entries) {
            repeat(50) { diagnostics.report(event) { "x" } }
        }
        for (event in GbaDiagnostics.Event.entries) {
            assertEquals(GbaDiagnostics.MAX_REPORTS_PER_EVENT, seen[event], "$event")
        }
    }

    // ---- Signalements produits par le moteur ----

    @Test
    fun `un appel logiciel non pris en charge est signale`() {
        val m = machine()
        val events = mutableListOf<GbaDiagnostics.Event>()
        m.diagnostics.onEvent = { event, _ -> events += event }

        m.bios.handleSwi(0x1D) // pilote sonore : non implémenté
        assertEquals(listOf(GbaDiagnostics.Event.UNSUPPORTED_SWI), events)
        assertEquals(0x1D, m.diagnostics.lastSwi)

        // Un appel pris en charge ne produit rien.
        m.cpu.state.regs[0] = 10
        m.cpu.state.regs[1] = 2
        m.bios.handleSwi(0x06) // Div
        assertEquals(1, events.size)
        assertEquals(0x06, m.diagnostics.lastSwi)
    }

    @Test
    fun `une instruction indefinie est signalee`() {
        val m = machine()
        val messages = mutableListOf<String>()
        m.diagnostics.onEvent = { _, message -> messages += message }

        // Motif coprocesseur : non pris en charge sur ARM7TDMI GBA.
        m.bus.write32(0x0300_0000, 0xEE000000.toInt())
        m.cpu.state.regs[15] = 0x0300_0000
        m.cpu.step()

        assertEquals(1, m.diagnostics.count(GbaDiagnostics.Event.UNDEFINED_INSTRUCTION))
        assertTrue(messages.single().contains("indéfinie"), messages.single())
    }

    @Test
    fun `un acces hors du plan memoire est signale`() {
        val m = machine()
        m.diagnostics.onEvent = { _, _ -> }
        // 0x1000_0000 n'appartient à aucune région.
        assertEquals(0, m.bus.read8(0x1000_0000))
        m.bus.write8(0x1000_0000, 0xFF)
        assertEquals(2, m.diagnostics.count(GbaDiagnostics.Event.UNSUPPORTED_ACCESS))
    }

    @Test
    fun `un flux compresse invalide est signale`() {
        val m = machine()
        m.diagnostics.onEvent = { _, _ -> }
        // En-tête LZ77 annonçant une taille nulle.
        m.bus.write32(0x0200_0000, 0x0000_0010)
        m.cpu.state.regs[0] = 0x0200_0000
        m.cpu.state.regs[1] = 0x0600_0000
        m.bios.handleSwi(0x12)
        assertEquals(1, m.diagnostics.count(GbaDiagnostics.Event.DECOMPRESSION_ERROR))
    }

    @Test
    fun `une attente d'interruption sans fin est signalee`() {
        val m = machine()
        val messages = mutableListOf<String>()
        m.diagnostics.onEvent = { _, message -> messages += message }

        // VBlankIntrWait sans que DISPSTAT autorise l'interruption : elle ne
        // viendra jamais.
        m.bios.handleSwi(0x05)
        assertTrue(m.cpu.state.halted)
        repeat(3) { m.runFrame(KotlinGbaCore.CYCLES_PER_FRAME) }

        assertEquals(
            1,
            m.diagnostics.count(GbaDiagnostics.Event.MISSING_INTERRUPT),
            "l'attente bloquée doit être signalée une seule fois",
        )
        assertTrue(messages.single().contains("attente"), messages.single())
    }

    @Test
    fun `une attente qui aboutit n'est pas signalee`() {
        val m = machine()
        m.diagnostics.onEvent = { _, _ -> }
        SyntheticRom.installMinimalIrqHandler(m.bus)
        m.bus.write16(0x0400_0004, 0x0008) // DISPSTAT : IRQ VBlank
        m.bus.write16(0x0400_0200, 0x0001) // IE
        m.bios.handleSwi(0x05)
        repeat(3) { m.runFrame(KotlinGbaCore.CYCLES_PER_FRAME) }
        assertEquals(0, m.diagnostics.count(GbaDiagnostics.Event.MISSING_INTERRUPT))
    }

    // ---- Mesures ----

    @Test
    fun `les instructions par trame sont comptees`() {
        val core = KotlinGbaCore()
        core.loadRom(RealisticRom.bootSequence())
        val framebuffer = IntArray(core.video.pixelCount)
        repeat(3) { core.runFrame(framebuffer) }

        val snapshot = assertNotNull(core.debugSnapshot())
        assertTrue(
            snapshot.instructionsPerFrame > 0,
            "instructions par trame : ${snapshot.instructionsPerFrame}",
        )
    }

    @Test
    fun `la photographie de debogage reflete l'etat du moteur`() {
        val core = KotlinGbaCore()
        core.loadRom(RealisticRom.bootSequence())
        val framebuffer = IntArray(core.video.pixelCount)
        repeat(8) { core.runFrame(framebuffer) }

        val snapshot = assertNotNull(core.debugSnapshot())
        val machine = core.machine!!
        assertEquals(machine.cpu.state.regs[15], snapshot.programCounter)
        assertEquals(machine.cpu.state.thumb, snapshot.thumb)
        assertEquals(machine.ppu.vcount, snapshot.vcount)
        assertEquals(machine.apu.fifoSize(0), snapshot.fifoASize)
        assertEquals(machine.apu.fifoSize(1), snapshot.fifoBSize)
        assertEquals(machine.dma.lastChannel, snapshot.lastDmaChannel)
        // La ROM réaliste finit en Thumb, a lancé un DMA et attendu des VBlank.
        assertTrue(snapshot.thumb)
        assertEquals(3, snapshot.lastDmaChannel, "le canal 3 a servi la copie VRAM")
        assertEquals(0x05, snapshot.lastSwi, "VBlankIntrWait est le dernier appel")
        assertEquals(1, snapshot.lastInterruptMask, "la dernière source est le VBlank")
        assertFalse(snapshot.hasAnomalies, "cette ROM ne doit produire aucune anomalie")
    }

    @Test
    fun `les sous-alimentations audio sont comptees`() {
        val core = KotlinGbaCore()
        core.loadRom(SyntheticRom.build())
        val buffer = ShortArray(64)
        // Aucun cycle écoulé : rien n'est prêt.
        assertEquals(0, core.readAudio(buffer))
        assertEquals(1, assertNotNull(core.debugSnapshot()).audioUnderruns)

        // Une trame plus tard, les échantillons sont là.
        core.runFrame(IntArray(core.video.pixelCount))
        assertTrue(core.readAudio(buffer) > 0)
        assertEquals(1, assertNotNull(core.debugSnapshot()).audioUnderruns)
    }

    @Test
    fun `sans auditeur le moteur ne journalise rien`() {
        val core = KotlinGbaCore()
        core.loadRom(SyntheticRom.build())
        assertEquals(null, core.onDiagnosticEvent)
        // Provoque une anomalie : le compteur monte, rien n'est émis.
        core.machine!!.bus.read8(0x1000_0000)
        val snapshot = assertNotNull(core.debugSnapshot())
        assertEquals(1, snapshot.unsupportedAccessCount)
        assertTrue(snapshot.hasAnomalies)
    }
}
