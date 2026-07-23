package com.ravenemu.core.gba.cpu

/**
 * État architectural de l'ARM7TDMI : les seize registres `R0..R15`, les
 * drapeaux et bits de contrôle du `CPSR`, les `SPSR` sauvegardés et les
 * **banques de registres** propres à chaque mode processeur.
 *
 * `R15` (le compteur de programme) contient ici l'adresse de l'instruction en
 * cours d'exécution ; le décalage de pipeline (`+8` en ARM, `+4` en Thumb) est
 * appliqué par le moteur [Arm7Tdmi] au moment de lire `R15`, jamais stocké.
 *
 * Cette classe est un conteneur de données pur (aucun accès mémoire), afin de
 * rester testable en isolation.
 */
class CpuState {

    /** Registres actifs `R0..R15` du mode courant. */
    val regs = IntArray(16)

    // Drapeaux de condition du CPSR.
    var negative = false
    var zero = false
    var carry = false
    var overflow = false

    // Bits de contrôle du CPSR.
    var irqDisabled = true
    var fiqDisabled = true
    var thumb = false

    /** Mode processeur courant (voir constantes `MODE_*`). */
    var mode = MODE_SUPERVISOR
        private set

    // Banques R13/R14, une paire par slot de mode (voir bankSlot).
    private val bankedR13 = IntArray(BANK_SLOTS)
    private val bankedR14 = IntArray(BANK_SLOTS)

    // R8..R12 : jeu utilisateur (partagé par tous les modes sauf FIQ) et jeu FIQ.
    private val usrR8to12 = IntArray(5)
    private val fiqR8to12 = IntArray(5)

    // SPSR par slot de mode (le slot utilisateur/système n'en a pas).
    private val spsr = IntArray(BANK_SLOTS)

    /** Réinitialise l'état comme à la mise sous tension (mode superviseur). */
    fun reset() {
        regs.fill(0)
        bankedR13.fill(0)
        bankedR14.fill(0)
        usrR8to12.fill(0)
        fiqR8to12.fill(0)
        spsr.fill(0)
        negative = false
        zero = false
        carry = false
        overflow = false
        irqDisabled = true
        fiqDisabled = true
        thumb = false
        mode = MODE_SUPERVISOR
    }

    /** Assemble le CPSR (drapeaux, bits de contrôle, mode) en un mot 32 bits. */
    fun cpsr(): Int {
        var value = mode and 0x1F
        if (thumb) value = value or (1 shl 5)
        if (fiqDisabled) value = value or (1 shl 6)
        if (irqDisabled) value = value or (1 shl 7)
        if (overflow) value = value or (1 shl 28)
        if (carry) value = value or (1 shl 29)
        if (zero) value = value or (1 shl 30)
        if (negative) value = value or (1 shl 31)
        return value
    }

    /**
     * Écrit le CPSR. Si [affectControl] est vrai, les bits de contrôle et le
     * mode sont mis à jour (avec bascule de banques) ; sinon seuls les drapeaux
     * de condition changent (utile pour un `MSR` limité au champ drapeaux).
     */
    fun setCpsr(value: Int, affectControl: Boolean) {
        negative = (value ushr 31) and 1 != 0
        zero = (value ushr 30) and 1 != 0
        carry = (value ushr 29) and 1 != 0
        overflow = (value ushr 28) and 1 != 0
        if (affectControl) {
            thumb = (value ushr 5) and 1 != 0
            fiqDisabled = (value ushr 6) and 1 != 0
            irqDisabled = (value ushr 7) and 1 != 0
            switchMode(value and 0x1F)
        }
    }

    /** SPSR du mode courant (0 en mode utilisateur/système, sans SPSR). */
    fun spsr(): Int = spsr[bankSlot(mode)]

    fun setSpsr(value: Int) {
        val slot = bankSlot(mode)
        if (slot != SLOT_USER) spsr[slot] = value
    }

    /** `true` si le mode courant possède un SPSR (mode privilégié hors système). */
    fun hasSpsr(): Boolean = bankSlot(mode) != SLOT_USER

