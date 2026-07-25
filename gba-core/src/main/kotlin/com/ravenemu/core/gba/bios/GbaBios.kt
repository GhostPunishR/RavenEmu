package com.ravenemu.core.gba.bios

import com.ravenemu.core.gba.cpu.Arm7Tdmi
import com.ravenemu.core.gba.cpu.SwiHandler
import com.ravenemu.core.gba.memory.GbaBus
import kotlin.math.sqrt

/**
 * **BIOS HLE (haut niveau) de RavenEmu** — écrit intégralement à partir de la
 * documentation matérielle publique, sans aucun octet du BIOS Nintendo.
 *
 * Deux rôles :
 * - **Handler d'interruption** : un court programme ARM (écrit ici) est placé au
 *   vecteur IRQ `0x18`. Il sauvegarde le contexte, lit l'adresse du gestionnaire
 *   du jeu en `0x0300_7FFC` (miroir `0x03FF_FFFC`), l'appelle, puis restaure et
 *   retourne — reproduisant le comportement documenté du BIOS.
 * - **Appels logiciels `SWI`** : interceptés et exécutés directement (division,
 *   racine, copies mémoire, mise en pause sur interruption). Les fonctions non
 *   implémentées sont sans effet (documenté).
 */
class GbaBios(
    private val cpu: Arm7Tdmi,
    private val bus: GbaBus,
) : SwiHandler {

    init {
        installIrqHandler()
    }

    /** Écrit le gestionnaire d'IRQ ARM au vecteur `0x18` du BIOS interne. */
    private fun installIrqHandler() {
        val handler = intArrayOf(
            0xE92D500F.toInt(), // stmfd sp!, {r0-r3, r12, lr}
            0xE3A00301.toInt(), // mov   r0, #0x0400_0000
            0xE28FE000.toInt(), // add   lr, pc, #0
            0xE510F004.toInt(), // ldr   pc, [r0, #-4]   ; = [0x03FF_FFFC] (gestionnaire du jeu)
            0xE8BD500F.toInt(), // ldmfd sp!, {r0-r3, r12, lr}
            0xE25EF004.toInt(), // subs  pc, lr, #4      ; retour d'exception
        )
        var offset = IRQ_VECTOR
        for (word in handler) {
            bus.bios[offset] = (word and 0xFF).toByte()
            bus.bios[offset + 1] = ((word ushr 8) and 0xFF).toByte()
            bus.bios[offset + 2] = ((word ushr 16) and 0xFF).toByte()
            bus.bios[offset + 3] = ((word ushr 24) and 0xFF).toByte()
            offset += 4
        }
    }

    override fun handleSwi(number: Int) {
        val r = cpu.state.regs
        when (number) {
            0x02, 0x03 -> cpu.state.halted = true                 // Halt / Stop
            0x04, 0x05 -> cpu.state.halted = true                 // IntrWait / VBlankIntrWait
            0x06 -> divide(numerator = r[0], denominator = r[1])  // Div
            0x07 -> divide(numerator = r[1], denominator = r[0])  // DivArm (arguments inversés)
            0x08 -> r[0] = isqrt(r[0].toLong() and 0xFFFF_FFFFL)  // Sqrt
            0x0B -> cpuSet(r[0], r[1], r[2])                      // CpuSet
            0x0C -> cpuFastSet(r[0], r[1], r[2])                  // CpuFastSet
            // Autres appels (SoftReset, RegisterRamReset, décompression…) : différés.
        }
    }

    private fun divide(numerator: Int, denominator: Int) {
        val r = cpu.state.regs
        if (denominator == 0) return // division par zéro : sans effet (documenté)
        val quotient = numerator / denominator
        r[0] = quotient
        r[1] = numerator % denominator
        r[3] = if (quotient < 0) -quotient else quotient
    }

    private fun isqrt(value: Long): Int {
        if (value <= 0) return 0
        var root = sqrt(value.toDouble()).toLong()
        // Correction des erreurs d'arrondi en virgule flottante.
        while (root * root > value) root--
        while ((root + 1) * (root + 1) <= value) root++
        return root.toInt()
    }

    /** `CpuSet` : copie ou remplissage 16/32 bits. */
    private fun cpuSet(source: Int, dest: Int, control: Int) {
        val count = control and 0x1F_FFFF
        val fixedSource = control and (1 shl 24) != 0
        val word32 = control and (1 shl 26) != 0
        val unit = if (word32) 4 else 2
        var src = source
        var dst = dest
        repeat(count) {
            if (word32) bus.write32(dst, bus.read32(src)) else bus.write16(dst, bus.read16(src))
            if (!fixedSource) src += unit
            dst += unit
        }
    }

    /** `CpuFastSet` : copie ou remplissage par blocs de mots 32 bits. */
    private fun cpuFastSet(source: Int, dest: Int, control: Int) {
        val count = control and 0x1F_FFFF
        val fixedSource = control and (1 shl 24) != 0
        var src = source
        var dst = dest
        repeat(count) {
            bus.write32(dst, bus.read32(src))
            if (!fixedSource) src += 4
            dst += 4
        }
    }

    companion object {
        const val IRQ_VECTOR = Arm7Tdmi.VECTOR_IRQ

        /** Adresse du pointeur de gestionnaire d'IRQ du jeu (miroir de 0x03FF_FFFC). */
        const val USER_IRQ_HANDLER = 0x0300_7FFC
    }
}
