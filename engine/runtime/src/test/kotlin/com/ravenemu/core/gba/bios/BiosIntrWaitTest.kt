package com.ravenemu.core.gba.bios

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.interrupt.Interrupt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `IntrWait` et `VBlankIntrWait` : l'attente doit porter sur une interruption
 * **précise**, pas se contenter d'endormir le processeur jusqu'à n'importe quel
 * événement.
 */
class BiosIntrWaitTest {

    private val vblank = 1 shl Interrupt.VBLANK
    private val timer0 = 1 shl Interrupt.TIMER0

    private fun machine() = GbaMachine(SyntheticRom.build())

    @Test
    fun `VBlankIntrWait attend le VBlank et rien d'autre`() {
        val m = machine()
        m.bios.handleSwi(0x05)

        assertTrue(m.cpu.state.halted, "le processeur doit être en pause")
        assertEquals(vblank, m.bios.waitState?.interruptMask)
        assertTrue(m.interrupts.masterEnable, "le BIOS doit activer IME")

        m.interrupts.request(Interrupt.TIMER0)
        assertFalse(m.bios.shouldResume(), "un timer ne réveille pas une attente VBlank")

        m.interrupts.request(Interrupt.VBLANK)
        assertTrue(m.bios.shouldResume())
        assertNull(m.bios.waitState, "l'attente est terminée")
    }

    @Test
    fun `une interruption hors du masque ne reveille pas`() {
        val m = machine()
        m.cpu.state.regs[0] = 1
        m.cpu.state.regs[1] = timer0
        m.bios.handleSwi(0x04)

        m.interrupts.request(Interrupt.VBLANK)
        assertFalse(m.bios.shouldResume())

        m.interrupts.request(Interrupt.TIMER0)
        assertTrue(m.bios.shouldResume())
    }

    @Test
    fun `une interruption deja survenue rend la main immediatement`() {
        val m = machine()
        m.interrupts.request(Interrupt.VBLANK) // avant l'appel

        m.cpu.state.regs[0] = 0 // conserver les anciens drapeaux
        m.cpu.state.regs[1] = vblank
        m.bios.handleSwi(0x04)

        assertFalse(m.cpu.state.halted, "aucune pause : l'événement avait déjà eu lieu")
        assertNull(m.bios.waitState)
        assertEquals(0, m.bios.biosFlags() and vblank, "le drapeau est consommé")
    }

    @Test
    fun `l'effacement des anciens drapeaux force l'attente du suivant`() {
        val m = machine()
        m.interrupts.request(Interrupt.VBLANK) // événement passé

        m.cpu.state.regs[0] = 1 // oublier les anciens drapeaux
        m.cpu.state.regs[1] = vblank
        m.bios.handleSwi(0x04)

        assertTrue(m.cpu.state.halted, "l'ancien VBlank ne compte plus")
        assertFalse(m.bios.shouldResume())

        m.interrupts.request(Interrupt.VBLANK) // nouvel événement
        assertTrue(m.bios.shouldResume())
    }

    @Test
    fun `un masque a plusieurs sources se reveille sur l'une d'elles`() {
        val m = machine()
        m.cpu.state.regs[0] = 1
        m.cpu.state.regs[1] = vblank or timer0
        m.bios.handleSwi(0x04)

        m.interrupts.request(Interrupt.TIMER0)
        assertTrue(m.bios.shouldResume(), "le timer fait partie du masque")
    }

    @Test
    fun `les drapeaux hors masque sont preserves au reveil`() {
        val m = machine()
        m.cpu.state.regs[0] = 1
        m.cpu.state.regs[1] = vblank
        m.bios.handleSwi(0x04)

        m.interrupts.request(Interrupt.TIMER0) // hors masque, doit subsister
        m.interrupts.request(Interrupt.VBLANK)
        assertTrue(m.bios.shouldResume())

        assertEquals(0, m.bios.biosFlags() and vblank, "la source attendue est consommée")
        assertEquals(timer0, m.bios.biosFlags() and timer0, "les autres sont conservées")
    }

    @Test
    fun `un masque vide n'endort pas le processeur`() {
        val m = machine()
        m.cpu.state.regs[0] = 1
        m.cpu.state.regs[1] = 0
        m.bios.handleSwi(0x04)
        assertFalse(m.cpu.state.halted)
        assertNull(m.bios.waitState)
    }

    @Test
    fun `Halt simple se reveille sur n'importe quelle interruption activee`() {
        val m = machine()
        m.bios.handleSwi(0x02)
        assertTrue(m.cpu.state.halted)
        assertNull(m.bios.waitState, "Halt n'installe pas d'attente ciblée")

        assertFalse(m.bios.shouldResume(), "aucune interruption activée")
        m.interrupts.enable = 0xFFFF
        m.interrupts.request(Interrupt.TIMER0)
        assertTrue(m.bios.shouldResume())
    }

    @Test
    fun `l'execution reprend a l'instruction suivant le SWI`() {
        // SWI VBlankIntrWait puis une écriture observable : la reprise doit
        // exécuter l'instruction qui suit immédiatement l'appel.
        val rom = SyntheticRom.build(
            programWords = intArrayOf(
                0xEF050000.toInt(), // SWI 0x05 (VBlankIntrWait)
                0xE3A000CD.toInt(), // MOV R0, #0xCD
                0xE3A01402.toInt(), // MOV R1, #0x02000000
                0xE5810000.toInt(), // STR R0, [R1]
                SyntheticRom.ARM_INFINITE_LOOP,
            )
        )
        val m = GbaMachine(rom)
        m.interrupts.enable = 0xFFFF
        // DISPSTAT : interruption VBlank activée, pour que la trame la lève.
        m.bus.write16(0x0400_0004, 0x0008)
        // Les IRQ étant démasquées au sortir du BIOS, l'interruption est
        // réellement délivrée : il faut donc un gestionnaire, comme dans un vrai
        // jeu, et qui acquitte `IF`.
        SyntheticRom.installMinimalIrqHandler(m.bus)

        m.runFrame(com.ravenemu.core.gba.KotlinGbaCore.CYCLES_PER_FRAME)

        assertEquals(0xCD, m.bus.read32(0x0200_0000), "la suite du programme doit s'exécuter")
    }

    @Test
    fun `l'attente survit a un aller-retour d'etat instantane`() {
        val core = com.ravenemu.core.gba.KotlinGbaCore()
        core.loadRom(SyntheticRom.build())
        val m = core.machine!!
        m.cpu.state.regs[0] = 1
        m.cpu.state.regs[1] = vblank or timer0
        m.bios.handleSwi(0x04)

        val state = core.saveState()
        m.bios.waitState = null
        m.cpu.state.halted = false

        core.loadState(state)
        // La restauration remplace la machine active : c'est la nouvelle qui
        // porte l'état restauré, `m` restant la machine abandonnée.
        val apres = core.machine!!
        val restored = assertNotNull(apres.bios.waitState)
        assertEquals(vblank or timer0, restored.interruptMask)
        assertTrue(restored.discardOldFlags)
        assertTrue(apres.cpu.state.halted)
    }
}