    /**
     * Bascule vers [newMode] en sauvegardant les registres banqués du mode
     * sortant puis en chargeant ceux du mode entrant. Sans effet si le mode ne
     * change pas.
     */
    fun switchMode(newMode: Int) {
        val target = newMode and 0x1F
        if (target == mode) return
        storeBanks(mode)
        loadBanks(target)
        mode = target
    }

    private fun storeBanks(fromMode: Int) {
        val slot = bankSlot(fromMode)
        bankedR13[slot] = regs[13]
        bankedR14[slot] = regs[14]
        val r8to12 = if (fromMode == MODE_FIQ) fiqR8to12 else usrR8to12
        for (i in 0 until 5) r8to12[i] = regs[8 + i]
    }

    private fun loadBanks(toMode: Int) {
        val slot = bankSlot(toMode)
        regs[13] = bankedR13[slot]
        regs[14] = bankedR14[slot]
        val r8to12 = if (toMode == MODE_FIQ) fiqR8to12 else usrR8to12
        for (i in 0 until 5) regs[8 + i] = r8to12[i]
    }

    /** Copie de travail des banques (pour la sérialisation d'état). */
    fun exportBanks(): IntArray {
        // On fige d'abord les registres actifs dans les banques du mode courant.
        storeBanks(mode)
        val out = IntArray(BANK_SLOTS * 3 + 10)
        var i = 0
        for (v in bankedR13) out[i++] = v
        for (v in bankedR14) out[i++] = v
        for (v in spsr) out[i++] = v
        for (v in usrR8to12) out[i++] = v
        for (v in fiqR8to12) out[i++] = v
        return out
    }

    /**
     * Restaure les banques brutes (sans recharger les registres actifs) : la
     * restauration d'état écrit ensuite directement les registres actifs et le
     * mode, sans déclencher de bascule de banque.
     */
    fun importBanks(data: IntArray) {
        var i = 0
        for (j in bankedR13.indices) bankedR13[j] = data[i++]
        for (j in bankedR14.indices) bankedR14[j] = data[i++]
        for (j in spsr.indices) spsr[j] = data[i++]
        for (j in usrR8to12.indices) usrR8to12[j] = data[i++]
        for (j in fiqR8to12.indices) fiqR8to12[j] = data[i++]
    }

    /**
     * Fixe les drapeaux, bits de contrôle et mode depuis un mot CPSR **sans**
     * sauvegarder ni recharger de banque (utilisé par la restauration d'état,
     * qui gère les banques séparément).
     */
    fun setControlRaw(cpsrValue: Int) {
        negative = (cpsrValue ushr 31) and 1 != 0
        zero = (cpsrValue ushr 30) and 1 != 0
        carry = (cpsrValue ushr 29) and 1 != 0
        overflow = (cpsrValue ushr 28) and 1 != 0
        thumb = (cpsrValue ushr 5) and 1 != 0
        fiqDisabled = (cpsrValue ushr 6) and 1 != 0
        irqDisabled = (cpsrValue ushr 7) and 1 != 0
        mode = cpsrValue and 0x1F
    }

    companion object {
        const val MODE_USER = 0x10
        const val MODE_FIQ = 0x11
        const val MODE_IRQ = 0x12
        const val MODE_SUPERVISOR = 0x13
        const val MODE_ABORT = 0x17
        const val MODE_UNDEFINED = 0x1B
        const val MODE_SYSTEM = 0x1F

        private const val BANK_SLOTS = 6
        private const val SLOT_USER = 0

        /** Associe chaque mode à un slot de banque R13/R14 (USR et SYS partagés). */
        private fun bankSlot(mode: Int): Int = when (mode and 0x1F) {
            MODE_FIQ -> 1
            MODE_IRQ -> 2
            MODE_SUPERVISOR -> 3
            MODE_ABORT -> 4
            MODE_UNDEFINED -> 5
            else -> SLOT_USER // USER, SYSTEM et valeurs inattendues
        }
    }
}
