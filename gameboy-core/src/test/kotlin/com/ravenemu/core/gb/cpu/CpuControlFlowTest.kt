package com.ravenemu.core.gb.cpu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CpuControlFlowTest {

    @Test
    fun `JR pris et non pris`() {
        val h = CpuHarness().program(0x18, 0x05) // JR +5
        assertEquals(12, h.run())
        assertEquals(0x0107, h.cpu.pc)

        val h2 = CpuHarness().program(0x20, 0x05) // JR NZ,+5
        h2.cpu.f = Cpu.FLAG_Z
        assertEquals(8, h2.run())
        assertEquals(0x0102, h2.cpu.pc)
    }

    @Test
    fun `JR offset negatif`() {
        val h = CpuHarness().program(0x18, 0xFE) // JR -2 (boucle sur soi)
        h.run()
        assertEquals(0x0100, h.cpu.pc)
    }

    @Test
    fun `JP conditionnel et JP HL`() {
        val h = CpuHarness().program(0xC2, 0x00, 0xC2) // JP NZ,0xC200
        h.cpu.f = 0
        assertEquals(16, h.run())
        assertEquals(0xC200, h.cpu.pc)

        val h2 = CpuHarness().program(0xCA, 0x00, 0xC2) // JP Z (Z=0)
        h2.cpu.f = 0
        assertEquals(12, h2.run())
        assertEquals(0x0103, h2.cpu.pc)

        val h3 = CpuHarness().program(0xE9) // JP HL
        h3.cpu.hl = 0x1234
        assertEquals(4, h3.run())
        assertEquals(0x1234, h3.cpu.pc)
    }

    @Test
    fun `CALL empile l'adresse de retour puis RET la restaure`() {
        val h = CpuHarness().program(0xCD, 0x00, 0xC2) // CALL 0xC200
        h.bus.memory[0xC200] = 0xC9 // RET
        assertEquals(24, h.run())
        assertEquals(0xC200, h.cpu.pc)
        assertEquals(0xFFFC, h.cpu.sp)
        // Adresse de retour 0x0103 en petit-boutiste.
        assertEquals(0x03, h.bus.memory[0xFFFC])
        assertEquals(0x01, h.bus.memory[0xFFFD])
        assertEquals(16, h.run())
        assertEquals(0x0103, h.cpu.pc)
        assertEquals(0xFFFE, h.cpu.sp)
    }

    @Test
    fun `CALL conditionnel non pris coute 12 cycles`() {
        val h = CpuHarness().program(0xC4, 0x00, 0xC2) // CALL NZ
        h.cpu.f = Cpu.FLAG_Z
        assertEquals(12, h.run())
        assertEquals(0x0103, h.cpu.pc)
        assertEquals(0xFFFE, h.cpu.sp)
    }

    @Test
    fun `RET conditionnel cycles 20 ou 8`() {
        val h = CpuHarness().program(0xC8) // RET Z
        h.cpu.f = Cpu.FLAG_Z
        h.cpu.sp = 0xFFFC
        h.bus.memory[0xFFFC] = 0x34
        h.bus.memory[0xFFFD] = 0x12
        assertEquals(20, h.run())
        assertEquals(0x1234, h.cpu.pc)

        val h2 = CpuHarness().program(0xC8)
        h2.cpu.f = 0
        assertEquals(8, h2.run())
        assertEquals(0x0101, h2.cpu.pc)
    }

    @Test
    fun `RST saute au vecteur`() {
        val h = CpuHarness().program(0xEF) // RST 0x28
        assertEquals(16, h.run())
        assertEquals(0x0028, h.cpu.pc)
        assertEquals(0x01, h.bus.memory[0xFFFD])
        assertEquals(0x01, h.bus.memory[0xFFFC])
    }

    @Test
    fun `PUSH puis POP restitue la valeur`() {
        val h = CpuHarness().program(0xC5, 0xD1) // PUSH BC ; POP DE
        h.cpu.bc = 0xBEEF
        assertEquals(16, h.run())
        assertEquals(0xFFFC, h.cpu.sp)
        assertEquals(12, h.run())
        assertEquals(0xBEEF, h.cpu.de)
        assertEquals(0xFFFE, h.cpu.sp)
    }

    @Test
    fun `POP AF masque les bits bas de F`() {
        val h = CpuHarness().program(0xF1) // POP AF
        h.cpu.sp = 0xC000
        h.bus.memory[0xC000] = 0xFF
        h.bus.memory[0xC001] = 0x12
        h.run()
        assertEquals(0x12F0, h.cpu.af)
    }
}

