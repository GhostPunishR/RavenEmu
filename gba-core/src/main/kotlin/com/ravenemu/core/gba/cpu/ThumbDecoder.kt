package com.ravenemu.core.gba.cpu

/**
 * Décodage et exécution du jeu d'instructions **Thumb 16 bits**, pour le
 * sous-ensemble livré au premier lot :
 *
 * - décalages immédiats (`LSL`/`LSR`/`ASR`) et additions/soustractions ;
 * - opérations immédiates 8 bits (`MOV`/`CMP`/`ADD`/`SUB`) ;
 * - opérations ALU registre à registre ;
 * - opérations sur registres hauts et `BX` (échange Thumb/ARM) ;
 * - chargement relatif au `PC` ;
 * - branchements conditionnels, inconditionnels et `BL` (branchement long).
 *
 * Les formats de chargement/stockage indexés, `PUSH`/`POP`, `SWI` et l'accès
 * relatif au `SP` sont différés (indéfinis, un cycle) et documentés.
 */
class ThumbDecoder(private val cpu: Arm7Tdmi) {

    fun execute(instr: Int): Int = when {
        instr and 0xF800 == 0x1800 -> addSubtract(instr)            // format 2
        instr and 0xE000 == 0x0000 -> moveShifted(instr)           // format 1
        instr and 0xE000 == 0x2000 -> immediateOps(instr)          // format 3
        instr and 0xFC00 == 0x4000 -> aluOps(instr)                // format 4
        instr and 0xFC00 == 0x4400 -> hiRegisterOps(instr)         // format 5
        instr and 0xF800 == 0x4800 -> pcRelativeLoad(instr)        // format 6
        instr and 0xF000 == 0xD000 -> conditionalBranch(instr)     // format 16
        instr and 0xF800 == 0xE000 -> unconditionalBranch(instr)   // format 18
        instr and 0xF800 == 0xF000 -> longBranchHigh(instr)        // format 19 (1/2)
        instr and 0xF800 == 0xF800 -> longBranchLow(instr)         // format 19 (2/2)
        else -> 1 // formats différés
    }

    private fun moveShifted(instr: Int): Int {
        val type = (instr ushr 11) and 3
        val amount = (instr ushr 6) and 0x1F
        val rs = (instr ushr 3) and 7
        val rd = instr and 7
        val result = cpu.shiftImmediate(cpu.readReg(rs), type, amount)
        cpu.writeReg(rd, result)
        cpu.setNZ(result)
        cpu.state.carry = cpu.shifterCarry
        return 1
    }

    private fun addSubtract(instr: Int): Int {
        val immediate = (instr ushr 10) and 1 != 0
        val subtract = (instr ushr 9) and 1 != 0
        val operandField = (instr ushr 6) and 7
        val rs = (instr ushr 3) and 7
        val rd = instr and 7
        val a = cpu.readReg(rs)
        val b = if (immediate) operandField else cpu.readReg(operandField)
        val result = if (subtract) cpu.addWithCarry(a, b.inv(), 1) else cpu.addWithCarry(a, b, 0)
        cpu.writeReg(rd, result)
        setArithmeticFlags(result)
        return 1
    }

    private fun immediateOps(instr: Int): Int {
        val opcode = (instr ushr 11) and 3
        val rd = (instr ushr 8) and 7
        val immediate = instr and 0xFF
        val current = cpu.readReg(rd)
        when (opcode) {
            0 -> { // MOV
                cpu.writeReg(rd, immediate)
                cpu.setNZ(immediate)
            }
            1 -> { // CMP
                val result = cpu.addWithCarry(current, immediate.inv(), 1)
                setArithmeticFlags(result)
            }
            2 -> { // ADD
                val result = cpu.addWithCarry(current, immediate, 0)
                cpu.writeReg(rd, result)
                setArithmeticFlags(result)
            }
            else -> { // SUB
                val result = cpu.addWithCarry(current, immediate.inv(), 1)
                cpu.writeReg(rd, result)
                setArithmeticFlags(result)
            }
        }
        return 1
    }

