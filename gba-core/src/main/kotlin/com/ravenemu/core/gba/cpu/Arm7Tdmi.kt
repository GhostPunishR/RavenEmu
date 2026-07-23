package com.ravenemu.core.gba.cpu

import com.ravenemu.core.gba.memory.GbaBus

/**
 * Moteur du processeur ARM7TDMI de la Game Boy Advance.
 *
 * Il possède l'[état architectural][CpuState], accède à la mémoire via le
 * [GbaBus] et délègue le décodage/exécution aux jeux d'instructions
 * [ArmDecoder] (32 bits) et [ThumbDecoder] (16 bits).
 *
 * Modèle de pipeline simplifié mais exact du point de vue logiciel : `R15`
 * conserve l'adresse de l'instruction courante et sa lecture renvoie `+8` en
 * ARM (`+4` en Thumb), comme sur le matériel. Toute écriture de `R15` provoque
 * un « saut » (vidage de pipeline) traité par [branchTo]/[branchExchange].
 *
 * Premier lot : sous-ensemble d'instructions suffisant pour exécuter une ROM
 * synthétique (traitement de données complet avec barrel shifter et drapeaux,
 * `B`/`BL`/`BX`, `LDR`/`STR`, `MRS`/`MSR`). Le jeu complet, les interruptions
 * matérielles et les temps d'attente réels sont différés (limites documentées).
 */
class Arm7Tdmi(val bus: GbaBus) {

    val state = CpuState()

    private val armDecoder = ArmDecoder(this)
    private val thumbDecoder = ThumbDecoder(this)

    /** Retenue produite par le barrel shifter lors du dernier décodage d'opérande. */
    var shifterCarry = false

    /** Retenue/débordement produits par la dernière opération arithmétique. */
    var lastCarry = false
    var lastOverflow = false

    /** Positionné par une instruction qui a modifié `R15` (saut). */
    private var branched = false

    /**
     * Réinitialise le CPU et place le point d'entrée. Reproduit l'état laissé
     * par le BIOS : mode système, piles initialisées, exécution en ARM.
     */
    fun reset(entryPoint: Int) {
        state.reset()
        // Piles par défaut des modes concernés (valeurs usuelles post-BIOS).
        state.switchMode(CpuState.MODE_SUPERVISOR)
        state.regs[13] = 0x0300_7FE0
        state.switchMode(CpuState.MODE_IRQ)
        state.regs[13] = 0x0300_7FA0
        state.switchMode(CpuState.MODE_SYSTEM)
        state.regs[13] = 0x0300_7F00
        state.thumb = false
        state.regs[15] = entryPoint
    }

    /** Lecture d'un registre, `R15` renvoyant l'adresse courante + décalage de pipeline. */
    fun readReg(index: Int): Int =
        if (index == 15) state.regs[15] + if (state.thumb) 4 else 8 else state.regs[index]

    /** Écriture d'un registre ; écrire `R15` déclenche un saut (aligné). */
    fun writeReg(index: Int, value: Int) {
        if (index == 15) branchTo(value) else state.regs[index] = value
    }

    /** Saut vers [address] en conservant le mode courant (aligné sur mot/demi-mot). */
    fun branchTo(address: Int) {
        state.regs[15] = if (state.thumb) address and 1.inv() else address and 3.inv()
        branched = true
    }

    /** Saut avec échange ARM/Thumb : le bit 0 de [address] sélectionne le mode Thumb. */
    fun branchExchange(address: Int) {
        state.thumb = (address and 1) != 0
        state.regs[15] = if (state.thumb) address and 1.inv() else address and 3.inv()
        branched = true
    }

    /**
     * Exécute une instruction et retourne un coût en cycles (approximatif dans
     * ce premier lot : le comptage exact des temps d'attente est différé).
     */
    fun step(): Int {
        branched = false
        val cost: Int
        if (state.thumb) {
            val instr = bus.read16(state.regs[15]) and 0xFFFF
            cost = thumbDecoder.execute(instr)
            if (!branched) state.regs[15] += 2
        } else {
            val instr = bus.read32(state.regs[15])
            cost = if (checkCondition(instr ushr 28)) {
                armDecoder.execute(instr)
            } else {
                1
            }
            if (!branched) state.regs[15] += 4
        }
        return cost
    }

