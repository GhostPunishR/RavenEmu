package com.ravenemu.core.gba

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Garde-fous de performance **relatifs**, conçus pour survivre à une machine
 * d'intégration continue partagée.
 *
 * Aucun seuil absolu de type « 60 images par seconde » : ces tests comparent des
 * grandeurs mesurées dans le même environnement, au même moment, ou vérifient une
 * propriété structurelle (l'absence d'allocation). Les rares bornes absolues sont
 * volontairement énormes et ne servent qu'à détecter un effondrement — une
 * régression d'un facteur dix, pas de dix pour cent.
 *
 * Le banc d'essai chiffré est dans [PerfBenchmark].
 */
class PerfRegressionTest {

    private val busyLoop = intArrayOf(
        0xE3A01402.toInt(), // MOV R1, #0x02000000
        0xE5910000.toInt(), // LDR R0, [R1]
        0xE2800001.toInt(), // ADD R0, R0, #1
        0xE5810000.toInt(), // STR R0, [R1]
        0xEAFFFFFB.toInt(), // B (boucle)
    )

    private fun machine() = GbaMachine(SyntheticRom.build(programWords = busyLoop))

    private fun nanoseconds(warmup: Int, rounds: Int, body: () -> Unit): Double {
        repeat(warmup) { body() }
        val start = System.nanoTime()
        repeat(rounds) { body() }
        return (System.nanoTime() - start).toDouble() / rounds
    }

    /** Prend la meilleure de plusieurs mesures : le bruit ne fait qu'ajouter du temps. */
    private fun best(attempts: Int = 5, warmup: Int, rounds: Int, body: () -> Unit): Double =
        (0 until attempts).minOf { nanoseconds(warmup, rounds, body) }

    @Test
    fun `le chemin rapide du bus bat le chemin generique`() {
        // Un accès 32 bits aligné en IWRAM passe par le raccourci direct ; le
        // même accès en zone d'E/S traverse l'aiguillage complet. Le premier doit
        // rester nettement plus rapide, sinon le raccourci a été neutralisé.
        val m = machine()
        val bus = m.bus
        val fast = best(warmup = 2000, rounds = 20_000) { bus.read32(0x0300_0100) }
        val generic = best(warmup = 2000, rounds = 20_000) { bus.read32(0x0400_0100) }
        assertTrue(
            fast < generic,
            "le raccourci mémoire directe doit rester plus rapide " +
                "(%.1f ns contre %.1f ns)".format(fast, generic),
        )
    }

    @Test
    fun `emitSample n'alloue rien`() {
        val m = machine()
        val bus = m.bus
        bus.write16(0x0400_0084, 0x0080) // audio activé
        bus.write16(0x0400_0082, 0x3F02)
        bus.write16(0x0400_0080, 0x77FF)
        bus.write16(0x0400_0062, 0xF080)
        bus.write16(0x0400_0064, 0x8400)
        val drain = ShortArray(4096)

        val allocated = AllocationProbe.measure(warmups = 10) {
            m.apu.tick(GbaCore.CYCLES_PER_FRAME)
            m.apu.readSamples(drain)
        }
        if (!AllocationProbe.supported) return
        // Le mixage tourne 32 768 fois par seconde : un seul objet par échantillon
        // suffirait à saturer le ramasse-miettes.
        assertTrue(allocated < 4096, "le mixage a alloué $allocated octets par trame")
    }

    @Test
    fun `le rendu d'une ligne n'alloue rien`() {
        val core = GbaCore()
        core.loadRom(SyntheticRom.build(programWords = busyLoop))
        val bus = core.machine!!.bus
        bus.write16(0x0400_0000, 0x1F00)
        for (bg in 0 until 4) bus.write16(0x0400_0008 + bg * 2, 0x0084 or (bg shl 8))
        for (i in 0 until 0x8000) bus.vram[i] = ((i and 0xFF) or 1).toByte()

        val ppu = core.machine!!.ppu
        val allocated =
            AllocationProbe.measure(warmups = 10) { ppu.tick(GbaCore.CYCLES_PER_FRAME) }
        if (!AllocationProbe.supported) return
        assertTrue(allocated < 8192, "le rendu a alloué $allocated octets par trame")
    }

    @Test
    fun `une trame ne depasse pas un plafond grossier`() {
        // Borne délibérément énorme : une trame émulée doit rester bien en deçà de
        // la seconde. Elle ne détecte qu'un effondrement — boucle infinie, coût
        // par instruction devenu absurde — jamais une variation de quelques
        // pourcents, et n'exprime aucune promesse de cadence.
        val core = GbaCore()
        core.loadRom(SyntheticRom.build(programWords = busyLoop))
        val fb = IntArray(core.video.pixelCount)
        repeat(5) { core.runFrame(fb) }
        val start = System.nanoTime()
        repeat(10) { core.runFrame(fb) }
        val msPerFrame = (System.nanoTime() - start) / 1_000_000.0 / 10
        assertTrue(msPerFrame < 500.0, "effondrement de performance : $msPerFrame ms/trame")
    }

    @Test
    fun `le compteur d'instructions par trame reste plausible`() {
        // Détecte une boucle d'exécution cassée : trop peu d'instructions signale
        // un blocage, une explosion signale un coût par instruction devenu nul.
        val core = GbaCore()
        core.loadRom(SyntheticRom.build(programWords = busyLoop))
        val fb = IntArray(core.video.pixelCount)
        repeat(3) { core.runFrame(fb) }
        val instructions = core.debugSnapshot()!!.instructionsPerFrame
        assertTrue(
            instructions in 1_000..GbaCore.CYCLES_PER_FRAME,
            "instructions par trame invraisemblable : $instructions",
        )
    }

    @Test
    fun `executer depuis la ROM coute plus cher que depuis l'IWRAM`() {
        // Propriété structurelle du modèle temporel : la même boucle doit
        // consommer plus de cycles en cartouche qu'en mémoire interne.
        val m = machine()
        for (i in busyLoop.indices) m.bus.write32(0x0300_0200 + i * 4, busyLoop[i])

        fun cyclesFor(entry: Int): Int {
            m.cpu.reset(entry)
            m.bus.breakAccessSequence()
            var total = 0
            repeat(200) { total += m.cpu.step() }
            return total
        }

        val fromIwram = cyclesFor(0x0300_0200)
        val fromRom = cyclesFor(0x0800_0000)
        assertTrue(
            fromRom > fromIwram,
            "la cartouche doit être plus lente ($fromRom contre $fromIwram cycles)",
        )
    }
}