    private fun aluOps(instr: Int): Int {
        val opcode = (instr ushr 6) and 0xF
        val rs = (instr ushr 3) and 7
        val rd = instr and 7
        val a = cpu.readReg(rd)
        val b = cpu.readReg(rs)
        when (opcode) {
            0x0 -> logic(rd, a and b)                                 // AND
            0x1 -> logic(rd, a xor b)                                 // EOR
            0x2 -> shift(rd, a, 0, b and 0xFF)                        // LSL
            0x3 -> shift(rd, a, 1, b and 0xFF)                        // LSR
            0x4 -> shift(rd, a, 2, b and 0xFF)                        // ASR
            0x5 -> arith(rd, cpu.addWithCarry(a, b, carry()), write = true)   // ADC
            0x6 -> arith(rd, cpu.addWithCarry(a, b.inv(), carry()), write = true) // SBC
            0x7 -> shift(rd, a, 3, b and 0xFF)                        // ROR
            0x8 -> logic(rd, a and b, write = false)                  // TST
            0x9 -> arith(rd, cpu.addWithCarry(0, b.inv(), 1), write = true)   // NEG = 0 - Rs
            0xA -> arith(rd, cpu.addWithCarry(a, b.inv(), 1), write = false)  // CMP
            0xB -> arith(rd, cpu.addWithCarry(a, b, 0), write = false)        // CMN
            0xC -> logic(rd, a or b)                                  // ORR
            0xD -> Unit                                              // MUL différé
            0xE -> logic(rd, a and b.inv())                          // BIC
            else -> logic(rd, b.inv())                               // MVN (0xF)
        }
        return 1
    }

    private fun hiRegisterOps(instr: Int): Int {
        val opcode = (instr ushr 8) and 3
        val rd = (instr and 7) or (((instr ushr 7) and 1) shl 3)
        val rs = ((instr ushr 3) and 7) or (((instr ushr 6) and 1) shl 3)
        when (opcode) {
            0 -> cpu.writeReg(rd, cpu.readReg(rd) + cpu.readReg(rs))  // ADD (sans drapeaux)
            1 -> {                                                    // CMP (avec drapeaux)
                val result = cpu.addWithCarry(cpu.readReg(rd), cpu.readReg(rs).inv(), 1)
                setArithmeticFlags(result)
            }
            2 -> cpu.writeReg(rd, cpu.readReg(rs))                    // MOV (sans drapeaux)
            else -> cpu.branchExchange(cpu.readReg(rs))               // BX
        }
        return if (opcode != 1 && rd == 15) 3 else 1
    }

    private fun pcRelativeLoad(instr: Int): Int {
        val rd = (instr ushr 8) and 7
        val offset = (instr and 0xFF) shl 2
        val address = (cpu.readReg(15) and 2.inv()) + offset
        cpu.writeReg(rd, cpu.bus.read32(address))
        return 3
    }

    private fun conditionalBranch(instr: Int): Int {
        val cond = (instr ushr 8) and 0xF
        if (cond == 0xF || cond == 0xE) return 1 // SWI / indéfini : différés
        if (!cpu.checkCondition(cond)) return 1
        val offset = ((instr and 0xFF) shl 24) shr 23 // sign-extend 8 bits, ×2
        cpu.branchTo(cpu.readReg(15) + offset)
        return 3
    }

    private fun unconditionalBranch(instr: Int): Int {
        val offset = ((instr and 0x7FF) shl 21) shr 20 // sign-extend 11 bits, ×2
        cpu.branchTo(cpu.readReg(15) + offset)
        return 3
    }

    private fun longBranchHigh(instr: Int): Int {
        val offsetHigh = ((instr and 0x7FF) shl 21) shr 9 // sign-extend 11 bits, ×4096
        cpu.writeReg(14, cpu.readReg(15) + offsetHigh)
        return 1
    }

    private fun longBranchLow(instr: Int): Int {
        val offsetLow = (instr and 0x7FF) shl 1
        val target = cpu.readReg(14) + offsetLow
        // Adresse de retour : instruction suivant ce demi-mot, marquée Thumb.
        cpu.writeReg(14, (cpu.state.regs[15] + 2) or 1)
        cpu.branchTo(target)
        return 3
    }

    // ---- Drapeaux ----

    private fun logic(rd: Int, result: Int, write: Boolean = true) {
        if (write) cpu.writeReg(rd, result)
        cpu.setNZ(result)
    }

    private fun shift(rd: Int, value: Int, type: Int, amount: Int) {
        val result = cpu.shiftRegister(value, type, amount)
        cpu.writeReg(rd, result)
        cpu.setNZ(result)
        cpu.state.carry = cpu.shifterCarry
    }

    private fun arith(rd: Int, result: Int, write: Boolean) {
        if (write) cpu.writeReg(rd, result)
        setArithmeticFlags(result)
    }

    private fun setArithmeticFlags(result: Int) {
        cpu.setNZ(result)
        cpu.state.carry = cpu.lastCarry
        cpu.state.overflow = cpu.lastOverflow
    }

    private fun carry(): Int = if (cpu.state.carry) 1 else 0
}
