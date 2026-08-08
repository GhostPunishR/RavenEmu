package com.ravenemu.core.gb.cpu

import com.ravenemu.core.gb.Interrupt
import com.ravenemu.core.gb.InterruptController
import com.ravenemu.core.gb.memory.Bus

/**
 * CPU Sharp LR35902 : jeu d'instructions principal et préfixe CB, IME avec
 * délai d'activation d'EI, HALT (bug compris), service d'interruptions.
 *
 * [step] exécute une instruction (ou un service d'interruption) et retourne
 * le nombre de T-cycles consommés ; l'appelant fait avancer les périphériques
 * d'autant. Les registres sont exposés pour les tests et la sérialisation.
 * Les valeurs initiales correspondent à l'état post-boot ROM d'une DMG.
 */
class Cpu(private val bus: Bus, private val interrupts: InterruptController) {

    var a = 0x01
    var f = 0xB0
    var b = 0x00
    var c = 0x13
    var d = 0x00
    var e = 0xD8
    var h = 0x01
    var l = 0x4D
    var sp = 0xFFFE
    var pc = 0x0100

    var ime = false
    var halted = false

    /** CPU verrouillé après un opcode invalide (comportement matériel). */
    var locked = false

    /** EI actif : IME sera levé après l'instruction suivante. */
    var eiPending = false

    /** Bug HALT : le prochain octet d'opcode sera lu sans avancer PC. */
    var haltBug = false

    // ---- Paires 16 bits ----

    var af: Int
        get() = (a shl 8) or f
        set(value) {
            a = (value shr 8) and 0xFF
            f = value and 0xF0
        }

    var bc: Int
        get() = (b shl 8) or c
        set(value) {
            b = (value shr 8) and 0xFF
            c = value and 0xFF
        }

    var de: Int
        get() = (d shl 8) or e
        set(value) {
            d = (value shr 8) and 0xFF
            e = value and 0xFF
        }

    var hl: Int
        get() = (h shl 8) or l
        set(value) {
            h = (value shr 8) and 0xFF
            l = value and 0xFF
        }

    // ---- Drapeaux ----

    val flagZ: Boolean get() = (f and FLAG_Z) != 0
    val flagN: Boolean get() = (f and FLAG_N) != 0
    val flagH: Boolean get() = (f and FLAG_H) != 0
    val flagC: Boolean get() = (f and FLAG_C) != 0

    private fun setFlags(z: Boolean, n: Boolean, h: Boolean, c: Boolean) {
        f = (if (z) FLAG_Z else 0) or (if (n) FLAG_N else 0) or
            (if (h) FLAG_H else 0) or (if (c) FLAG_C else 0)
    }

    // ---- Boucle d'exécution ----

    /** Exécute une instruction et retourne les T-cycles consommés. */
    fun step(): Int {
        if (locked) return 4

        val enableIme = eiPending
        eiPending = false

        if (halted) {
            if ((interrupts.interruptEnable and interrupts.interruptFlags and 0x1F) != 0) {
                halted = false
            } else {
                return 4
            }
        }

        if (ime) {
            val irq = interrupts.highestPending()
            if (irq != null) return serviceInterrupt(irq)
        }

        val opcode = fetchOpcode()
        val cycles = execute(opcode)
        if (enableIme && opcode != 0xF3) ime = true
        return cycles
    }

    private fun serviceInterrupt(irq: Interrupt): Int {
        ime = false
        eiPending = false
        interrupts.acknowledge(irq)
        push(pc)
        pc = irq.vector
        return 20
    }

    // ---- Accès mémoire ----

    private fun fetchOpcode(): Int {
        val v = bus.read(pc)
        if (haltBug) haltBug = false else pc = (pc + 1) and 0xFFFF
        return v
    }

    private fun fetchByte(): Int {
        val v = bus.read(pc)
        pc = (pc + 1) and 0xFFFF
        return v
    }

    private fun fetchWord(): Int {
        val lo = fetchByte()
        return lo or (fetchByte() shl 8)
    }

    private fun push(value: Int) {
        sp = (sp - 1) and 0xFFFF
        bus.write(sp, (value shr 8) and 0xFF)
        sp = (sp - 1) and 0xFFFF
        bus.write(sp, value and 0xFF)
    }

