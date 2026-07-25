package com.ravenemu.core.gba

import com.ravenemu.core.gba.audio.GbaApu
import com.ravenemu.core.gba.ppu.GbaPpu
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.core.gba.state.GbaState
import com.ravenemu.emulation.api.AudioSpec
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.FramebufferFormat
import com.ravenemu.emulation.api.VideoSpec
import java.security.MessageDigest

/**
 * Moteur Game Boy Advance de RavenEmu : assemble la [GbaMachine] (CPU ARM7TDMI,
 * bus, PPU) et expose le contrat [EmulatorCore]. Mono-thread et passif :
 * l'appelant pilote la cadence via [runFrame].
 *
 * Le CPU couvre l'essentiel du jeu d'instructions ARM/Thumb, le PPU rend les
 * modes bitmap, texte et affines avec sprites, fenêtres et effets de couleur.
 * Sont également gérés : entrées ([setButton] alimente `KEYINPUT`, boutons
 * `L`/`R` compris), interruptions, timers, DMA, BIOS HLE, audio ([readAudio]
 * draine les canaux PSG et Direct Sound) et **sauvegardes de cartouche**
 * (SRAM, Flash 64/128 Kio, EEPROM) exportées au format `.sav` brut.
 *
 * La compatibilité commerciale reste à valider ; les limites connues sont
 * documentées en AD-15.
 */
class GbaCore(
    /**
     * Impose un type de mémoire de sauvegarde au lieu de la détection
     * automatique (réglage par jeu). `null` = détection.
     */
    var forcedSaveType: GbaSaveType? = null,
) : EmulatorCore {

    override val console: ConsoleType = ConsoleType.GAME_BOY_ADVANCE

    override val video: VideoSpec = VideoSpec(
        width = GbaPpu.SCREEN_WIDTH,
        height = GbaPpu.SCREEN_HEIGHT,
        refreshRateHz = REFRESH_RATE_HZ,
    )

    override val audio: AudioSpec =
        AudioSpec(sampleRateHz = GbaApu.SAMPLE_RATE_HZ, channelCount = 2)

    override val framebufferFormat: FramebufferFormat = FramebufferFormat.ARGB_8888

    internal var machine: GbaMachine? = null
        private set

    private var loadedRom: ByteArray? = null

    /** SHA-256 de la ROM chargée, utilisé pour lier états et sauvegardes. */
    var romHash: ByteArray = ByteArray(0)
        private set

    override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) {
        val newMachine = GbaMachine(rom, forcedSaveType)
        if (batteryRam != null) newMachine.cartridge.save?.import(batteryRam)
        machine = newMachine
        loadedRom = rom
        romHash = MessageDigest.getInstance("SHA-256").digest(rom)
    }

    override fun reset() {
        val rom = loadedRom ?: error("Aucune ROM chargée")
        // Power-cycle : la mémoire de sauvegarde survit, le reste repart à zéro.
        val battery = machine?.cartridge?.save?.export()
        val newMachine = GbaMachine(rom, forcedSaveType)
        if (battery != null) newMachine.cartridge.save?.import(battery)
        machine = newMachine
    }

    override fun runFrame(framebuffer: IntArray) {
        val m = machine ?: error("Aucune ROM chargée")
        require(framebuffer.size >= video.pixelCount) {
            "Framebuffer trop petit : ${framebuffer.size} < ${video.pixelCount}"
        }
        m.runFrame(CYCLES_PER_FRAME)
        System.arraycopy(m.ppu.frame, 0, framebuffer, 0, video.pixelCount)
    }

    override fun setButton(button: EmulatorButton, pressed: Boolean) {
        machine?.bus?.keypad?.setButton(button, pressed)
    }

    override fun readAudio(buffer: ShortArray): Int =
        machine?.apu?.readSamples(buffer) ?: 0

    /** Type de sauvegarde retenu pour la ROM chargée (détecté ou imposé). */
    val saveType: GbaSaveType
        get() = machine?.cartridge?.saveType ?: GbaSaveType.NONE

    override val hasBatteryRam: Boolean
        get() = machine?.cartridge?.save != null

    override val batteryRamDirty: Boolean
        get() = machine?.cartridge?.save?.dirty ?: false

    override fun exportBatteryRam(): ByteArray? {
        val save = machine?.cartridge?.save ?: return null
        val data = save.export()
        save.acknowledgeSaved()
        return data
    }

    override fun saveState(): ByteArray {
        val m = machine ?: error("Aucune ROM chargée")
        return GbaState.serialize(this, m)
    }

    override fun loadState(state: ByteArray) {
        val m = machine ?: error("Aucune ROM chargée")
        GbaState.restore(this, m, state)
    }

    companion object {
        /** 240 points × 308 + intervalles ≈ 280 896 cycles par trame. */
        const val CYCLES_PER_FRAME = 280_896

        /** 16 777 216 / 280 896 ≈ 59,7275 Hz. */
        const val REFRESH_RATE_HZ = 16_777_216.0 / CYCLES_PER_FRAME
    }
}
