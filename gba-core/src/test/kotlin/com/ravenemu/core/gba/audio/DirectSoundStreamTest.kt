package com.ravenemu.core.gba.audio

import com.ravenemu.core.gba.GbaCore
import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.diag.GbaDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct Sound alimenté par DMA, comme le fait un vrai jeu : un timer cadence la
 * consommation des échantillons, et le canal DMA en mode spécial réapprovisionne
 * la FIFO quand elle descend sous le quart de sa capacité.
 *
 * Ce que ces tests protègent : le **rythme** de consommation. Si la FIFO est
 * vidée ou réapprovisionnée plus vite que le timer ne le demande, le pointeur de
 * source dépasse le tampon d'échantillons du jeu — jusqu'à sortir du plan
 * mémoire — et le son se dégrade.
 */
class DirectSoundStreamTest {

    /** Base et taille du tampon d'échantillons placé en fin d'EWRAM. */
    private val sampleBase = 0x0203_E000
    private val sampleBytes = 8192

    private fun machine(): GbaMachine {
        val m = GbaMachine(SyntheticRom.build())
        val bus = m.bus
        for (i in 0 until sampleBytes step 4) bus.write32(sampleBase + i, 0x40302010)

        bus.write16(0x0400_0084, 0x0080)          // audio activé
        bus.write16(0x0400_0082, 0x0B04)          // Direct Sound A, cadencé timer 0
        bus.write16(0x0400_0080, 0x77FF)
        // Canal DMA 1 : source EWRAM, destination FIFO A, mode spécial, 32 bits.
        bus.write32(0x0400_00BC, sampleBase)
        bus.write32(0x0400_00C0, 0x0400_00A0)
        bus.write16(0x0400_00C4, 4)
        bus.write16(0x0400_00C6, 0x8000 or 0x3000 or 0x0400)
        // Timer 0 : prédiviseur 1, rechargement 0xFF00 → un débordement
        // tous les 256 cycles, soit la cadence d'un flux Direct Sound.
        bus.write16(0x0400_0100, 0xFF00)
        bus.write16(0x0400_0102, 0x0080)
        return m
    }

    @Test
    fun `le flux reste dans le tampon programme`() {
        val m = machine()
        repeat(4) { m.runFrame(GbaCore.CYCLES_PER_FRAME) }

        val source = m.dma.exportState()[1]
        assertTrue(
            source in sampleBase until (sampleBase + sampleBytes),
            "source DMA hors du tampon : 0x${source.toUInt().toString(16)}",
        )
        assertEquals(
            0,
            m.diagnostics.count(GbaDiagnostics.Event.UNSUPPORTED_ACCESS),
            "le flux Direct Sound a quitté le plan mémoire",
        )
    }

    @Test
    fun `la FIFO est consommee au rythme du timer`() {
        val m = machine()
        // Un débordement tous les 256 cycles donne 1097 débordements complets
        // par trame, avec 64 cycles conservés pour la trame suivante.
        m.runFrame(GbaCore.CYCLES_PER_FRAME)
        val consumed = 280_896 / 256

        // Le registre visible reste inchangé, mais l'adresse interne du DMA
        // avance par blocs de 16 octets au rythme des échantillons consommés.
        assertEquals(sampleBase, m.bus.read32(0x0400_00BC))
        val advanced = m.dma.exportState()[1] - sampleBase
        assertEquals(0, advanced % 16, "progression DMA non alignée : $advanced")
        assertTrue(
            advanced in consumed..(consumed + 32),
            "progression DMA $advanced, attendu autour de $consumed octets",
        )

        // La FIFO reste dans sa plage de service : ni vide, ni saturée.
        val size = m.apu.fifoSize(0)
        assertTrue(size in 1..32, "taille de FIFO hors plage : $size")
    }

    @Test
    fun `le nombre d'echantillons consommes suit la cadence du timer`() {
        val m = machine()
        var pops = 0
        // Compte les débordements tout en conservant la chaîne réelle
        // timer, APU, FIFO et DMA.
        m.timers.onOverflow = { timer ->
            if (timer == 0) pops++
            m.apu.onTimerOverflow(timer)
        }
        // L'appel direct donne un budget exact, sans le léger dépassement
        // possible de la dernière instruction CPU d'une trame complète.
        m.timers.tick(GbaCore.CYCLES_PER_FRAME)

        val expected = GbaCore.CYCLES_PER_FRAME / 256
        assertEquals(expected, pops)
        assertEquals(
            0xFF00 + (GbaCore.CYCLES_PER_FRAME % 256),
            m.timers.counter(0),
            "phase du timer après une trame",
        )
    }
}
