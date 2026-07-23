package com.ravenemu.core.gba

import com.ravenemu.core.gba.ppu.GbaPpu
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
 * **Premier lot** : le CPU exécute un sous-ensemble d'instructions ARM/Thumb et
 * le PPU produit une image 240 × 160 d'une couleur unie (arrière-plan). Les
 * entrées sont gérées ([setButton] alimente le registre `KEYINPUT`, boutons
 * `L`/`R` compris). Audio, DMA, timers, interruptions matérielles, sauvegardes
 * de cartouche et compatibilité commerciale sont différés aux lots suivants
 * (limites documentées) ; [readAudio] retourne donc 0.
 */
class GbaCore : EmulatorCore {

    override val console: ConsoleType = ConsoleType.GAME_BOY_ADVANCE

    override val video: VideoSpec = VideoSpec(
        width = GbaPpu.SCREEN_WIDTH,
        height = GbaPpu.SCREEN_HEIGHT,
        refreshRateHz = REFRESH_RATE_HZ,
    )

    override val audio: AudioSpec = AudioSpec(sampleRateHz = 32_768, channelCount = 2)

    override val framebufferFormat: FramebufferFormat = FramebufferFormat.ARGB_8888

    internal var machine: GbaMachine? = null
        private set

    private var loadedRom: ByteArray? = null

    /** SHA-256 de la ROM chargée, utilisé pour lier états et sauvegardes. */
    var romHash: ByteArray = ByteArray(0)
        private set

    override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) {
        machine = GbaMachine(rom)
        loadedRom = rom
        romHash = MessageDigest.getInstance("SHA-256").digest(rom)
    }

    override fun reset() {
        val rom = loadedRom ?: error("Aucune ROM chargée")
        machine = GbaMachine(rom)
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

    override fun readAudio(buffer: ShortArray): Int = 0

    override val hasBatteryRam: Boolean = false

    override val batteryRamDirty: Boolean = false

    override fun exportBatteryRam(): ByteArray? = null

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