    private fun pop(): Int {
        val lo = bus.read(sp)
        sp = (sp + 1) and 0xFFFF
        val hi = bus.read(sp)
        sp = (sp + 1) and 0xFFFF
        return (hi shl 8) or lo
    }

    /** Registre 8 bits indexé par l'encodage matériel (6 = mémoire (HL)). */
    private fun readR8(index: Int): Int = when (index) {
        0 -> b
        1 -> c
        2 -> d
        3 -> e
        4 -> h
        5 -> l
        6 -> bus.read(hl)
        else -> a
    }

    private fun writeR8(index: Int, value: Int) {
        val v = value and 0xFF
        when (index) {
            0 -> b = v
            1 -> c = v
            2 -> d = v
            3 -> e = v
            4 -> h = v
            5 -> l = v
            6 -> bus.write(hl, v)
            else -> a = v
        }
    }

    // ---- ALU ----

    private fun add8(value: Int, carry: Int) {
        val result = a + value + carry
        setFlags(
            z = (result and 0xFF) == 0,
            n = false,
            h = (a and 0x0F) + (value and 0x0F) + carry > 0x0F,
            c = result > 0xFF,
        )
        a = result and 0xFF
    }

    private fun sub8(value: Int, carry: Int, store: Boolean) {
        val result = a - value - carry
        setFlags(
            z = (result and 0xFF) == 0,
            n = true,
            h = (a and 0x0F) - (value and 0x0F) - carry < 0,
            c = result < 0,
        )
        if (store) a = result and 0xFF
    }

    private fun and8(value: Int) {
        a = a and value
        setFlags(a == 0, n = false, h = true, c = false)
    }

    private fun xor8(value: Int) {
        a = (a xor value) and 0xFF
        setFlags(a == 0, n = false, h = false, c = false)
    }

    private fun or8(value: Int) {
        a = (a or value) and 0xFF
        setFlags(a == 0, n = false, h = false, c = false)
    }

    private fun inc8(value: Int): Int {
        val result = (value + 1) and 0xFF
        f = (f and FLAG_C) or (if (result == 0) FLAG_Z else 0) or
            (if ((value and 0x0F) == 0x0F) FLAG_H else 0)
        return result
    }

    private fun dec8(value: Int): Int {
        val result = (value - 1) and 0xFF
        f = (f and FLAG_C) or FLAG_N or (if (result == 0) FLAG_Z else 0) or
            (if ((value and 0x0F) == 0) FLAG_H else 0)
        return result
    }

    private fun addHl(value: Int) {
        val result = hl + value
        f = (f and FLAG_Z) or
            (if ((hl and 0x0FFF) + (value and 0x0FFF) > 0x0FFF) FLAG_H else 0) or
            (if (result > 0xFFFF) FLAG_C else 0)
        hl = result and 0xFFFF
    }

    /** ADD SP,e8 et LD HL,SP+e8 : drapeaux calculés sur l'octet bas. */
    private fun spPlusImmediate(): Int {
        val offset = fetchByte().toByte().toInt()
        setFlags(
            z = false,
            n = false,
            h = (sp and 0x0F) + (offset and 0x0F) > 0x0F,
            c = (sp and 0xFF) + (offset and 0xFF) > 0xFF,
        )
        return (sp + offset) and 0xFFFF
    }

    private fun daa() {
        var result = a
        if (!flagN) {
            if (flagC || result > 0x99) {
                result += 0x60
                f = f or FLAG_C
            }
            if (flagH || (result and 0x0F) > 0x09) result += 0x06
        } else {
            if (flagC) result -= 0x60
            if (flagH) result -= 0x06
        }
        result = result and 0xFF
        f = (f and (FLAG_N or FLAG_C)) or (if (result == 0) FLAG_Z else 0)
        a = result
    }

    // ---- Rotations / décalages ----

    private fun rlc(value: Int): Int {
        val carry = (value shr 7) and 1
        val result = ((value shl 1) or carry) and 0xFF
        setFlags(result == 0, n = false, h = false, c = carry != 0)
        return result
    }

