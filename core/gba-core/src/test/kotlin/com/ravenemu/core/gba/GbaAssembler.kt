package com.ravenemu.core.gba

/**
 * Petit assembleur ARM/Thumb pour les tests : il permet d'écrire des programmes
 * lisibles au lieu de tableaux de mots hexadécimaux, et de mélanger les deux
 * jeux d'instructions dans un même flux avec des étiquettes.
 *
 * Il ne couvre que les instructions dont les ROM synthétiques ont besoin. Toute
 * valeur inencodable (immédiat trop large, offset hors de portée) provoque une
 * erreur à la construction du programme, ce qui évite les ROM silencieusement
 * fausses.
 */
class GbaAssembler(private val base: Int = 0x0800_0000) {

    private val bytes = ArrayList<Byte>()
    private val labels = HashMap<String, Int>()
    private val fixups = ArrayList<Fixup>()

    /** Adresse à laquelle la prochaine instruction sera émise. */
    val position: Int get() = base + bytes.size

    private class Fixup(
        val offset: Int,
        val label: String,
        val width: Int,
        val encode: (instruction: Int, target: Int) -> Int,
    )

    // ---- Étiquettes et données ----

    fun label(name: String) {
        require(labels.put(name, position) == null) { "Étiquette dupliquée : $name" }
    }

    fun addressOf(name: String): Int =
        labels[name] ?: error("Étiquette inconnue : $name")

    fun word(value: Int) = emit32(value)

    fun byte(value: Int) {
        bytes.add((value and 0xFF).toByte())
    }

    fun bytes(values: IntArray) = values.forEach(::byte)

    /** Complète avec des zéros jusqu'au prochain multiple de [alignment]. */
    fun align(alignment: Int) {
        while (bytes.size % alignment != 0) byte(0)
    }

    /**
     * Complète avec des zéros jusqu'à l'offset [offset] depuis le début de la
     * ROM. Sert à réserver l'en-tête de cartouche (0x04 à 0xBF), que le code ne
     * doit pas recouvrir : une vraie ROM place un simple branchement en 0x00 et
     * son programme à partir de 0xC0.
     */
    fun padTo(offset: Int) {
        require(bytes.size <= offset) {
            "Déjà au-delà de l'offset 0x${offset.toString(16)} (${bytes.size} octets émis)"
        }
        while (bytes.size < offset) byte(0)
    }

    /** Réserve un mot dont la valeur est l'adresse de [label] (table de constantes). */
    fun addressWord(label: String) {
        fixupHere(label, width = 4) { _, target -> target }
        emit32(0)
    }

    /** Comme [addressWord], mais avec le bit 0 levé : cible en mode Thumb. */
    fun thumbAddressWord(label: String) {
        fixupHere(label, width = 4) { _, target -> target or 1 }
        emit32(0)
    }

    // ---- Instructions ARM ----

    /** `MOV Rd, #value` (l'immédiat doit être encodable en 8 bits tournés). */
    fun mov(rd: Int, value: Int) = emit32(0xE3A00000.toInt() or (rd shl 12) or immediate(value))

    /** `MOV Rd, Rm`. */
    fun movReg(rd: Int, rm: Int) = emit32(0xE1A00000.toInt() or (rd shl 12) or rm)

    /** `ADD Rd, Rn, #value`. */
    fun add(rd: Int, rn: Int, value: Int) =
        emit32(0xE2800000.toInt() or (rn shl 16) or (rd shl 12) or immediate(value))

    /** `SUB Rd, Rn, #value`. */
    fun sub(rd: Int, rn: Int, value: Int) =
        emit32(0xE2400000.toInt() or (rn shl 16) or (rd shl 12) or immediate(value))

    /** `SUBS Rd, Rn, #value` : soustraction positionnant les drapeaux. */
    fun subs(rd: Int, rn: Int, value: Int) =
        emit32(0xE2500000.toInt() or (rn shl 16) or (rd shl 12) or immediate(value))

    /** `CMP Rn, #value`. */
    fun cmp(rn: Int, value: Int) =
        emit32(0xE3500000.toInt() or (rn shl 16) or immediate(value))

    /** `ORR Rd, Rn, #value`. */
    fun orr(rd: Int, rn: Int, value: Int) =
        emit32(0xE3800000.toInt() or (rn shl 16) or (rd shl 12) or immediate(value))

