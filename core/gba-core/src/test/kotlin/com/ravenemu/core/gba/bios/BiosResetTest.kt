package com.ravenemu.core.gba.bios

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** `SoftReset` et `RegisterRamReset`. */
class BiosResetTest {

    @Test
    fun `SoftReset execute bien la premiere instruction de la ROM`() {
        // La ROM écrit une valeur observable dès sa première instruction : si le
        // moteur avance le PC après le SWI, cette instruction serait sautée.
        val rom = SyntheticRom.build(
            programWords = intArrayOf(
                0xE3A000AB.toInt(), // MOV R0, #0xAB   ← doit être exécutée
                0xE3A01402.toInt(), // MOV R1, #0x02000000
                0xE5810000.toInt(), // STR R0, [R1]
                SyntheticRom.ARM_INFINITE_LOOP,
            )
        )
        val m = GbaMachine(rom)
        // On part d'un état volontairement différent, ailleurs en mémoire.
        m.cpu.state.regs[15] = 0x0300_0000
        m.cpu.state.regs[0] = 0

        m.bios.handleSwi(0x00)
        assertEquals(0x0800_0000, m.cpu.state.regs[15], "le PC doit viser l'entrée de la ROM")

        // Trois instructions suffisent à écrire la valeur si aucune n'est sautée.
        repeat(3) { m.cpu.step() }
        assertEquals(0xAB, m.bus.read32(0x0200_0000))
    }

    /** Écrit une valeur repère dans chaque famille de registres d'E/S. */
    private fun markRegisters(m: GbaMachine) {
        m.bus.io[DISPCNT] = 0x11        // affichage
        m.bus.io[DMA_CONTROL] = 0x22    // DMA
        m.bus.io[TIMER_CONTROL] = 0x33  // timer
        m.bus.io[SOUND_CNT_L] = 0x44    // audio
        m.bus.io[SERIAL_DATA] = 0x55    // série
    }

    @Test
    fun `le groupe audio n'efface que les registres audio`() {
        val m = GbaMachine(SyntheticRom.build())
        markRegisters(m)
        m.cpu.state.regs[0] = 0x40 // bit 6 : audio seul
        m.bios.handleSwi(0x01)

        assertEquals(0, m.bus.io[SOUND_CNT_L].toInt(), "l'audio doit être remis à zéro")
        assertEquals(0x11, m.bus.io[DISPCNT].toInt(), "DISPCNT doit être préservé")
        assertEquals(0x22, m.bus.io[DMA_CONTROL].toInt(), "le DMA doit être préservé")
        assertEquals(0x33, m.bus.io[TIMER_CONTROL].toInt(), "les timers doivent être préservés")
        assertEquals(0x55, m.bus.io[SERIAL_DATA].toInt(), "la série doit être préservée")
    }

    @Test
    fun `le groupe serie n'efface que les registres serie`() {
        val m = GbaMachine(SyntheticRom.build())
        markRegisters(m)
        m.cpu.state.regs[0] = 0x20 // bit 5 : série seule
        m.bios.handleSwi(0x01)

        assertEquals(0, m.bus.io[SERIAL_DATA].toInt())
        assertEquals(0x11, m.bus.io[DISPCNT].toInt())
        assertEquals(0x44, m.bus.io[SOUND_CNT_L].toInt())
    }

    @Test
    fun `le groupe des autres registres preserve audio et serie`() {
        val m = GbaMachine(SyntheticRom.build())
        markRegisters(m)
        m.cpu.state.regs[0] = 0x80 // bit 7 : affichage, DMA, timers, interruptions
        m.bios.handleSwi(0x01)

        assertEquals(0, m.bus.io[DISPCNT].toInt())
        assertEquals(0, m.bus.io[DMA_CONTROL].toInt())
        assertEquals(0, m.bus.io[TIMER_CONTROL].toInt())
        assertEquals(0x44, m.bus.io[SOUND_CNT_L].toInt(), "l'audio n'est pas dans ce groupe")
        assertEquals(0x55, m.bus.io[SERIAL_DATA].toInt(), "la série n'est pas dans ce groupe")
    }

    @Test
    fun `les zones de memoire s'effacent independamment des registres`() {
        val m = GbaMachine(SyntheticRom.build())
        m.bus.write16(0x0500_0000, 0x1234) // palette
        m.bus.write16(0x0600_0000, 0x5678) // VRAM
        markRegisters(m)

        m.cpu.state.regs[0] = 0x04 // palette seule
        m.bios.handleSwi(0x01)

        assertEquals(0, m.bus.read16(0x0500_0000))
        assertNotEquals(0, m.bus.read16(0x0600_0000), "la VRAM n'était pas demandée")
        assertEquals(0x11, m.bus.io[DISPCNT].toInt(), "aucun registre n'était demandé")
    }

    @Test
    fun `le groupe audio reinitialise aussi l'etat de l'APU`() {
        val m = GbaMachine(SyntheticRom.build())
        m.bus.write16(0x0400_0084, 0x0080) // SOUNDCNT_X : audio activé
        m.cpu.state.regs[0] = 0x40
        m.bios.handleSwi(0x01)
        assertEquals(false, m.apu.masterEnable, "l'APU doit revenir à l'arrêt")
    }

    private companion object {
        const val DISPCNT = 0x000
        const val SOUND_CNT_L = 0x080
        const val DMA_CONTROL = 0x0BA
        const val TIMER_CONTROL = 0x102
        const val SERIAL_DATA = 0x120
    }

    // ---- Matrices des plans affines ----

    private fun matrix(m: GbaMachine, base: Int) = listOf(
        m.bus.read16(0x0400_0000 + base),
        m.bus.read16(0x0400_0000 + base + 2),
        m.bus.read16(0x0400_0000 + base + 4),
        m.bus.read16(0x0400_0000 + base + 6),
    )

    /**
     * Une matrice affine nulle réduit toute la couche à un point : elle
     * n'affiche rien. Les registres ne partent donc pas de zéro, et des jeux
     * commerciaux en dépendent sans jamais les écrire.
     */
    @Test
    fun `les matrices affines valent l'identite a l'allumage`() {
        val m = GbaMachine(SyntheticRom.build())
        assertEquals(listOf(0x0100, 0, 0, 0x0100), matrix(m, 0x20), "BG2")
        assertEquals(listOf(0x0100, 0, 0, 0x0100), matrix(m, 0x30), "BG3")
    }

    /**
     * `RegisterRamReset` remet les registres d'affichage à zéro — sauf ces
     * matrices, qui reviennent à l'identité. Les mettre à zéro éteindrait un plan
     * affine que le jeu croit configuré.
     */
    @Test
    fun `la remise a zero des registres ramene les matrices a l'identite`() {
        val m = GbaMachine(SyntheticRom.build())
        // Le jeu impose sa propre transformation, puis demande la remise à zéro.
        m.bus.write16(0x0400_0020, 0x0080)
        m.bus.write16(0x0400_0026, 0x0040)
        m.bus.write16(0x0400_0000, 0x1234)

        m.cpu.state.regs[0] = 0xFF // tout remettre à zéro
        m.bios.handleSwi(0x01)

        assertEquals(0, m.bus.read16(0x0400_0000), "l'affichage doit bien être remis à zéro")
        assertEquals(listOf(0x0100, 0, 0, 0x0100), matrix(m, 0x20), "BG2")
        assertEquals(listOf(0x0100, 0, 0, 0x0100), matrix(m, 0x30), "BG3")
    }
}
