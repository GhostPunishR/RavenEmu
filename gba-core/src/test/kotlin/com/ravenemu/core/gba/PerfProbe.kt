package com.ravenemu.core.gba

import kotlin.test.Test

/** Sonde de performance : sépare le coût du CPU de celui du rendu. */
class PerfProbe {

    private val busyLoop = intArrayOf(
        0xE3A01402.toInt(), // MOV R1, #0x02000000
        0xE5910000.toInt(), // LDR R0, [R1]
        0xE2800001.toInt(), // ADD R0, R0, #1
        0xE5810000.toInt(), // STR R0, [R1]
        0xEAFFFFFB.toInt(), // B (boucle)
    )

    private fun measure(label: String, prepare: (GbaCore) -> Unit) {
        val core = GbaCore()
        core.loadRom(SyntheticRom.build(programWords = busyLoop))
        prepare(core)
        val fb = IntArray(core.video.pixelCount)
        repeat(5) { core.runFrame(fb) } // chauffe du JIT

        val frames = 60
        val start = System.nanoTime()
        repeat(frames) { core.runFrame(fb) }
        val ms = (System.nanoTime() - start) / 1_000_000.0 / frames
        println("PERF[%s]: %.2f ms/trame → %.0f fps (JVM de bureau)".format(label, ms, 1000.0 / ms))
    }

    @Test
    fun `cout du moteur selon la charge graphique`() {
        measure("écran noir") { }

        measure("mode 0, 4 BG + sprites") { core ->
            val bus = core.machine!!.bus
            // DISPCNT : mode 0, BG0-3 et OBJ actifs.
            bus.write16(0x0400_0000, 0x1F00)
            for (bg in 0 until 4) {
                // charBase 1, screenBase distinct par BG, 8 bpp.
                bus.write16(0x0400_0008 + bg * 2, 0x0084 or (bg shl 8))
            }
            // Tuiles et cartes non nulles : force le chemin de rendu complet.
            for (i in 0 until 0x8000) bus.vram[i] = ((i and 0xFF) or 1).toByte()
            for (i in 0 until 0x400) bus.paletteRam[i] = (i and 0xFF).toByte()
            // 128 sprites visibles.
            for (s in 0 until 128) {
                bus.oam[s * 8] = (s and 0x7F).toByte()
                bus.oam[s * 8 + 1] = 0x20 // 8 bpp
                bus.oam[s * 8 + 2] = ((s * 2) and 0xFF).toByte()
            }
        }
    }
}