    /** `LDR Rd, [Rn, #offset]` (offset 12 bits, positif). */
    fun ldr(rd: Int, rn: Int, offset: Int = 0) {
        require(offset in 0..0xFFF) { "Offset LDR hors de portée : $offset" }
        emit32(0xE5900000.toInt() or (rn shl 16) or (rd shl 12) or offset)
    }

    /** `STR Rd, [Rn, #offset]`. */
    fun str(rd: Int, rn: Int, offset: Int = 0) {
        require(offset in 0..0xFFF) { "Offset STR hors de portée : $offset" }
        emit32(0xE5800000.toInt() or (rn shl 16) or (rd shl 12) or offset)
    }

    /** `LDRB Rd, [Rn, #offset]`. */
    fun ldrb(rd: Int, rn: Int, offset: Int = 0) {
        require(offset in 0..0xFFF) { "Offset LDRB hors de portée : $offset" }
        emit32(0xE5D00000.toInt() or (rn shl 16) or (rd shl 12) or offset)
    }

    /** `LDRH Rd, [Rn, #offset]` (offset 8 bits). */
    fun ldrh(rd: Int, rn: Int, offset: Int = 0) =
        emit32(0xE1D000B0.toInt() or (rn shl 16) or (rd shl 12) or halfwordOffset(offset))

    /** `STRH Rd, [Rn, #offset]` (offset 8 bits). */
    fun strh(rd: Int, rn: Int, offset: Int = 0) =
        emit32(0xE1C000B0.toInt() or (rn shl 16) or (rd shl 12) or halfwordOffset(offset))

    /** `LDR Rd, [PC, #…]` : charge le mot situé à [label]. */
    fun ldrPc(rd: Int, label: String) {
        fixupHere(label, width = 4) { instruction, target ->
            val offset = target - (instruction + 8)
            require(offset in 0..0xFFF) { "Constante hors de portée du PC : $label" }
            0xE5900000.toInt() or (15 shl 16) or (rd shl 12) or offset
        }
        emit32(0)
    }

    /** `B label`. */
    fun b(label: String) = branch(label, link = false)

    /** `BNE label` : branchement si le résultat précédent est non nul. */
    fun bne(label: String) = branch(label, link = false, condition = COND_NE)

    /** `BL label`. */
    fun bl(label: String) = branch(label, link = true)

    /** `BX Rm` : saut avec basculement ARM/Thumb selon le bit 0. */
    fun bx(rm: Int) = emit32(0xE12FFF10.toInt() or rm)

    /**
     * `SWI number` : appel logiciel du BIOS. En ARM, le numéro de fonction se
     * lit dans les bits 23-16 du champ de commentaire — c'est là que le BIOS le
     * cherche, et non dans les bits de poids faible.
     */
    fun swi(number: Int) {
        require(number in 0..0xFF) { "Numéro de SWI hors de portée : $number" }
        emit32(0xEF000000.toInt() or (number shl 16))
    }

    /** `B .` : boucle sur place, fin d'un programme de test. */
    fun infiniteLoop() = emit32(0xEAFFFFFE.toInt())

    private fun branch(label: String, link: Boolean, condition: Int = COND_AL) {
        val base = if (link) 0x0B000000 else 0x0A000000
        val opcode = (condition shl 28) or base
        fixupHere(label, width = 4) { instruction, target ->
            val delta = target - (instruction + 8)
            require(delta % 4 == 0) { "Cible de branchement non alignée : $label" }
            opcode or ((delta shr 2) and 0xFFFFFF)
        }
        emit32(0)
    }

    // ---- Instructions Thumb ----

    /** `MOV Rd, #imm8` (Thumb, Rd dans r0-r7). */
    fun tMov(rd: Int, imm8: Int) {
        requireLowRegister(rd)
        require(imm8 in 0..0xFF) { "Immédiat Thumb hors de portée : $imm8" }
        emit16(0x2000 or (rd shl 8) or imm8)
    }

    /** `ADD Rd, #imm8` (Thumb). */
    fun tAdd(rd: Int, imm8: Int) {
        requireLowRegister(rd)
        require(imm8 in 0..0xFF) { "Immédiat Thumb hors de portée : $imm8" }
        emit16(0x3000 or (rd shl 8) or imm8)
    }