    private fun rrc(value: Int): Int {
        val carry = value and 1
        val result = ((value shr 1) or (carry shl 7)) and 0xFF
        setFlags(result == 0, n = false, h = false, c = carry != 0)
        return result
    }

    private fun rl(value: Int): Int {
        val carryIn = if (flagC) 1 else 0
        val carry = (value shr 7) and 1
        val result = ((value shl 1) or carryIn) and 0xFF
        setFlags(result == 0, n = false, h = false, c = carry != 0)
        return result
    }

    private fun rr(value: Int): Int {
        val carryIn = if (flagC) 0x80 else 0
        val carry = value and 1
        val result = (value shr 1) or carryIn
        setFlags(result == 0, n = false, h = false, c = carry != 0)
        return result
    }

    private fun sla(value: Int): Int {
        val carry = (value shr 7) and 1
        val result = (value shl 1) and 0xFF
        setFlags(result == 0, n = false, h = false, c = carry != 0)
        return result
    }

    private fun sra(value: Int): Int {
        val carry = value and 1
        val result = (value shr 1) or (value and 0x80)
        setFlags(result == 0, n = false, h = false, c = carry != 0)
        return result
    }

    private fun swap(value: Int): Int {
        val result = ((value shl 4) or (value shr 4)) and 0xFF
        setFlags(result == 0, n = false, h = false, c = false)
        return result
    }

    private fun srl(value: Int): Int {
        val carry = value and 1
        val result = value shr 1
        setFlags(result == 0, n = false, h = false, c = carry != 0)
        return result
    }

    // ---- Branchements ----

    private fun jumpRelative(condition: Boolean): Int {
        val offset = fetchByte().toByte().toInt()
        if (!condition) return 8
        pc = (pc + offset) and 0xFFFF
        return 12
    }

    private fun jumpAbsolute(condition: Boolean): Int {
        val target = fetchWord()
        if (!condition) return 12
        pc = target
        return 16
    }

    private fun call(condition: Boolean): Int {
        val target = fetchWord()
        if (!condition) return 12
        push(pc)
        pc = target
        return 24
    }

    private fun returnIf(condition: Boolean): Int {
        if (!condition) return 8
        pc = pop()
        return 20
    }

    private fun rst(vector: Int): Int {
        push(pc)
        pc = vector
        return 16
    }

    // ---- Décodage ----

