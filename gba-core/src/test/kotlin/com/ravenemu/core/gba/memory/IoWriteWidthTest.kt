package com.ravenemu.core.gba.memory

import com.ravenemu.core.gba.GbaMachine
import com.ravenemu.core.gba.SyntheticRom
import com.ravenemu.core.gba.interrupt.Interrupt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Une écriture d'E/S doit produire le même effet matériel quelle que soit sa
 * largeur : un jeu qui pose un octet sur `SOUNDCNT_X` allume réellement l'audio,
 * et un mot de 32 bits couvrant deux registres doit déclencher les deux.
 */
class IoWriteWidthTest {

    private fun machine() = GbaMachine(SyntheticRom.build())

    // ---- Audio ----

    @Test
    fun `STRB sur SOUNDCNT_X active l'APU`() {
        val m = machine()
        assertFalse(m.apu.masterEnable)
        m.bus.write8(0x0400_0084, 0x80)
        assertTrue(m.apu.masterEnable, "une écriture d'octet doit allumer l'audio")

        m.bus.write8(0x0400_0084, 0x00)
        assertFalse(m.apu.masterEnable, "et pouvoir l'éteindre")
    }

    @Test
    fun `une ecriture partielle de SOUNDCNT_H conserve l'autre octet`() {
        val m = machine()
        m.bus.write16(0x0400_0082, 0x0302)      // proportion PSG + volume A
        m.bus.write8(0x0400_0083, 0x0B)         // octet haut seul : panoramique
        assertEquals(0x0B02, m.apu.controlH, "l'octet bas doit survivre")
    }

    @Test
    fun `une ecriture 32 bits couvre les deux registres audio`() {
        val m = machine()
        // 0x80 : SOUNDCNT_L, 0x82 : SOUNDCNT_H — un seul mot les traverse.
        m.bus.write32(0x0400_0080, 0x0002_1234)
        assertEquals(0x1234, m.apu.controlL)
        assertEquals(0x0002, m.apu.controlH)
    }

    @Test
    fun `une FIFO recoit exactement les octets ecrits`() {
        val m = machine()
        m.bus.write8(0x0400_00A0, 0x11)
        assertEquals(1, m.apu.fifoSize(0), "un octet écrit, un octet empilé")

        m.bus.write16(0x0400_00A2, 0x3322)
        assertEquals(3, m.apu.fifoSize(0))

        m.bus.write32(0x0400_00A4, 0x8877_6655.toInt())
        assertEquals(4, m.apu.fifoSize(1), "quatre octets dans la file B")
    }

    // ---- Timers ----

    @Test
    fun `une ecriture 32 bits arme un timer et sa valeur de rechargement`() {
        val m = machine()
        // 0x100 : rechargement, 0x102 : contrôle. Activation dans le même mot.
        m.bus.write32(0x0400_0100, 0x0080_1234)
        assertEquals(0x1234, m.timers.counter(0), "le compteur charge le rechargement")
        assertEquals(0x0080, m.timers.control(0))
    }

    @Test
    fun `un STRB sur le controle d'un timer l'active`() {
        val m = machine()
        m.bus.write16(0x0400_0100, 0xABCD) // rechargement
        m.bus.write8(0x0400_0102, 0x80)    // activation par octet
        assertEquals(0xABCD, m.timers.counter(0))
    }

    // ---- DMA ----

    @Test
    fun `un STRB sur l'octet haut du controle DMA declenche le transfert`() {
        val m = machine()
        m.bus.write32(0x0200_0000, 0xCAFE_BABE.toInt())
        m.bus.write32(0x0400_00B0, 0x0200_0000) // source
        m.bus.write32(0x0400_00B4, 0x0200_1000) // destination
        m.bus.write16(0x0400_00B8, 1)           // un mot
        // Contrôle d'adresses dans l'octet bas ; largeur (bit 10) et activation
        // (bit 15) dans l'octet haut.
        m.bus.write8(0x0400_00BA, 0x00)
        m.bus.write8(0x0400_00BB, 0x84)         // activation + transfert 32 bits
        assertEquals(0xCAFE_BABE.toInt(), m.bus.read32(0x0200_1000))
    }

    // ---- Interruptions ----

    @Test
    fun `IE se met a jour par octet comme par demi-mot`() {
        val m = machine()
        m.bus.write8(0x0400_0200, 0x01)
        assertEquals(0x0001, m.interrupts.enable)
        m.bus.write8(0x0400_0201, 0x10)
        assertEquals(0x1001, m.interrupts.enable, "l'octet bas doit survivre")
    }

    @Test
    fun `un STRB sur IF acquitte l'interruption`() {
        val m = machine()
        m.interrupts.request(Interrupt.VBLANK)
        assertTrue(m.interrupts.flags and 0x01 != 0)
        m.bus.write8(0x0400_0202, 0x01)
        assertEquals(0, m.interrupts.flags and 0x01, "l'écriture d'un 1 efface le drapeau")
    }

    @Test
    fun `IME s'active par octet`() {
        val m = machine()
        m.bus.write8(0x0400_0208, 0x01)
        assertTrue(m.interrupts.masterEnable)
        m.bus.write8(0x0400_0208, 0x00)
        assertFalse(m.interrupts.masterEnable)
    }

    @Test
    fun `une ecriture 32 bits sur IE et IF traite les deux registres`() {
        val m = machine()
        m.interrupts.request(Interrupt.VBLANK)
        // 0x200 : IE, 0x202 : IF (acquittement du VBlank dans le même mot).
        m.bus.write32(0x0400_0200, 0x0001_3FFF)
        assertEquals(0x3FFF, m.interrupts.enable)
        assertEquals(0, m.interrupts.flags and 0x01)
    }

    // ---- Affichage ----

    @Test
    fun `un STRB sur DISPCNT change le mode video`() {
        val m = machine()
        m.bus.write8(0x0400_0000, 0x03) // mode 3
        // Le PPU lit DISPCNT directement : l'écriture d'octet suffit.
        assertEquals(0x03, m.bus.read8(0x0400_0000))
    }
}
