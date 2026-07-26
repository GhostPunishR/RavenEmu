package com.ravenemu.core.gba

import kotlin.test.Test

/**
 * Banc d'essai **informatif** : il mesure et publie, il ne juge pas.
 *
 * Aucune de ces mesures ne fait échouer la construction. Une machine
 * d'intégration continue partagée est trop irrégulière pour qu'un seuil absolu
 * ait un sens, et un chiffre obtenu sur une JVM de bureau ne dit **rien** de la
 * cadence atteinte sur un téléphone : le processeur, la mémoire, le compilateur
 * et la gestion thermique n'ont rien de comparable. Ces mesures servent à
 * comparer deux versions du moteur sur une même machine, et à situer les coûts
 * les uns par rapport aux autres.
 *
 * Les seuils, eux, sont dans [PerfRegressionTest].
 */
class PerfBenchmark {

    /** Boucle serrée : lecture, incrément, écriture, branchement. */
    private val busyLoop = intArrayOf(
        0xE3A01402.toInt(), // MOV R1, #0x02000000
        0xE5910000.toInt(), // LDR R0, [R1]
        0xE2800001.toInt(), // ADD R0, R0, #1
        0xE5810000.toInt(), // STR R0, [R1]
        0xEAFFFFFB.toInt(), // B (boucle)
    )

    private fun core(prepare: (GbaCore) -> Unit = {}): GbaCore {
        val core = GbaCore()
        core.loadRom(SyntheticRom.build(programWords = busyLoop))
        prepare(core)
        return core
    }

    /** Charge graphique maximale : quatre arrière-plans, 128 sprites, fenêtres. */
    private fun heavyScene(core: GbaCore) {
        val bus = core.machine!!.bus
        bus.write16(0x0400_0000, 0x1F00) // mode 0, BG0-3 et OBJ
        for (bg in 0 until 4) {
            bus.write16(0x0400_0008 + bg * 2, 0x0084 or (bg shl 8))
        }
        for (i in 0 until 0x8000) bus.vram[i] = ((i and 0xFF) or 1).toByte()
        for (i in 0 until 0x400) bus.paletteRam[i] = (i and 0xFF).toByte()
        for (s in 0 until 128) {
            bus.oam[s * 8] = (s and 0x7F).toByte()
            bus.oam[s * 8 + 1] = 0x20 // 8 bpp
            bus.oam[s * 8 + 2] = ((s * 2) and 0xFF).toByte()
        }
    }

    /** Active l'audio complet : quatre canaux PSG et les deux Direct Sound. */
    private fun loudScene(core: GbaCore) {
        val bus = core.machine!!.bus
        bus.write16(0x0400_0084, 0x0080)
        bus.write16(0x0400_0082, 0x3F02)
        bus.write16(0x0400_0080, 0x77FF)
        bus.write16(0x0400_0062, 0xF080)
        bus.write16(0x0400_0064, 0x8400)
        bus.write16(0x0400_0078, 0xF000)
        bus.write16(0x0400_007C, 0x8010)
    }

    private fun milliseconds(warmup: Int, frames: Int, body: () -> Unit): Double {
        repeat(warmup) { body() }
        val start = System.nanoTime()
        repeat(frames) { body() }
        return (System.nanoTime() - start) / 1_000_000.0 / frames
    }

    @Test
    fun `banc d'essai du moteur complet`() {
        println("=== Banc d'essai RavenEmu GBA (JVM de bureau, valeurs indicatives) ===")

        // --- Trame complète, selon la charge ---
        for ((label, prepare) in listOf<Pair<String, (GbaCore) -> Unit>>(
            "écran noir" to {},
            "4 arrière-plans + 128 sprites" to ::heavyScene,
            "graphismes + audio complet" to { c -> heavyScene(c); loudScene(c) },
        )) {
            val core = core(prepare)
            val fb = IntArray(core.video.pixelCount)
            val audio = ShortArray(4096)
            val ms = milliseconds(warmup = 10, frames = 60) {
                core.runFrame(fb)
                core.readAudio(audio)
            }
            val snapshot = core.debugSnapshot()!!
            // Le débit d'instructions est publié en plus du temps par trame : il
            // ne dépend pas du modèle de cycles, alors que le nombre
            // d'instructions exécutées par trame, lui, en dépend. Comparer deux
            // versions du moteur sur le seul temps par trame serait trompeur.
            println(
                "trame[%-30s] %6.3f ms  %7d instructions  %5.1f Mi/s"
                    .format(
                        label,
                        ms,
                        snapshot.instructionsPerFrame,
                        snapshot.instructionsPerFrame / ms / 1000.0,
                    ),
            )
        }

        // --- Coût de chaque sous-système, isolément ---
        val cycles = GbaCore.CYCLES_PER_FRAME

        val cpuCore = core()
        val cpuMachine = cpuCore.machine!!
        var instructions = 0
        val cpuMs = milliseconds(warmup = 10, frames = 60) {
            var elapsed = 0
            var count = 0
            while (elapsed < cycles) {
                elapsed += cpuMachine.cpu.step()
                count++
            }
            instructions = count
        }
        println("cpu   %8.3f ms par trame  (%d instructions)".format(cpuMs, instructions))

        val ppuCore = core(::heavyScene)
        val ppu = ppuCore.machine!!.ppu
        println("ppu   %8.3f ms par trame".format(milliseconds(10, 60) { ppu.tick(cycles) }))

        val apuCore = core(::loudScene)
        val apu = apuCore.machine!!.apu
        val drain = ShortArray(4096)
        println(
            "apu   %8.3f ms par trame".format(
                milliseconds(10, 60) {
                    apu.tick(cycles)
                    apu.readSamples(drain)
                },
            ),
        )

        val dmaCore = core()
        val dmaMachine = dmaCore.machine!!
        val dmaMs = milliseconds(10, 60) {
            // Une trame entière recopiée en VRAM : le pire cas courant.
            dmaMachine.bus.write32(0x0400_00D4, 0x0200_0000)
            dmaMachine.bus.write32(0x0400_00D8, 0x0600_0000)
            dmaMachine.bus.write16(0x0400_00DC, 0x4B00)
            dmaMachine.bus.write16(0x0400_00DE, 0x8400)
            dmaMachine.dma.takePendingCycles()
        }
        println("dma   %8.3f ms pour une trame recopiée (240x160 en 16 bits)".format(dmaMs))

        // --- Allocations ---
        val allocCore = core { c -> heavyScene(c); loudScene(c) }
        val allocFb = IntArray(allocCore.video.pixelCount)
        val allocAudio = ShortArray(4096)
        val allocated = AllocationProbe.measure(warmups = 20) {
            allocCore.runFrame(allocFb)
            allocCore.readAudio(allocAudio)
        }
        println(
            if (AllocationProbe.supported) {
                "alloc %8d octets par trame".format(allocated)
            } else {
                "alloc      mesure indisponible sur cette JVM"
            },
        )
        println("=== Ces chiffres ne préjugent en rien de la cadence sur Android ===")
    }
}
