package com.ravenemu.core.gb

import com.ravenemu.core.gb.cartridge.Cartridge
import com.ravenemu.core.gb.cpu.Cpu
import com.ravenemu.core.gb.io.Apu
import com.ravenemu.core.gb.io.Joypad
import com.ravenemu.core.gb.io.SerialPort
import com.ravenemu.core.gb.io.Timer
import com.ravenemu.core.gb.memory.MemoryBus
import com.ravenemu.core.gb.ppu.Ppu
import com.ravenemu.emulation.api.AudioSpec
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.FramebufferFormat
import com.ravenemu.emulation.api.VideoSpec
import java.security.MessageDigest

/**
 * Moteur Game Boy DMG de RavenEmu : assemble CPU, bus, PPU, timers, joypad,
 * cartouche, et expose le contrat [EmulatorCore]. Mono-thread et passif :
 * l'appelant pilote la cadence en appelant [runFrame].
 *
 * L'émulation démarre à l'état post-boot ROM (aucun BIOS n'est requis ni
 * accepté). L'APU synthétise les 4 canaux en continu ; [readAudio] draine les
 * échantillons stéréo PCM 16 bits produits depuis le dernier appel.
 *
 * Le moteur ne produit **aucune couleur** : [runFrame] écrit les quatre
 * niveaux logiques `0..3` de l'écran monochrome (0 = plus clair, 3 = plus
 * sombre). La colorisation est appliquée par le renderer via un profil
 * d'écran, indépendant de l'état d'émulation.
 */
class GameBoyCore(
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) : EmulatorCore {

    override val console: ConsoleType = ConsoleType.GAME_BOY

    override val video: VideoSpec = VideoSpec(
        width = Ppu.SCREEN_WIDTH,
        height = Ppu.SCREEN_HEIGHT,
        refreshRateHz = REFRESH_RATE_HZ,
    )

    override val audio: AudioSpec =
        AudioSpec(sampleRateHz = Apu.SAMPLE_RATE_HZ, channelCount = 2)

    /** La Game Boy émet des niveaux monochromes, colorisés par le renderer. */
    override val framebufferFormat: FramebufferFormat = FramebufferFormat.INDEXED_4

    internal var machine: Machine? = null
        private set

    private var loadedRom: ByteArray? = null

    /** SHA-256 de la ROM chargée, utilisé pour lier états et sauvegardes. */
    var romHash: ByteArray = ByteArray(0)
        private set

    override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) {
        val newMachine = Machine(rom, clock)
        if (batteryRam != null) newMachine.cartridge.importBattery(batteryRam)
        machine = newMachine
        loadedRom = rom
        romHash = MessageDigest.getInstance("SHA-256").digest(rom)
    }

    override fun reset() {
        val rom = loadedRom ?: error("Aucune ROM chargée")
        // Power-cycle : la RAM à pile survit, le reste repart de zéro.
        val battery = machine?.cartridge?.exportBattery()
        val newMachine = Machine(rom, clock)
        if (battery != null) newMachine.cartridge.importBattery(battery)
        machine = newMachine
    }

    override fun runFrame(framebuffer: IntArray) {
        val m = machine ?: error("Aucune ROM chargée")
        require(framebuffer.size >= video.pixelCount) {
            "Framebuffer trop petit : ${framebuffer.size} < ${video.pixelCount}"
        }
        var cycles = 0
        while (cycles < CYCLES_PER_FRAME) {
            val consumed = m.cpu.step()
            m.tick(consumed)
            cycles += consumed
        }
        // Le PPU produit déjà les niveaux 0..3 (registres BGP/OBP appliqués) ;
        // on les recopie tels quels, sans colorisation.
        System.arraycopy(m.ppu.completedFrame, 0, framebuffer, 0, video.pixelCount)
    }

    override fun setButton(button: EmulatorButton, pressed: Boolean) {
        machine?.joypad?.setButton(button, pressed)
    }

    override fun readAudio(buffer: ShortArray): Int =
        machine?.apu?.readSamples(buffer) ?: 0

    override val hasBatteryRam: Boolean
        get() = machine?.cartridge?.let {
            it.header.hasBattery && (it.ram.isNotEmpty() || it.header.hasRtc)
        } ?: false

    override val batteryRamDirty: Boolean
        get() = machine?.cartridge?.ramDirty ?: false

    override fun exportBatteryRam(): ByteArray? {
        val cartridge = machine?.cartridge ?: return null
        val data = cartridge.exportBattery()
        cartridge.acknowledgeRamSaved()
        return data
    }

    override fun saveState(): ByteArray {
        val m = machine ?: error("Aucune ROM chargée")
        return GameBoyState.serialize(this, m)
    }

    override fun loadState(state: ByteArray) {
        val m = machine ?: error("Aucune ROM chargée")
        GameBoyState.restore(this, m, state)
    }

    /** Machine complète reconstruite à chaque chargement de ROM. */
    internal class Machine(rom: ByteArray, clock: () -> Long) {
        val cartridge: Cartridge = Cartridge.create(rom, clock)
        val interrupts = InterruptController()
        val timer = Timer(interrupts)
        val serial = SerialPort(interrupts)
        val joypad = Joypad(interrupts)
        val ppu = Ppu(interrupts)
        val apu = Apu()
        val bus = MemoryBus(cartridge, ppu, interrupts, timer, serial, joypad, apu)
        val cpu = Cpu(bus, interrupts)

        fun tick(cycles: Int) {
            timer.tick(cycles)
            serial.tick(cycles)
            ppu.tick(cycles)
            apu.tick(cycles)
            bus.tick(cycles)
            cartridge.tick(cycles)
        }
    }

    companion object {
        /** 154 lignes × 456 points par trame. */
        const val CYCLES_PER_FRAME = 70224

        /** 4 194 304 / 70 224 ≈ 59,7275 Hz. */
        const val REFRESH_RATE_HZ = 4_194_304.0 / CYCLES_PER_FRAME
    }
}