    /** `LSL Rd, Rm, #imm5` (Thumb). */
    fun tLsl(rd: Int, rm: Int, imm5: Int) {
        requireLowRegister(rd)
        requireLowRegister(rm)
        require(imm5 in 0..31) { "Décalage Thumb hors de portée : $imm5" }
        emit16((imm5 shl 6) or (rm shl 3) or rd)
    }

    /** `STR Rd, [Rn, #offset]` (Thumb, offset multiple de 4 jusqu'à 124). */
    fun tStr(rd: Int, rn: Int, offset: Int = 0) {
        requireLowRegister(rd)
        requireLowRegister(rn)
        require(offset in 0..124 && offset % 4 == 0) { "Offset Thumb invalide : $offset" }
        emit16(0x6000 or ((offset / 4) shl 6) or (rn shl 3) or rd)
    }

    /** `LDR Rd, [Rn, #offset]` (Thumb). */
    fun tLdr(rd: Int, rn: Int, offset: Int = 0) {
        requireLowRegister(rd)
        requireLowRegister(rn)
        require(offset in 0..124 && offset % 4 == 0) { "Offset Thumb invalide : $offset" }
        emit16(0x6800 or ((offset / 4) shl 6) or (rn shl 3) or rd)
    }

    /** `BX Rm` (Thumb). */
    fun tBx(rm: Int) = emit16(0x4700 or (rm shl 3))

    /** `SWI number` (Thumb). */
    fun tSwi(number: Int) = emit16(0xDF00 or (number and 0xFF))

    /** `B label` (Thumb, inconditionnel). */
    fun tB(label: String) {
        fixupHere(label, width = 2) { instruction, target ->
            val delta = target - (instruction + 4)
            require(delta % 2 == 0) { "Cible Thumb non alignée : $label" }
            require(delta shr 1 in -1024..1023) { "Cible Thumb hors de portée : $label" }
            0xE000 or ((delta shr 1) and 0x7FF)
        }
        emit16(0)
    }

    /** `B .` en Thumb : boucle sur place. */
    fun tInfiniteLoop() = emit16(0xE7FE)

    // ---- Assemblage ----

    fun assemble(): ByteArray {
        val output = ByteArray(bytes.size) { bytes[it] }
        for (fixup in fixups) {
            val target = labels[fixup.label] ?: error("Étiquette inconnue : ${fixup.label}")
            val instruction = base + fixup.offset
            val value = fixup.encode(instruction, target)
            for (i in 0 until fixup.width) {
                output[fixup.offset + i] = ((value ushr (i * 8)) and 0xFF).toByte()
            }
        }
        return output
    }

    private fun fixupHere(label: String, width: Int, encode: (Int, Int) -> Int) {
        fixups.add(Fixup(bytes.size, label, width, encode))
    }

    private fun emit16(value: Int) {
        byte(value)
        byte(value ushr 8)
    }

    private fun emit32(value: Int) {
        emit16(value)
        emit16(value ushr 16)
    }

    private fun requireLowRegister(register: Int) {
        require(register in 0..7) { "Registre Thumb hors de r0-r7 : r$register" }
    }

    /** Offset immédiat d'un transfert de demi-mot : deux quartets séparés. */
    private fun halfwordOffset(offset: Int): Int {
        require(offset in 0..0xFF) { "Offset de demi-mot hors de portée : $offset" }
        return ((offset and 0xF0) shl 4) or (offset and 0x0F)
    }

    /**
     * Encode un immédiat de traitement de données : un octet tourné d'un nombre
     * pair de bits. `0x04000000` s'écrit donc `0x04` tourné de 8 bits.
     */
    private fun immediate(value: Int): Int {
        for (rotation in 0 until 16) {
            val candidate = Integer.rotateLeft(value, rotation * 2)
            if (candidate in 0..0xFF) return (rotation shl 8) or candidate
        }
        error("Immédiat ARM inencodable : 0x${value.toString(16)}")
    }

    private companion object {
        /** Champs de condition ARM utilisés par l'assembleur. */
        const val COND_NE = 0x1
        const val COND_AL = 0xE
    }
}
