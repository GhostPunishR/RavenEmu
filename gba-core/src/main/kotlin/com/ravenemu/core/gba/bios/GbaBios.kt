package com.ravenemu.core.gba.bios

import com.ravenemu.core.gba.cpu.Arm7Tdmi
import com.ravenemu.core.gba.interrupt.Interrupt
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

    /** Attente d'interruption en cours, ou `null` hors `IntrWait`. */
    var waitState: BiosWaitState? = null

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
        bus.diagnostics.onSwi(number)
        when (number) {
            0x00 -> softReset()                                   // SoftReset
            0x01 -> registerRamReset(r[0])                        // RegisterRamReset
            0x02, 0x03 -> {                                       // Halt / Stop
                waitState = null
                cpu.state.halted = true
            }
            0x04 -> intrWait(discardOldFlags = r[0] != 0, mask = r[1] and 0x3FFF)
            0x05 -> intrWait(discardOldFlags = true, mask = 1 shl Interrupt.VBLANK)
            0x06 -> divide(numerator = r[0], denominator = r[1])  // Div
            0x07 -> divide(numerator = r[1], denominator = r[0])  // DivArm (arguments inversés)
            0x08 -> r[0] = isqrt(r[0].toLong() and 0xFFFF_FFFFL)  // Sqrt
            0x09 -> r[0] = arcTan(r[0])                           // ArcTan
            0x0A -> r[0] = arcTan2(r[0], r[1])                    // ArcTan2
            0x0B -> cpuSet(r[0], r[1], r[2])                      // CpuSet
            0x0C -> cpuFastSet(r[0], r[1], r[2])                  // CpuFastSet
            0x0E -> bgAffineSet(r[0], r[1], r[2])                 // BgAffineSet
            0x0F -> objAffineSet(r[0], r[1], r[2], r[3])          // ObjAffineSet
            0x10 -> BiosDecompression.bitUnPack(bus, r[0], r[1], r[2])
            0x11 -> BiosDecompression.lz77(bus, r[0], r[1], toVram = false)
            0x12 -> BiosDecompression.lz77(bus, r[0], r[1], toVram = true)
            0x13 -> BiosDecompression.huffman(bus, r[0], r[1])
            0x14 -> BiosDecompression.runLength(bus, r[0], r[1], toVram = false)
            0x15 -> BiosDecompression.runLength(bus, r[0], r[1], toVram = true)
            0x16 -> BiosDecompression.differentialUnfilter(bus, r[0], r[1], wide = false, toVram = false)
            0x17 -> BiosDecompression.differentialUnfilter(bus, r[0], r[1], wide = false, toVram = true)
            0x18 -> BiosDecompression.differentialUnfilter(bus, r[0], r[1], wide = true, toVram = true)
            // Appels du pilote sonore (0x19–0x1F) et diagnostics : sans effet.
            else -> bus.diagnostics.report(
                com.ravenemu.core.gba.diag.GbaDiagnostics.Event.UNSUPPORTED_SWI,
            ) { "appel logiciel 0x${number.toString(16)} non pris en charge" }
        }
    }

    /**
     * `IntrWait` / `VBlankIntrWait` : met le processeur en pause jusqu'à ce
     * qu'une interruption du [mask] demandé survienne.
     *
     * Le BIOS active `IME` (sans quoi l'attente ne pourrait jamais se terminer),
     * puis consulte ses propres drapeaux, alimentés par [onInterruptRaised].
     * Avec [discardOldFlags], les sources déjà survenues sont oubliées et seules
     * les suivantes réveillent l'appel ; sans lui, une source déjà présente rend
     * la main immédiatement.
     */
    private fun intrWait(discardOldFlags: Boolean, mask: Int) {
        bus.interrupts?.masterEnable = true
        if (mask == 0) return // aucune source attendue : rien à faire

        if (discardOldFlags) {
            clearBiosFlags(mask)
        } else if (biosFlags() and mask != 0) {
            clearBiosFlags(mask)
            return // l'interruption attendue avait déjà eu lieu
        }
        waitState = BiosWaitState(mask, discardOldFlags)
        cpu.state.halted = true
    }

    /**
     * Enregistre une source d'interruption dans les drapeaux du BIOS, comme le
     * fait son gestionnaire d'interruption. Câblé sur le contrôleur.
     */
    fun onInterruptRaised(mask: Int) {
        writeBiosFlags(biosFlags() or mask)
    }

    /**
     * Indique si le processeur en pause peut reprendre. Une attente `IntrWait`
     * ne se termine que sur une source de son masque ; une pause `Halt` simple
     * se termine sur n'importe quelle interruption activée.
     */
    fun shouldResume(): Boolean {
        val wait = waitState
        val interrupts = bus.interrupts ?: return true
        if (wait == null) {
            return interrupts.enable and interrupts.flags and 0x3FFF != 0
        }
        if (biosFlags() and wait.interruptMask == 0) return false
        clearBiosFlags(wait.interruptMask)
        waitState = null
        return true
    }

    /** Mot de drapeaux d'attente du BIOS (`0x0300_7FF8`). */
    fun biosFlags(): Int = bus.read32(BIOS_INTERRUPT_FLAGS)

    private fun writeBiosFlags(value: Int) = bus.write32(BIOS_INTERRUPT_FLAGS, value)

    private fun clearBiosFlags(mask: Int) = writeBiosFlags(biosFlags() and mask.inv())

    /**
     * `SoftReset` : redémarre la cartouche depuis son point d'entrée.
     *
     * Le saut passe par [Arm7Tdmi.branchTo] : sans cela le moteur considérerait
     * l'instruction courante comme terminée et avancerait `PC`, ce qui sauterait
     * la première instruction de la ROM.
     */
    private fun softReset() {
        cpu.state.reset()
        cpu.reset(ROM_ENTRY)
        cpu.branchTo(ROM_ENTRY)
    }

    /**
     * `RegisterRamReset` : efface uniquement les groupes désignés par [flags].
     *
     * Bits 0–4 : EWRAM, IWRAM, palette, VRAM, OAM. Bits 5–7 : registres, par
     * familles distinctes — série, audio, puis « les autres » (affichage, DMA,
     * timers, interruptions). Un groupe non demandé n'est jamais touché : un
     * effacement global écraserait `DISPCNT` ou la configuration DMA d'un jeu
     * qui ne demandait que la remise à zéro du son.
     */
    private fun registerRamReset(flags: Int) {
        if (flags and 0x01 != 0) bus.ewram.fill(0)
        // L'IWRAM haute abrite la pile du BIOS : elle est préservée.
        if (flags and 0x02 != 0) bus.iwram.fill(0, 0, bus.iwram.size - 0x200)
        if (flags and 0x04 != 0) bus.paletteRam.fill(0)
        if (flags and 0x08 != 0) bus.vram.fill(0)
        if (flags and 0x10 != 0) bus.oam.fill(0)

        if (flags and 0x20 != 0) {
            // Communication série.
            bus.io.fill(0, IO_SERIAL_START, IO_SERIAL_END)
        }
        if (flags and 0x40 != 0) {
            // Registres audio et files Direct Sound.
            bus.io.fill(0, IO_SOUND_START, IO_SOUND_END)
            bus.apu?.reset()
        }
        if (flags and 0x80 != 0) {
            // Affichage, DMA, timers, interruptions : tout le reste des E/S.
            bus.io.fill(0, 0, IO_SOUND_START)
            bus.io.fill(0, IO_SOUND_END, IO_SERIAL_START)
            bus.io.fill(0, IO_SERIAL_END, bus.io.size)
            bus.timers?.reset()
            bus.interrupts?.reset()
            bus.dma?.reset()
            // Les matrices affines reviennent à l'identité, pas à zéro : une
            // matrice nulle réduirait la couche à un point. Des jeux comptent
            // sur cette identité sans jamais écrire ces registres.
            bus.resetAffineMatrices()
        }
    }

    /** `ArcTan` : arc tangente d'une valeur en virgule fixe 1.14, en angle 16 bits. */
    private fun arcTan(value: Int): Int {
        val x = ((value shl 16) shr 16) / 16384.0
        return (kotlin.math.atan(x) * 32768.0 / kotlin.math.PI).toInt() and 0xFFFF
    }

    /** `ArcTan2` : angle du vecteur (x, y) sur un tour complet. */
    private fun arcTan2(x: Int, y: Int): Int {
        val fx = ((x shl 16) shr 16).toDouble()
        val fy = ((y shl 16) shr 16).toDouble()
        if (fx == 0.0 && fy == 0.0) return 0
        val angle = kotlin.math.atan2(fy, fx)
        val normalized = if (angle < 0) angle + 2 * kotlin.math.PI else angle
        return (normalized * 32768.0 / kotlin.math.PI).toInt() and 0xFFFF
    }

    /**
     * `BgAffineSet` : calcule les paramètres `PA`–`PD` et le point de référence
     * d'un arrière-plan affine à partir d'un centre, d'un facteur d'échelle et
     * d'un angle.
     */
    private fun bgAffineSet(source: Int, dest: Int, count: Int) {
        var src = source
        var dst = dest
        repeat(count.coerceIn(0, MAX_AFFINE_ENTRIES)) {
            val originX = bus.read32(src)
            val originY = bus.read32(src + 4)
            val screenX = (bus.read16(src + 8) shl 16) shr 16
            val screenY = (bus.read16(src + 10) shl 16) shr 16
            val scaleX = (bus.read16(src + 12) shl 16) shr 16
            val scaleY = (bus.read16(src + 14) shl 16) shr 16
            val angle = ((bus.read16(src + 16) ushr 8) and 0xFF) * 2.0 * kotlin.math.PI / 256.0
            src += 20

            val cos = kotlin.math.cos(angle)
            val sin = kotlin.math.sin(angle)
            val pa = (cos * scaleX).toInt()
            val pb = (-sin * scaleX).toInt()
            val pc = (sin * scaleY).toInt()
            val pd = (cos * scaleY).toInt()

            bus.write16(dst, pa)
            bus.write16(dst + 2, pb)
            bus.write16(dst + 4, pc)
            bus.write16(dst + 6, pd)
            bus.write32(dst + 8, originX - (pa * screenX + pb * screenY))
            bus.write32(dst + 12, originY - (pc * screenX + pd * screenY))
            dst += 16
        }
    }

    /** `ObjAffineSet` : matrice de rotation/échelle pour les sprites affines. */
    private fun objAffineSet(source: Int, dest: Int, count: Int, stride: Int) {
        var src = source
        var dst = dest
        repeat(count.coerceIn(0, MAX_AFFINE_ENTRIES)) {
            val scaleX = (bus.read16(src) shl 16) shr 16
            val scaleY = (bus.read16(src + 2) shl 16) shr 16
            val angle = ((bus.read16(src + 4) ushr 8) and 0xFF) * 2.0 * kotlin.math.PI / 256.0
            src += 8

            val cos = kotlin.math.cos(angle)
            val sin = kotlin.math.sin(angle)
            bus.write16(dst, (cos * scaleX).toInt())
            bus.write16(dst + stride, (-sin * scaleX).toInt())
            bus.write16(dst + stride * 2, (sin * scaleY).toInt())
            bus.write16(dst + stride * 3, (cos * scaleY).toInt())
            dst += stride * 4
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

        /** Mot où le BIOS accumule les interruptions survenues, pour `IntrWait`. */
        const val BIOS_INTERRUPT_FLAGS = 0x0300_7FF8

        /** Point d'entrée de la cartouche, utilisé par `SoftReset`. */
        private const val ROM_ENTRY = 0x0800_0000

        /** Garde-fou sur le nombre d'entrées traitées par les appels affines. */
        private const val MAX_AFFINE_ENTRIES = 512

        // Bornes des familles de registres, pour un effacement par groupe.
        private const val IO_SOUND_START = 0x060
        private const val IO_SOUND_END = 0x0A8
        private const val IO_SERIAL_START = 0x120
        private const val IO_SERIAL_END = 0x160
    }
}
