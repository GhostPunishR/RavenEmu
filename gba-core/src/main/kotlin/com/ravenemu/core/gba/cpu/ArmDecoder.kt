package com.ravenemu.core.gba.cpu

/**
 * Décodage et exécution du jeu d'instructions **ARM 32 bits**, pour le
 * sous-ensemble livré au premier lot :
 *
 * - branchements `B` / `BL` et `BX` (échange ARM/Thumb) ;
 * - traitement de données (tous les codes opératoires) avec barrel shifter,
 *   opérandes immédiat/registre et mise à jour conditionnelle des drapeaux ;
 * - transfert de registre `PSR` (`MRS` / `MSR`) avec masque de champ et respect
 *   du mode utilisateur ;
 * - transfert simple `LDR` / `STR` (octet/mot, offset immédiat ou registre,
 *   pré/post-indexation, réécriture de base, rotation des lectures non alignées).
 *
 * Les instructions hors périmètre (multiplication, transferts demi-mot/signés,
 * `LDM`/`STM`, `SWP`, `SWI`, coprocesseur) sont traitées comme indéfinies : sans
 * effet, un cycle. Elles seront ajoutées aux lots suivants.
 */
class ArmDecoder(private val cpu: Arm7Tdmi) {

    fun execute(instr: Int): Int {
        // BX : 0000 0001 0010 1111 1111 1111 0001 Rn
        if (instr and 0x0FFF_FFF0 == 0x012F_FF10) {
            cpu.branchExchange(cpu.readReg(instr and 0xF))
            return 3
        }
        return when ((instr ushr 25) and 0x7) {
            0b101 -> branch(instr)
            0b010, 0b011 -> singleDataTransfer(instr)
            0b000, 0b001 -> dataProcessingOrPsr(instr)
            else -> 1 // indéfini dans ce lot
        }
    }

    private fun branch(instr: Int): Int {
        val link = (instr ushr 24) and 1 != 0
        // Offset signé 24 bits, décalé de 2 bits.
        val offset = (instr shl 8) shr 6 // sign-extend puis ×4
        if (link) cpu.writeReg(14, cpu.state.regs[15] + 4)
        cpu.branchTo(cpu.readReg(15) + offset)
        return 3
    }

    private fun dataProcessingOrPsr(instr: Int): Int {
        val immediate = (instr ushr 25) and 1 != 0
        val opcode = (instr ushr 21) and 0xF
        val setFlags = (instr ushr 20) and 1 != 0

        // Les codes de comparaison (8..B) avec S=0 codent un transfert PSR.
        if (!setFlags && opcode in 0x8..0xB) {
            return psrTransfer(instr)
        }
        // Décalage par registre avec bit 7 = 1 : encode multiplication / demi-mot,
        // hors périmètre → indéfini.
        if (!immediate && (instr ushr 4) and 1 != 0 && (instr ushr 7) and 1 != 0) {
            return 1
        }
        return dataProcessing(instr, immediate, opcode, setFlags)
    }

    private fun dataProcessing(instr: Int, immediate: Boolean, opcode: Int, setFlags: Boolean): Int {
        val rn = (instr ushr 16) and 0xF
        val rd = (instr ushr 12) and 0xF

        // Un décalage par registre (I=0, bit4=1) fait lire R15 comme pc+12.
        val registerShift = !immediate && (instr ushr 4) and 1 != 0
        val pcExtra = if (registerShift) 4 else 0
        val operandA = readWithExtra(rn, pcExtra)
        val operand2 = decodeOperand2(instr, immediate, pcExtra)

        val arithmetic: Boolean
        val result: Int = when (opcode) {
            0x0 -> { arithmetic = false; operandA and operand2 }        // AND
            0x1 -> { arithmetic = false; operandA xor operand2 }        // EOR
            0x2 -> { arithmetic = true; cpu.addWithCarry(operandA, operand2.inv(), 1) } // SUB
            0x3 -> { arithmetic = true; cpu.addWithCarry(operand2, operandA.inv(), 1) } // RSB
            0x4 -> { arithmetic = true; cpu.addWithCarry(operandA, operand2, 0) }        // ADD
            0x5 -> { arithmetic = true; cpu.addWithCarry(operandA, operand2, carry()) } // ADC
            0x6 -> { arithmetic = true; cpu.addWithCarry(operandA, operand2.inv(), carry()) } // SBC
            0x7 -> { arithmetic = true; cpu.addWithCarry(operand2, operandA.inv(), carry()) } // RSC
            0x8 -> { arithmetic = false; operandA and operand2 }        // TST
            0x9 -> { arithmetic = false; operandA xor operand2 }        // TEQ
            0xA -> { arithmetic = true; cpu.addWithCarry(operandA, operand2.inv(), 1) } // CMP
            0xB -> { arithmetic = true; cpu.addWithCarry(operandA, operand2, 0) }        // CMN
            0xC -> { arithmetic = false; operandA or operand2 }         // ORR
            0xD -> { arithmetic = false; operand2 }                     // MOV
            0xE -> { arithmetic = false; operandA and operand2.inv() }  // BIC
            else -> { arithmetic = false; operand2.inv() }              // MVN (0xF)
        }

        val writesResult = opcode !in 0x8..0xB
        if (setFlags) {
            if (rd == 15 && writesResult) {
                // Retour d'exception : le SPSR redevient le CPSR.
                if (cpu.state.hasSpsr()) cpu.state.setCpsr(cpu.state.spsr(), true)
            } else {
                cpu.setNZ(result)
                cpu.state.carry = if (arithmetic) cpu.lastCarry else cpu.shifterCarry
                if (arithmetic) cpu.state.overflow = cpu.lastOverflow
            }
        }
        if (writesResult) cpu.writeReg(rd, result)
        return if (rd == 15 && writesResult) 3 else 1
    }

