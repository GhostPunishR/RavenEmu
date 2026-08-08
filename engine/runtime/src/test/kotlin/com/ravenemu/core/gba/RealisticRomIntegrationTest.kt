package com.ravenemu.core.gba

import com.ravenemu.core.gba.ppu.GbaPpu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Test d'intégration : une ROM synthétique réaliste est exécutée dans le cœur
 * complet, et l'on vérifie que chaque mécanisme du moteur a bien fonctionné —
 * démarrage, attentes de VBlank, décompression, motif affiché, interruption
 * traitée et son produit.
 *
 * C'est le garde-fou qui manquait : les tests unitaires validaient chaque pièce
 * séparément, sans qu'aucun programme n'exerce la chaîne entière.
 */
class RealisticRomIntegrationTest {

    private fun witness(core: KotlinGbaCore, offset: Int): Int =
        core.machine!!.bus.read32(RealisticRom.Witness.BASE + offset)

    @Test
    fun `la sequence de demarrage complete aboutit`() {
        val core = KotlinGbaCore()
        core.loadRom(RealisticRom.bootSequence())
        val framebuffer = IntArray(core.video.pixelCount)
        val audio = ShortArray(4096)
        var audioSamples = 0

        // 1. La ROM démarre, 2. et l'on laisse passer plusieurs trames pour que
        // les attentes de VBlank se terminent.
        repeat(8) {
            core.runFrame(framebuffer)
            audioSamples += core.readAudio(audio)
        }

        val machine = core.machine!!

        // 2. Les attentes de VBlank ont abouti, l'une après l'autre.
        assertEquals(
            RealisticRom.VBLANK_WAITS,
            witness(core, RealisticRom.Witness.VBLANK_COUNT),
            "les trois attentes de VBlank doivent s'être terminées",
        )

        // 3. Les données LZ77 ont été décompressées en VRAM, référence arrière
        //    comprise.
        for ((index, expected) in RealisticRom.EXPECTED_LZ77.withIndex()) {
            assertEquals(
                expected,
                machine.bus.read8(RealisticRom.Video.LZ77_DEST + index),
                "octet décompressé n°$index",
            )
        }

        // 4. Le motif copié par DMA est présent, et visible à l'écran.
        for ((index, expected) in RealisticRom.EXPECTED_PATTERN.withIndex()) {
            assertEquals(
                expected,
                machine.bus.read32(RealisticRom.Video.DMA_DEST + index * 4),
                "mot n°$index du motif copié par DMA",
            )
        }
        // En mode 3, le premier demi-mot de la VRAM est le pixel (0,0).
        val firstPixel = RealisticRom.EXPECTED_LZ77[0] or (RealisticRom.EXPECTED_LZ77[1] shl 8)
        assertEquals(
            GbaPpu.bgr555ToArgb(firstPixel),
            framebuffer[0],
            "le pixel affiché doit venir des données décompressées",
        )

        // 5. Le gestionnaire d'interruption du jeu a bien été appelé.
        val irqCount = witness(core, RealisticRom.Witness.IRQ_COUNT)
        assertTrue(irqCount >= RealisticRom.VBLANK_WAITS, "interruptions traitées : $irqCount")
        assertEquals(
            1,
            witness(core, RealisticRom.Witness.LAST_IF) and 0x01,
            "le dernier IF observé doit porter le drapeau VBlank",
        )

        // 6. L'audio a produit des échantillons non nuls.
        assertTrue(audioSamples > 0, "le cœur doit avoir produit des échantillons")
        assertTrue(
            audio.take(minOf(audioSamples, audio.size)).any { it.toInt() != 0 },
            "les échantillons produits ne doivent pas être tous nuls",
        )
    }

    @Test
    fun `le timer avance pendant les attentes de VBlank`() {
        val core = KotlinGbaCore()
        core.loadRom(RealisticRom.bootSequence())
        val framebuffer = IntArray(core.video.pixelCount)
        repeat(8) { core.runFrame(framebuffer) }

        // Le timer est armé avant les attentes et relevé après : il a forcément
        // tourné, sinon les périphériques n'avancent pas pendant une pause CPU.
        assertNotEquals(
            0,
            witness(core, RealisticRom.Witness.TIMER_VALUE),
            "le compteur du timer doit avoir progressé pendant l'attente",
        )
    }

    @Test
    fun `le programme finit en Thumb`() {
        val core = KotlinGbaCore()
        core.loadRom(RealisticRom.bootSequence())
        val framebuffer = IntArray(core.video.pixelCount)
        repeat(8) { core.runFrame(framebuffer) }

        assertEquals(
            RealisticRom.THUMB_MARKER,
            witness(core, RealisticRom.Witness.THUMB_DONE),
            "la partie Thumb du programme doit s'être exécutée",
        )
        assertTrue(core.machine!!.cpu.state.thumb, "le CPU doit être resté en Thumb")
    }

    @Test
    fun `une ROM entierement en Thumb s'execute`() {
        val core = KotlinGbaCore()
        core.loadRom(RealisticRom.thumbOnly())
        val framebuffer = IntArray(core.video.pixelCount)
        core.runFrame(framebuffer)

        val bus = core.machine!!.bus
        assertEquals(0x1F, bus.read16(RealisticRom.Video.LZ77_DEST) and 0xFFFF)
        assertEquals(RealisticRom.THUMB_MARKER, bus.read16(RealisticRom.Video.LZ77_DEST + 4))
        assertTrue(core.machine!!.cpu.state.thumb)
    }

    @Test
    fun `les interruptions continuent d'arriver apres le passage en Thumb`() {
        val core = KotlinGbaCore()
        core.loadRom(RealisticRom.bootSequence())
        val framebuffer = IntArray(core.video.pixelCount)
        repeat(8) { core.runFrame(framebuffer) }
        val afterBoot = witness(core, RealisticRom.Witness.IRQ_COUNT)

        repeat(4) { core.runFrame(framebuffer) }
        assertTrue(
            witness(core, RealisticRom.Witness.IRQ_COUNT) > afterBoot,
            "le VBlank doit continuer à interrompre la boucle Thumb",
        )
    }

    @Test
    fun `un etat instantane restaure poursuit la meme execution`() {
        val core = KotlinGbaCore()
        core.loadRom(RealisticRom.bootSequence())
        val framebuffer = IntArray(core.video.pixelCount)
        repeat(8) { core.runFrame(framebuffer) }

        val state = core.saveState()
        val irqBefore = witness(core, RealisticRom.Witness.IRQ_COUNT)
        repeat(4) { core.runFrame(framebuffer) }
        val irqAfter = witness(core, RealisticRom.Witness.IRQ_COUNT)

        core.loadState(state)
        assertEquals(irqBefore, witness(core, RealisticRom.Witness.IRQ_COUNT))
        repeat(4) { core.runFrame(framebuffer) }
        assertEquals(
            irqAfter,
            witness(core, RealisticRom.Witness.IRQ_COUNT),
            "la reprise doit être déterministe",
        )
    }
}
