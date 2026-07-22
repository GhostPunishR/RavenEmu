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

    /**
     * DMG : niveaux monochromes `0..3` colorisés par le renderer. CGB : le
     * PPU produit directement des couleurs ARGB.
     */
    override val framebufferFormat: FramebufferFormat
        get() = if (machine?.cgbMode == true) {
            FramebufferFormat.ARGB_8888
        } else {
            FramebufferFormat.INDEXED_4
        }

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
        // On compte les cycles ramenés à l'horloge PPU : une trame vaut
        // toujours 70 224 points d'affichage, que le CPU tourne en simple ou
        // en double vitesse (auquel cas il exécute deux fois plus de cycles).
        var ppuCycles = 0
        while (ppuCycles < CYCLES_PER_FRAME) {
            val consumed = m.cpu.step()
            m.tick(consumed)
            ppuCycles += consumed shr m.speed.peripheralShift
        }
        // En DMG le framebuffer contient des niveaux 0..3 (colorisés par le
        // renderer) ; en CGB il contient déjà des couleurs ARGB.
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

        /** Fonctions couleur CGB actives si la cartouche les déclare. */
        val cgbMode: Boolean = cartridge.header.supportsCgb

        val interrupts = InterruptController()
        val timer = Timer(interrupts)
        val serial = SerialPort(interrupts)
        val joypad = Joypad(interrupts)
        val speed = SpeedController(cgbMode)
        val ppu = Ppu(interrupts, cgbMode)
        val apu = Apu()
        val bus =
            MemoryBus(cartridge, ppu, interrupts, timer, serial, joypad, apu, cgbMode, speed)
        val cpu = Cpu(bus, interrupts)

        init {
            // Les jeux détectent la Game Boy Color via A = 0x11 au démarrage.
            if (cgbMode) cpu.a = 0x11
        }

        fun tick(cpuCycles: Int) {
            // Timer et port série suivent l'horloge CPU (doublée en double
            // vitesse) ; PPU et APU restent à 4,19 MHz.
            timer.tick(cpuCycles)
            serial.tick(cpuCycles)
            val ppuCycles = cpuCycles shr speed.peripheralShift
            ppu.tick(ppuCycles)
            if (ppu.enteredHBlank) {
                bus.notifyHBlank()
                ppu.enteredHBlank = false
            }
            apu.tick(ppuCycles)
            bus.tick(cpuCycles)
            cartridge.tick(cpuCycles)
        }
    }

    companion object {
        /** 154 lignes × 456 points par trame. */
        const val CYCLES_PER_FRAME = 70224

        /** 4 194 304 / 70 224 ≈ 59,7275 Hz. */
        const val REFRESH_RATE_HZ = 4_194_304.0 / CYCLES_PER_FRAME
    }
}