    private fun decodeOperand2(instr: Int, immediate: Boolean, pcExtra: Int): Int {
        if (immediate) {
            val imm = instr and 0xFF
            val rotate = ((instr ushr 8) and 0xF) * 2
            return if (rotate == 0) {
                cpu.shifterCarry = cpu.state.carry
                imm
            } else {
                val value = (imm ushr rotate) or (imm shl (32 - rotate))
                cpu.shifterCarry = value < 0
                value
            }
        }
        val rm = instr and 0xF
        val shiftType = (instr ushr 5) and 3
        return if ((instr ushr 4) and 1 != 0) {
            val rs = (instr ushr 8) and 0xF
            cpu.shiftRegister(readWithExtra(rm, pcExtra), shiftType, cpu.readReg(rs) and 0xFF)
        } else {
            val amount = (instr ushr 7) and 0x1F
            cpu.shiftImmediate(cpu.readReg(rm), shiftType, amount)
        }
    }

    private fun psrTransfer(instr: Int): Int {
        val useSpsr = (instr ushr 22) and 1 != 0
        val isMsr = (instr ushr 21) and 1 != 0
        if (!isMsr) {
            // MRS Rd, PSR
            val rd = (instr ushr 12) and 0xF
            cpu.writeReg(rd, if (useSpsr) cpu.state.spsr() else cpu.state.cpsr())
            return 1
        }
        // MSR PSR{field}, operand
        val immediate = (instr ushr 25) and 1 != 0
        val operand = if (immediate) {
            val imm = instr and 0xFF
            val rotate = ((instr ushr 8) and 0xF) * 2
            if (rotate == 0) imm else (imm ushr rotate) or (imm shl (32 - rotate))
        } else {
            cpu.readReg(instr and 0xF)
        }
        val fields = (instr ushr 16) and 0xF
        var mask = 0
        if (fields and 0x1 != 0) mask = mask or 0x0000_00FF // contrôle (mode, T, I, F)
        if (fields and 0x2 != 0) mask = mask or 0x0000_FF00
        if (fields and 0x4 != 0) mask = mask or 0x00FF_0000
        if (fields and 0x8 != 0) mask = mask or 0xFF00_0000.toInt() // drapeaux
        if (useSpsr) {
            if (cpu.state.hasSpsr()) {
                cpu.state.setSpsr((cpu.state.spsr() and mask.inv()) or (operand and mask))
            }
            return 1
        }
        // CPSR : en mode utilisateur, seuls les drapeaux (octet de poids fort) changent.
        if (cpu.state.mode == CpuState.MODE_USER) mask = mask and 0xFF00_0000.toInt()
        val merged = (cpu.state.cpsr() and mask.inv()) or (operand and mask)
        cpu.state.setCpsr(merged, affectControl = mask and 0x0000_00FF != 0)
        return 1
    }

    private fun singleDataTransfer(instr: Int): Int {
        val registerOffset = (instr ushr 25) and 1 != 0
        val preIndex = (instr ushr 24) and 1 != 0
        val add = (instr ushr 23) and 1 != 0
        val byteAccess = (instr ushr 22) and 1 != 0
        val writeBack = (instr ushr 21) and 1 != 0
        val load = (instr ushr 20) and 1 != 0
        val rn = (instr ushr 16) and 0xF
        val rd = (instr ushr 12) and 0xF

        val offset = if (!registerOffset) {
            instr and 0xFFF
        } else {
            val rm = instr and 0xF
            val shiftType = (instr ushr 5) and 3
            val amount = (instr ushr 7) and 0x1F
            cpu.shiftImmediate(cpu.readReg(rm), shiftType, amount)
        }
        val base = cpu.readReg(rn)
        val signedOffset = if (add) offset else -offset
        val address = if (preIndex) base + signedOffset else base
        val newBase = base + signedOffset

        if (load) {
            val value = if (byteAccess) {
                cpu.bus.read8(address)
            } else {
                val word = cpu.bus.read32(address and 3.inv())
                val rotate = (address and 3) * 8
                if (rotate == 0) word else (word ushr rotate) or (word shl (32 - rotate))
            }
            if ((!preIndex || writeBack) && rn != rd) cpu.writeReg(rn, newBase)
            cpu.writeReg(rd, value)
        } else {
            val value = cpu.readReg(rd)
            if (byteAccess) cpu.bus.write8(address, value and 0xFF)
            else cpu.bus.write32(address and 3.inv(), value)
            if (!preIndex || writeBack) cpu.writeReg(rn, newBase)
        }
        return 2
    }

    private fun readWithExtra(index: Int, pcExtra: Int): Int =
        cpu.readReg(index) + if (index == 15) pcExtra else 0

    private fun carry(): Int = if (cpu.state.carry) 1 else 0
}