class CpuInterruptTest {

    @Test
    fun `EI n'active IME qu'apres l'instruction suivante`() {
        val h = CpuHarness().program(0xFB, 0x00, 0x00) // EI ; NOP ; NOP
        h.run()
        assertFalse(h.cpu.ime)
        h.run()
        assertTrue(h.cpu.ime)
    }

    @Test
    fun `EI puis DI n'active jamais IME`() {
        val h = CpuHarness().program(0xFB, 0xF3, 0x00) // EI ; DI ; NOP
        h.run(3)
        assertFalse(h.cpu.ime)
    }

    @Test
    fun `service d'interruption via vecteur avec IF acquitte`() {
        val h = CpuHarness().program(0x00, 0x00)
        h.cpu.ime = true
        h.interrupts.writeEnable(0x01)
        h.interrupts.interruptFlags = 0x01 // VBlank
        val cycles = h.run()
        assertEquals(20, cycles)
        assertEquals(0x0040, h.cpu.pc)
        assertFalse(h.cpu.ime)
        assertEquals(0, h.interrupts.interruptFlags and 0x01)
        // PC 0x0100 empilé.
        assertEquals(0x00, h.bus.memory[0xFFFC])
        assertEquals(0x01, h.bus.memory[0xFFFD])
    }

    @Test
    fun `priorite des interruptions`() {
        val h = CpuHarness().program(0x00)
        h.cpu.ime = true
        h.interrupts.writeEnable(0x1F)
        h.interrupts.interruptFlags = 0x14 // Timer + Joypad
        h.run()
        assertEquals(0x0050, h.cpu.pc) // Timer prioritaire
        assertEquals(0x10, h.interrupts.interruptFlags) // Joypad toujours levée
    }

    @Test
    fun `interruption ignoree si IME eteint`() {
        val h = CpuHarness().program(0x00)
        h.interrupts.writeEnable(0x01)
        h.interrupts.interruptFlags = 0x01
        h.run()
        assertEquals(0x0101, h.cpu.pc)
    }

    @Test
    fun `HALT se reveille sans IME et continue apres`() {
        val h = CpuHarness().program(0x76, 0x04) // HALT ; INC B
        h.run()
        assertTrue(h.cpu.halted)
        assertEquals(4, h.run()) // reste endormi
        assertTrue(h.cpu.halted)

        h.interrupts.writeEnable(0x04)
        h.interrupts.interruptFlags = 0x04
        h.run() // réveil sans service (IME=0) : exécute INC B
        assertFalse(h.cpu.halted)
        assertEquals(0x01, h.cpu.b)
    }

    @Test
    fun `HALT avec IME sert l'interruption au reveil`() {
        val h = CpuHarness().program(0xFB, 0x00, 0x76) // EI ; NOP ; HALT
        h.run(3)
        assertTrue(h.cpu.halted)
        h.interrupts.writeEnable(0x01)
        h.interrupts.interruptFlags = 0x01
        h.run()
        assertEquals(0x0040, h.cpu.pc)
    }

    @Test
    fun `bug HALT relit l'octet suivant`() {
        // IME=0 et interruption en attente : l'octet après HALT est lu deux
        // fois → INC B (0x04) s'exécute puis 0x04 relu → B += 2.
        val h = CpuHarness().program(0x76, 0x04, 0x00)
        h.interrupts.writeEnable(0x04)
        h.interrupts.interruptFlags = 0x04
        h.run() // HALT → bug, pas d'arrêt
        assertFalse(h.cpu.halted)
        h.run(2)
        assertEquals(0x02, h.cpu.b)
        assertEquals(0x0102, h.cpu.pc)
    }

    @Test
    fun `RETI restaure PC et reactive IME`() {
        val h = CpuHarness().program(0xD9) // RETI
        h.cpu.sp = 0xC000
        h.bus.memory[0xC000] = 0x00
        h.bus.memory[0xC001] = 0x02
        assertEquals(16, h.run())
        assertEquals(0x0200, h.cpu.pc)
        assertTrue(h.cpu.ime)
    }
}