    private fun execute(opcode: Int): Int {
        when (opcode) {
            0x00 -> return 4 // NOP
            0x01 -> { bc = fetchWord(); return 12 }
            0x02 -> { bus.write(bc, a); return 8 }
            0x03 -> { bc = (bc + 1) and 0xFFFF; return 8 }
            0x04 -> { b = inc8(b); return 4 }
            0x05 -> { b = dec8(b); return 4 }
            0x06 -> { b = fetchByte(); return 8 }
            0x07 -> { a = rlc(a); f = f and FLAG_Z.inv(); return 4 } // RLCA
            0x08 -> { bus.writeWord(fetchWord(), sp); return 20 }
            0x09 -> { addHl(bc); return 8 }
            0x0A -> { a = bus.read(bc); return 8 }
            0x0B -> { bc = (bc - 1) and 0xFFFF; return 8 }
            0x0C -> { c = inc8(c); return 4 }
            0x0D -> { c = dec8(c); return 4 }
            0x0E -> { c = fetchByte(); return 8 }
            0x0F -> { a = rrc(a); f = f and FLAG_Z.inv(); return 4 } // RRCA

            0x10 -> { fetchByte(); bus.onStop(); return 4 } // STOP (+ bascule vitesse CGB)
            0x11 -> { de = fetchWord(); return 12 }
            0x12 -> { bus.write(de, a); return 8 }
            0x13 -> { de = (de + 1) and 0xFFFF; return 8 }
            0x14 -> { d = inc8(d); return 4 }
            0x15 -> { d = dec8(d); return 4 }
            0x16 -> { d = fetchByte(); return 8 }
            0x17 -> { a = rl(a); f = f and FLAG_Z.inv(); return 4 } // RLA
            0x18 -> return jumpRelative(true)
            0x19 -> { addHl(de); return 8 }
            0x1A -> { a = bus.read(de); return 8 }
            0x1B -> { de = (de - 1) and 0xFFFF; return 8 }
            0x1C -> { e = inc8(e); return 4 }
            0x1D -> { e = dec8(e); return 4 }
            0x1E -> { e = fetchByte(); return 8 }
            0x1F -> { a = rr(a); f = f and FLAG_Z.inv(); return 4 } // RRA

            0x20 -> return jumpRelative(!flagZ)
            0x21 -> { hl = fetchWord(); return 12 }
            0x22 -> { bus.write(hl, a); hl = (hl + 1) and 0xFFFF; return 8 }
            0x23 -> { hl = (hl + 1) and 0xFFFF; return 8 }
            0x24 -> { h = inc8(h); return 4 }
            0x25 -> { h = dec8(h); return 4 }
            0x26 -> { h = fetchByte(); return 8 }
            0x27 -> { daa(); return 4 }
            0x28 -> return jumpRelative(flagZ)
            0x29 -> { addHl(hl); return 8 }
            0x2A -> { a = bus.read(hl); hl = (hl + 1) and 0xFFFF; return 8 }
            0x2B -> { hl = (hl - 1) and 0xFFFF; return 8 }
            0x2C -> { l = inc8(l); return 4 }
            0x2D -> { l = dec8(l); return 4 }
            0x2E -> { l = fetchByte(); return 8 }
            0x2F -> { a = a.inv() and 0xFF; f = f or FLAG_N or FLAG_H; return 4 } // CPL

            0x30 -> return jumpRelative(!flagC)
            0x31 -> { sp = fetchWord(); return 12 }
            0x32 -> { bus.write(hl, a); hl = (hl - 1) and 0xFFFF; return 8 }
            0x33 -> { sp = (sp + 1) and 0xFFFF; return 8 }
            0x34 -> { bus.write(hl, inc8(bus.read(hl))); return 12 }
            0x35 -> { bus.write(hl, dec8(bus.read(hl))); return 12 }
            0x36 -> { bus.write(hl, fetchByte()); return 12 }
            0x37 -> { f = (f and FLAG_Z) or FLAG_C; return 4 } // SCF
            0x38 -> return jumpRelative(flagC)
            0x39 -> { addHl(sp); return 8 }
            0x3A -> { a = bus.read(hl); hl = (hl - 1) and 0xFFFF; return 8 }
            0x3B -> { sp = (sp - 1) and 0xFFFF; return 8 }
            0x3C -> { a = inc8(a); return 4 }
            0x3D -> { a = dec8(a); return 4 }
            0x3E -> { a = fetchByte(); return 8 }
            0x3F -> { // CCF
                f = (f and FLAG_Z) or ((f and FLAG_C) xor FLAG_C)
                return 4
            }

            0x76 -> { // HALT
                if (!ime && (interrupts.interruptEnable and interrupts.interruptFlags and 0x1F) != 0) {
                    haltBug = true
                } else {
                    halted = true
                }
                return 4
            }

            in 0x40..0x7F -> { // LD r,r'
                val src = opcode and 0x07
                val dst = (opcode shr 3) and 0x07
                writeR8(dst, readR8(src))
                return if (src == 6 || dst == 6) 8 else 4
            }

            in 0x80..0xBF -> { // Opérations ALU A,r
                val src = opcode and 0x07
                val value = readR8(src)
                val carry = if (flagC) 1 else 0
                when ((opcode shr 3) and 0x07) {
                    0 -> add8(value, 0)
                    1 -> add8(value, carry)
                    2 -> sub8(value, 0, store = true)
                    3 -> sub8(value, carry, store = true)
                    4 -> and8(value)
                    5 -> xor8(value)
                    6 -> or8(value)
                    7 -> sub8(value, 0, store = false) // CP
                }
                return if (src == 6) 8 else 4
            }

            0xC0 -> return returnIf(!flagZ)
            0xC1 -> { bc = pop(); return 12 }
            0xC2 -> return jumpAbsolute(!flagZ)
            0xC3 -> return jumpAbsolute(true)
            0xC4 -> return call(!flagZ)
            0xC5 -> { push(bc); return 16 }
            0xC6 -> { add8(fetchByte(), 0); return 8 }
            0xC7 -> return rst(0x00)
            0xC8 -> return returnIf(flagZ)
            0xC9 -> { pc = pop(); return 16 }
            0xCA -> return jumpAbsolute(flagZ)
            0xCB -> return executeCb()
            0xCC -> return call(flagZ)
            0xCD -> return call(true)
            0xCE -> { add8(fetchByte(), if (flagC) 1 else 0); return 8 }
            0xCF -> return rst(0x08)

            0xD0 -> return returnIf(!flagC)
            0xD1 -> { de = pop(); return 12 }
            0xD2 -> return jumpAbsolute(!flagC)
            0xD4 -> return call(!flagC)
            0xD5 -> { push(de); return 16 }
            0xD6 -> { sub8(fetchByte(), 0, store = true); return 8 }
            0xD7 -> return rst(0x10)
            0xD8 -> return returnIf(flagC)
            0xD9 -> { pc = pop(); ime = true; return 16 } // RETI
            0xDA -> return jumpAbsolute(flagC)
            0xDC -> return call(flagC)
            0xDE -> { sub8(fetchByte(), if (flagC) 1 else 0, store = true); return 8 }
            0xDF -> return rst(0x18)

            0xE0 -> { bus.write(0xFF00 + fetchByte(), a); return 12 }
            0xE1 -> { hl = pop(); return 12 }
            0xE2 -> { bus.write(0xFF00 + c, a); return 8 }
            0xE5 -> { push(hl); return 16 }
            0xE6 -> { and8(fetchByte()); return 8 }
            0xE7 -> return rst(0x20)
            0xE8 -> { sp = spPlusImmediate(); return 16 }
            0xE9 -> { pc = hl; return 4 }
            0xEA -> { bus.write(fetchWord(), a); return 16 }
            0xEE -> { xor8(fetchByte()); return 8 }
            0xEF -> return rst(0x28)

            0xF0 -> { a = bus.read(0xFF00 + fetchByte()); return 12 }
            0xF1 -> { af = pop(); return 12 }
            0xF2 -> { a = bus.read(0xFF00 + c); return 8 }
            0xF3 -> { ime = false; eiPending = false; return 4 } // DI
            0xF5 -> { push(af); return 16 }
            0xF6 -> { or8(fetchByte()); return 8 }
            0xF7 -> return rst(0x30)
            0xF8 -> { hl = spPlusImmediate(); return 12 }
            0xF9 -> { sp = hl; return 8 }
            0xFA -> { a = bus.read(fetchWord()); return 16 }
            0xFB -> { eiPending = true; return 4 } // EI
            0xFE -> { sub8(fetchByte(), 0, store = false); return 8 }
            0xFF -> return rst(0x38)

            else -> { // Opcode invalide : le CPU réel se verrouille.
                locked = true
                return 4
            }
        }
    }

