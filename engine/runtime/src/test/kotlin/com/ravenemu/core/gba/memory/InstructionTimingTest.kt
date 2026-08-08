package com.ravenemu.core.gba.memory

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Le coût d'une instruction doit dépendre de l'endroit où elle réside et des
 * régions qu'elle touche : c'est ce qui distingue une boucle recopiée en IWRAM
 * d'une boucle exécutée directement depuis la cartouche.
 */
class InstructionTimingTest {

    private fun machine() = GbaMachine(SyntheticRom.build())

    /** `MOV R0, #1` : instruction purement interne, un seul accès (sa lecture). */
    private val movR0 = 0xE3A00001.toInt()

    /** `LDR R1, [R2]`. */
    private val ldrR1 = 0xE5921000.toInt()

    private fun stepAt(m: GbaMachine, address: Int): Int {
        m.cpu.state.regs[15] = address
        m.cpu.state.thumb = false
        m.bus.breakAccessSequence()
        return m.cpu.step()
    }

    @Test
    fun `une instruction en IWRAM ne paie aucun temps d'attente`() {
        val m = machine()
        m.bus.write32(0x0300_0100, movR0)
        assertEquals(1, stepAt(m, 0x0300_0100), "coût nominal, sans attente")
    }

    @Test
    fun `la meme instruction en EWRAM paie le bus 16 bits`() {
        val m = machine()
        m.bus.write32(0x0200_0100, movR0)
        // Lecture non séquentielle d'un mot en EWRAM : cinq cycles d'attente.
        assertEquals(1 + 5, stepAt(m, 0x0200_0100))
    }

    @Test
    fun `une instruction en ROM paie les temps d'attente de WAITCNT`() {
        val m = machine()
        // La ROM synthétique commence par une instruction ARM valide.
        val nominal = stepAt(m, 0x0300_0100.also { m.bus.write32(it, movR0) })
        val fromRom = stepAt(m, 0x0800_0000)
        assertTrue(
            fromRom > nominal,
            "exécuter depuis la cartouche doit coûter plus cher ($fromRom vs $nominal)",
        )
        // WAITCNT à zéro : un mot non séquentiel en zone 0 coûte sept cycles
        // d'attente. La première instruction de la ROM synthétique est un saut,
        // dont le coût nominal est de trois cycles.
        assertEquals(3 + 7, fromRom)
    }

    @Test
    fun `WAITCNT accelere reellement l'execution depuis la cartouche`() {
        val m = machine()
        val slow = stepAt(m, 0x0800_0000)
        // Zone 0 au plus rapide : N = 2, S = 1 cycle d'attente.
        m.bus.write16(0x0400_0204, 0x0008 or 0x0010)
        val fast = stepAt(m, 0x0800_0000)
        assertTrue(fast < slow, "WAITCNT doit réduire le coût ($fast vs $slow)")
        assertEquals(3 + 4, fast) // N + S + un cycle de base pour le second accès
    }

    @Test
    fun `le prechargement supprime l'attente des lectures sequentielles`() {
        val m = machine()
        // Deux instructions consécutives en ROM : la seconde est séquentielle.
        // La ROM n'est pas inscriptible, on mesure donc sur le programme fourni.
        m.bus.write16(0x0400_0204, 0x4000) // préchargement activé
        m.cpu.state.regs[15] = 0x0800_0000
        m.cpu.state.thumb = false
        m.bus.breakAccessSequence()
        val first = m.cpu.step() // non séquentielle : la file est vide

        val m2 = machine()
        m2.bus.write16(0x0400_0204, 0x0000) // sans préchargement
        m2.cpu.state.regs[15] = 0x0800_0000
        m2.cpu.state.thumb = false
        m2.bus.breakAccessSequence()
        assertEquals(m2.cpu.step(), first, "un premier accès paie dans les deux cas")

        // Une lecture séquentielle d'instruction, elle, devient gratuite.
        assertEquals(0, m.bus.timing.instructionWaitStates(0x0800_0004, 4, sequential = true))
        assertEquals(5, m2.bus.timing.instructionWaitStates(0x0800_0004, 4, sequential = true))
    }

    @Test
    fun `un acces aux donnees en EWRAM alourdit l'instruction`() {
        val m = machine()
        m.bus.write32(0x0300_0100, ldrR1)
        m.cpu.state.regs[2] = 0x0300_0200 // cible en IWRAM
        val inIwram = stepAt(m, 0x0300_0100)

        m.cpu.state.regs[2] = 0x0200_0200 // même instruction, cible en EWRAM
        val inEwram = stepAt(m, 0x0300_0100)
        assertEquals(inIwram + 5, inEwram, "le mot lu en EWRAM ajoute cinq cycles")
    }

    @Test
    fun `un acces sequentiel coute moins qu'un acces disperse`() {
        val m = machine()
        m.bus.write16(0x0400_0204, 0x0000)
        // Deux lectures de mots consécutifs en ROM : la seconde est séquentielle.
        m.bus.breakAccessSequence()
        m.bus.takeWaitCycles()
        m.bus.read32(0x0800_0000)
        val firstAccess = m.bus.takeWaitCycles()
        m.bus.read32(0x0800_0004)
        val secondAccess = m.bus.takeWaitCycles()
        assertEquals(7, firstAccess, "premier accès : N + S + un cycle")
        assertEquals(5, secondAccess, "accès suivant : deux S + un cycle")

        // Une lecture éloignée repart en accès non séquentiel.
        m.bus.read32(0x0801_0000)
        assertEquals(7, m.bus.takeWaitCycles())
    }

    @Test
    fun `un etat instantane restaure retrouve les temps d'attente`() {
        val core = com.ravenemu.core.gba.KotlinGbaCore()
        core.loadRom(SyntheticRom.build())
        val m = core.machine!!
        m.bus.write16(0x0400_0204, 0x0008 or 0x0010 or 0x4000)
        val state = core.saveState()

        m.bus.write16(0x0400_0204, 0x0000)
        assertEquals(0, m.bus.timing.waitControl)

        core.loadState(state)
        // La restauration remplace la machine active.
        val apres = core.machine!!
        assertEquals(0x4018, apres.bus.timing.waitControl)
        assertTrue(apres.bus.timing.prefetchEnabled)
    }
}