    /** Évalue le champ de condition ARM (bits 31–28). */
    fun checkCondition(cond: Int): Boolean = when (cond and 0xF) {
        0x0 -> state.zero
        0x1 -> !state.zero
        0x2 -> state.carry
        0x3 -> !state.carry
        0x4 -> state.negative
        0x5 -> !state.negative
        0x6 -> state.overflow
        0x7 -> !state.overflow
        0x8 -> state.carry && !state.zero
        0x9 -> !state.carry || state.zero
        0xA -> state.negative == state.overflow
        0xB -> state.negative != state.overflow
        0xC -> !state.zero && (state.negative == state.overflow)
        0xD -> state.zero || (state.negative != state.overflow)
        0xE -> true
        else -> false // 0xF : réservé sur ARMv4T, jamais exécuté
    }

    // ---- Barrel shifter ----

    /**
     * Décalage par une valeur immédiate. Les cas `amount == 0` codent les
     * décalages spéciaux ARM : `LSR/ASR #0` valent `#32`, `ROR #0` vaut `RRX`.
     */
    fun shiftImmediate(value: Int, type: Int, amount: Int): Int = when (type and 3) {
        0 -> { // LSL
            if (amount == 0) {
                shifterCarry = state.carry
                value
            } else {
                shifterCarry = (value ushr (32 - amount)) and 1 != 0
                value shl amount
            }
        }
        1 -> { // LSR
            val n = if (amount == 0) 32 else amount
            shifterCarry = (value ushr (n - 1)) and 1 != 0
            if (n == 32) 0 else value ushr n
        }
        2 -> { // ASR
            val n = if (amount == 0) 32 else amount
            if (n >= 32) {
                shifterCarry = value < 0
                if (value < 0) -1 else 0
            } else {
                shifterCarry = (value shr (n - 1)) and 1 != 0
                value shr n
            }
        }
        else -> { // ROR / RRX
            if (amount == 0) { // RRX : rotation 33 bits à travers la retenue
                val carryIn = if (state.carry) 1 else 0
                shifterCarry = value and 1 != 0
                (value ushr 1) or (carryIn shl 31)
            } else {
                val n = amount and 31
                if (n == 0) {
                    shifterCarry = value < 0
                    value
                } else {
                    shifterCarry = (value ushr (n - 1)) and 1 != 0
                    (value ushr n) or (value shl (32 - n))
                }
            }
        }
    }

    /**
     * Décalage par une valeur contenue dans un registre (`amount` = 8 bits de
     * poids faible). `amount == 0` laisse la valeur et la retenue inchangées.
     */
    fun shiftRegister(value: Int, type: Int, amount: Int): Int {
        val n = amount and 0xFF
        if (n == 0) {
            shifterCarry = state.carry
            return value
        }
        return when (type and 3) {
            0 -> { // LSL
                when {
                    n < 32 -> {
                        shifterCarry = (value ushr (32 - n)) and 1 != 0
                        value shl n
                    }
                    n == 32 -> {
                        shifterCarry = value and 1 != 0
                        0
                    }
                    else -> {
                        shifterCarry = false
                        0
                    }
                }
            }
            1 -> { // LSR
                when {
                    n < 32 -> {
                        shifterCarry = (value ushr (n - 1)) and 1 != 0
                        value ushr n
                    }
                    n == 32 -> {
                        shifterCarry = value < 0
                        0
                    }
                    else -> {
                        shifterCarry = false
                        0
                    }
                }
            }
            2 -> { // ASR
                if (n >= 32) {
                    shifterCarry = value < 0
                    if (value < 0) -1 else 0
                } else {
                    shifterCarry = (value shr (n - 1)) and 1 != 0
                    value shr n
                }
            }
            else -> { // ROR
                val r = n and 31
                if (r == 0) {
                    shifterCarry = value < 0
                    value
                } else {
                    shifterCarry = (value ushr (r - 1)) and 1 != 0
                    (value ushr r) or (value shl (32 - r))
                }
            }
        }
    }

    // ---- Arithmétique et drapeaux ----

    /**
     * Additionne [a], [b] et [carryIn] (0 ou 1) sur 32 bits en positionnant
     * [lastCarry] (retenue non signée) et [lastOverflow] (débordement signé).
     * La soustraction `a - b` s'exprime `addWithCarry(a, b.inv(), 1)`.
     */
    fun addWithCarry(a: Int, b: Int, carryIn: Int): Int {
        val ua = a.toLong() and 0xFFFF_FFFFL
        val ub = b.toLong() and 0xFFFF_FFFFL
        val sum = ua + ub + carryIn
        val result = sum.toInt()
        lastCarry = sum > 0xFFFF_FFFFL
        lastOverflow = ((a xor result) and (b xor result)) < 0
        return result
    }

    /** Positionne N et Z d'après [result]. */
    fun setNZ(result: Int) {
        state.negative = result < 0
        state.zero = result == 0
    }
}
