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
 * - branchements conditionnels, inconditionnels et `BL` (branchement long) ;
 * - chargements/stockages : offset registre, signés/demi-mot, offset immédiat,
 *   demi-mot, relatif au `SP`, calcul d'adresse (`ADD Rd, PC/SP`), ajustement du
 *   `SP`, `PUSH`/`POP` (avec `LR`/`PC`) et transfert de blocs (`LDMIA`/`STMIA`) ;
 * - `MUL` et `SWI` (entrée en exception superviseur).
 */
class ThumbDecoder(private val cpu: Arm7Tdmi) {

    fun execute(instr: Int): Int = when {
        instr and 0xF800 == 0x1800 -> addSubtract(instr)            // format 2
        instr and 0xE000 == 0x0000 -> moveShifted(instr)           // format 1
        instr and 0xE000 == 0x2000 -> immediateOps(instr)          // format 3
        instr and 0xFC00 == 0x4000 -> aluOps(instr)                // format 4
        instr and 0xFC00 == 0x4400 -> hiRegisterOps(instr)         // format 5
        instr and 0xF800 == 0x4800 -> pcRelativeLoad(instr)        // format 6
        instr and 0xF200 == 0x5000 -> loadStoreRegisterOffset(instr) // format 7
        instr and 0xF200 == 0x5200 -> loadStoreSignExtended(instr) // format 8
        instr and 0xE000 == 0x6000 -> loadStoreImmediate(instr)    // format 9
        instr and 0xF000 == 0x8000 -> loadStoreHalfword(instr)     // format 10
        instr and 0xF000 == 0x9000 -> loadStoreSpRelative(instr)   // format 11
        instr and 0xF000 == 0xA000 -> loadAddress(instr)           // format 12
        instr and 0xFF00 == 0xB000 -> addOffsetToSp(instr)         // format 13
        instr and 0xF600 == 0xB400 -> pushPop(instr)               // format 14
        instr and 0xF000 == 0xC000 -> blockTransfer(instr)         // format 15
        instr and 0xFF00 == 0xDF00 -> softwareInterrupt(instr)     // format 17
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
            0xD -> logic(rd, a * b)                                  // MUL (Rd = Rd * Rs)
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

    private fun loadStoreRegisterOffset(instr: Int): Int {
        val load = (instr ushr 11) and 1 != 0
        val byteAccess = (instr ushr 10) and 1 != 0
        val ro = (instr ushr 6) and 7
        val rb = (instr ushr 3) and 7
        val rd = instr and 7
        val address = cpu.readReg(rb) + cpu.readReg(ro)
        when {
            load && byteAccess -> cpu.writeReg(rd, cpu.bus.read8(address))
            load -> cpu.writeReg(rd, cpu.loadWordRotated(address))
            byteAccess -> cpu.bus.write8(address, cpu.readReg(rd) and 0xFF)
            else -> cpu.bus.write32(address and 3.inv(), cpu.readReg(rd))
        }
        return 2
    }

    private fun loadStoreSignExtended(instr: Int): Int {
        val h = (instr ushr 11) and 1 != 0
        val s = (instr ushr 10) and 1 != 0
        val ro = (instr ushr 6) and 7
        val rb = (instr ushr 3) and 7
        val rd = instr and 7
        val address = cpu.readReg(rb) + cpu.readReg(ro)
        when {
            !s && !h -> cpu.bus.write16(address, cpu.readReg(rd) and 0xFFFF)     // STRH
            !s -> cpu.writeReg(rd, cpu.bus.read16(address) and 0xFFFF)           // LDRH
            !h -> cpu.writeReg(rd, (cpu.bus.read8(address) shl 24) shr 24)       // LDRSB
            else -> cpu.writeReg(rd, (cpu.bus.read16(address) shl 16) shr 16)    // LDRSH
        }
        return 2
    }

    private fun loadStoreImmediate(instr: Int): Int {
        val byteAccess = (instr ushr 12) and 1 != 0
        val load = (instr ushr 11) and 1 != 0
        val offset5 = (instr ushr 6) and 0x1F
        val rb = (instr ushr 3) and 7
        val rd = instr and 7
        val address = cpu.readReg(rb) + if (byteAccess) offset5 else offset5 shl 2
        when {
            load && byteAccess -> cpu.writeReg(rd, cpu.bus.read8(address))
            load -> cpu.writeReg(rd, cpu.loadWordRotated(address))
            byteAccess -> cpu.bus.write8(address, cpu.readReg(rd) and 0xFF)
            else -> cpu.bus.write32(address and 3.inv(), cpu.readReg(rd))
        }
        return 2
    }

    private fun loadStoreHalfword(instr: Int): Int {
        val load = (instr ushr 11) and 1 != 0
        val offset = ((instr ushr 6) and 0x1F) shl 1
        val rb = (instr ushr 3) and 7
        val rd = instr and 7
        val address = cpu.readReg(rb) + offset
        if (load) cpu.writeReg(rd, cpu.bus.read16(address) and 0xFFFF)
        else cpu.bus.write16(address, cpu.readReg(rd) and 0xFFFF)
        return 2
    }

    private fun loadStoreSpRelative(instr: Int): Int {
        val load = (instr ushr 11) and 1 != 0
        val rd = (instr ushr 8) and 7
        val address = cpu.readReg(13) + ((instr and 0xFF) shl 2)
        if (load) cpu.writeReg(rd, cpu.loadWordRotated(address))
        else cpu.bus.write32(address and 3.inv(), cpu.readReg(rd))
        return 2
    }

    private fun loadAddress(instr: Int): Int {
        val useSp = (instr ushr 11) and 1 != 0
        val rd = (instr ushr 8) and 7
        val offset = (instr and 0xFF) shl 2
        val base = if (useSp) cpu.readReg(13) else cpu.readReg(15) and 2.inv()
        cpu.writeReg(rd, base + offset)
        return 1
    }

    private fun addOffsetToSp(instr: Int): Int {
        val offset = (instr and 0x7F) shl 2
        val negative = (instr ushr 7) and 1 != 0
        cpu.writeReg(13, cpu.readReg(13) + if (negative) -offset else offset)
        return 1
    }

    private fun pushPop(instr: Int): Int {
        val load = (instr ushr 11) and 1 != 0
        val pcOrLr = (instr ushr 8) and 1 != 0
        val list = instr and 0xFF
        val count = Integer.bitCount(list) + if (pcOrLr) 1 else 0
        if (load) {
            var address = cpu.readReg(13)
            for (r in 0 until 8) {
                if (list and (1 shl r) == 0) continue
                cpu.writeReg(r, cpu.bus.read32(address))
                address += 4
            }
            if (pcOrLr) { // POP {PC}
                cpu.writeReg(15, cpu.bus.read32(address))
                address += 4
            }
            cpu.writeReg(13, address)
        } else {
            var address = cpu.readReg(13) - count * 4
            cpu.writeReg(13, address)
            for (r in 0 until 8) {
                if (list and (1 shl r) == 0) continue
                cpu.bus.write32(address, cpu.readReg(r))
                address += 4
            }
            if (pcOrLr) cpu.bus.write32(address, cpu.readReg(14)) // PUSH {LR}
        }
        return count + 1
    }

    private fun blockTransfer(instr: Int): Int {
        val load = (instr ushr 11) and 1 != 0
        val rb = (instr ushr 8) and 7
        val list = instr and 0xFF
        if (list == 0) return 1 // cas limite non émulé
        var address = cpu.readReg(rb)
        for (r in 0 until 8) {
            if (list and (1 shl r) == 0) continue
            if (load) cpu.writeReg(r, cpu.bus.read32(address))
            else cpu.bus.write32(address, cpu.readReg(r))
            address += 4
        }
        if (!(load && list and (1 shl rb) != 0)) cpu.writeReg(rb, address)
        return Integer.bitCount(list) + 1
    }

    private fun softwareInterrupt(instr: Int): Int {
        cpu.raiseException(
            CpuState.MODE_SUPERVISOR,
            Arm7Tdmi.VECTOR_SWI,
            cpu.state.regs[15] + 2,
        )
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
