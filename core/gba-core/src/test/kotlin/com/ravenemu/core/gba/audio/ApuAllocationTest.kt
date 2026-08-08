package com.ravenemu.core.gba.audio

import com.ravenemu.core.gba.AllocationProbe
import com.ravenemu.core.gba.KotlinGbaCore
import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * L'APU produit 32 768 échantillons stéréo par seconde : le moindre objet créé
 * par échantillon devient deux millions d'objets par minute et déclenche des
 * pauses du ramasse-miettes audibles sous forme de craquements.
 *
 * Ces tests vérifient que le mixage ne fait **aucune allocation** en régime
 * établi, et publient une mesure de débit à titre indicatif.
 */
class ApuAllocationTest {

    private fun machine() = GbaMachine(SyntheticRom.build())

    /** Active l'audio maître, tous les canaux PSG et les deux Direct Sound. */
    private fun enableEverything(m: GbaMachine) {
        val bus = m.bus
        bus.write16(0x0400_0084, 0x0080) // SOUNDCNT_X : audio activé
        bus.write16(0x0400_0082, 0x3F02) // PSG 100 %, Direct Sound A et B partout
        bus.write16(0x0400_0080, 0x77FF) // volume max, tous les canaux mixés
        // Canaux carrés 1 et 2.
        bus.write16(0x0400_0062, 0xF080.toInt())
        bus.write16(0x0400_0064, 0x8400)
        bus.write16(0x0400_0068, 0xF040.toInt())
        bus.write16(0x0400_006C, 0x8500)
        // Canal à table d'onde : DAC alimenté, volume 100 %, onde non nulle.
        bus.write16(0x0400_0070, 0x0080)
        bus.write16(0x0400_0072, 0x2000)
        for (offset in 0x90..0x9E step 2) bus.write16(0x0400_0000 + offset, 0x9F3A)
        bus.write16(0x0400_0074, 0x8600)
        // Canal de bruit.
        bus.write16(0x0400_0078, 0xF000.toInt())
        bus.write16(0x0400_007C, 0x8010)
        // Quelques échantillons dans chaque FIFO.
        repeat(8) {
            bus.write32(0x0400_00A0, 0x4030_2010)
            bus.write32(0x0400_00A4, 0x1020_3040)
        }
    }

    @Test
    fun `le mixage d'une trame n'alloue rien`() {
        val m = machine()
        enableEverything(m)
        val drain = ShortArray(4096)

        val allocated = AllocationProbe.measure {
            m.apu.tick(KotlinGbaCore.CYCLES_PER_FRAME)
            m.apu.readSamples(drain)
        }
        println("ALLOC[apu]: $allocated octets par trame")
        if (!AllocationProbe.supported) return // JVM sans mesure par fil

        // Marge volontairement étroite : le seul bruit toléré vient de la
        // comptabilité interne de la JVM, pas d'un objet par échantillon. Une
        // trame produit ~1100 échantillons stéréo ; un seul `IntArray(4)` par
        // échantillon coûterait déjà plus de 35 Kio.
        assertTrue(
            allocated < 4096,
            "le mixage d'une trame a alloué $allocated octets (attendu ~0)",
        )
    }

    @Test
    fun `le reapprovisionnement des FIFO n'alloue rien`() {
        val m = machine()
        enableEverything(m)
        // Direct Sound A et B cadencés par les timers 0 et 1.
        m.bus.write16(0x0400_0082, 0x3F02)

        val allocated = AllocationProbe.measure {
            repeat(2048) {
                m.bus.write32(0x0400_00A0, 0x4030_2010)
                m.bus.write32(0x0400_00A4, 0x1020_3040)
                m.apu.onTimerOverflow(0)
                m.apu.onTimerOverflow(1)
            }
        }
        if (!AllocationProbe.supported) return

        assertTrue(
            allocated < 4096,
            "2048 cycles de FIFO ont alloué $allocated octets (attendu ~0)",
        )
    }

    @Test
    fun `debit du mixage audio`() {
        // Mesure informative. Le seuil ne détecte qu'un effondrement : le mixage
        // d'une trame coûte moins de 0,05 ms sur un poste de développement, la
        // borne est donc à plus de deux ordres de grandeur au-dessus pour rester
        // insensible à la charge d'une machine d'intégration continue partagée.
        val m = machine()
        enableEverything(m)
        val drain = ShortArray(4096)
        val frames = 300

        repeat(60) { // échauffement
            m.apu.tick(KotlinGbaCore.CYCLES_PER_FRAME)
            m.apu.readSamples(drain)
        }
        val start = System.nanoTime()
        repeat(frames) {
            m.apu.tick(KotlinGbaCore.CYCLES_PER_FRAME)
            m.apu.readSamples(drain)
        }
        val msPerFrame = (System.nanoTime() - start) / 1_000_000.0 / frames
        println("PERF[apu]: %.3f ms de son par trame".format(msPerFrame))
        assertTrue(msPerFrame < 20.0, "mixage audio anormalement lent : $msPerFrame ms/trame")
    }
}