    private fun executeCb(): Int {
        val op = fetchByte()
        val reg = op and 0x07
        val selector = (op shr 3) and 0x07
        val isMemory = reg == 6
        val value = readR8(reg)
        when (op shr 6) {
            0 -> { // rotations et décalages
                val result = when (selector) {
                    0 -> rlc(value)
                    1 -> rrc(value)
                    2 -> rl(value)
                    3 -> rr(value)
                    4 -> sla(value)
                    5 -> sra(value)
                    6 -> swap(value)
                    else -> srl(value)
                }
                writeR8(reg, result)
                return if (isMemory) 16 else 8
            }
            1 -> { // BIT n,r
                val zero = (value shr selector) and 1 == 0
                f = (f and FLAG_C) or FLAG_H or (if (zero) FLAG_Z else 0)
                return if (isMemory) 12 else 8
            }
            2 -> { // RES n,r
                writeR8(reg, value and (1 shl selector).inv())
                return if (isMemory) 16 else 8
            }
            else -> { // SET n,r
                writeR8(reg, value or (1 shl selector))
                return if (isMemory) 16 else 8
            }
        }
    }

    companion object {
        const val FLAG_Z = 0x80
        const val FLAG_N = 0x40
        const val FLAG_H = 0x20
        const val FLAG_C = 0x10
    }
}
