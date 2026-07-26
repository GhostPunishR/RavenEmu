package com.ravenemu.core.gba

import kotlin.test.Test
import kotlin.test.assertEquals
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

    /**
     * Le raccourci mémoire directe doit rendre **exactement** ce que rendrait la
     * décomposition en accès d'octets.
     *
     * Ce test remplace une comparaison chronométrée des deux chemins. Le
     * raccourci est bien quatre à cinq fois plus rapide — le banc d'essai le
     * publie — mais l'écart se joue en nanosecondes : une recompilation du
     * compilateur juste-à-temps suffit à inverser la mesure, et un test qui
     * échoue au hasard ne protège rien. Le risque réel n'est pas qu'un raccourci
     * devienne lent, c'est qu'il rende une valeur fausse ou saute une bordure de
     * région : c'est donc cela qui est vérifié, et de façon déterministe.
     */
    @Test
    fun `le chemin rapide du bus rend le meme resultat que le chemin generique`() {
        val bus = machine().bus
        // Une adresse par région servie par le raccourci, plus les bordures.
        val addresses = intArrayOf(
            0x0200_0000, 0x0203_FFF0,            // EWRAM : début et fin
            0x0300_0100, 0x0300_7FF0,            // IWRAM
            0x0500_0000, 0x0500_03F0,            // palette
            0x0600_0000, 0x0601_7FF0,            // VRAM
            0x0600_FFFC, 0x0601_7FFC,            // VRAM : bords du repliement
            0x0700_0000, 0x0700_03F0,            // OAM
            0x0800_0000, 0x0800_0100,            // ROM cartouche
        )
        for (address in addresses) {
            // Motif reconnaissable, écrit octet par octet pour ne dépendre
            // d'aucun raccourci d'écriture.
            for (i in 0 until 4) bus.write8(address + i, 0xA0 + i)

            val byByte = (0 until 4).fold(0) { acc, i ->
                acc or (bus.read8(address + i) shl (i * 8))
            }
            val expected16 = bus.read8(address) or (bus.read8(address + 1) shl 8)
            val hex = "0x${address.toUInt().toString(16)}"
            assertEquals(byByte, bus.read32(address), "lecture 32 bits en $hex")
            assertEquals(expected16, bus.read16(address) and 0xFFFF, "lecture 16 bits en $hex")
        }
    }

    @Test
    fun `le chemin rapide d'ecriture est coherent avec les lectures d'octet`() {
        val bus = machine().bus
        // La ROM est en lecture seule et l'OAM refuse les écritures d'octet :
        // seules les régions réellement inscriptibles au mot sont comparées.
        for (address in intArrayOf(0x0200_0100, 0x0300_0200, 0x0500_0100, 0x0600_0200)) {
            bus.write32(address, 0x1234_5678)
            val hex = "0x${address.toUInt().toString(16)}"
            assertEquals(0x78, bus.read8(address), "octet 0 en $hex")
            assertEquals(0x56, bus.read8(address + 1), "octet 1 en $hex")
            assertEquals(0x34, bus.read8(address + 2), "octet 2 en $hex")
            assertEquals(0x12, bus.read8(address + 3), "octet 3 en $hex")
            assertEquals(0x1234_5678, bus.read32(address), "aller-retour en $hex")
        }
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
