package com.ravenemu.core.gba

import com.ravenemu.core.gba.audio.GbaApu
import com.ravenemu.core.gba.diag.GbaDebugSnapshot
import com.ravenemu.core.gba.diag.GbaDiagnostics
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
 * draine les canaux PSG et Direct Sound), **sauvegardes de cartouche**
 * (SRAM, Flash 64/128 Kio, EEPROM) exportées au format `.sav` brut, et
 * **horloge temps réel** de cartouche pour les jeux qui en embarquent une.
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

    override val supportsVideoFrameSkipping: Boolean = true

    internal var machine: GbaMachine? = null
        private set

    private var loadedRom: ByteArray? = null

    /** SHA-256 de la ROM chargée, utilisé pour lier états et sauvegardes. */
    var romHash: ByteArray = ByteArray(0)
        private set

    override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) {
        val newMachine = GbaMachine(rom, forcedSaveType)
        if (batteryRam != null) newMachine.cartridge.save?.import(batteryRam)
        newMachine.diagnostics.onEvent = onDiagnosticEvent
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
        newMachine.diagnostics.onEvent = onDiagnosticEvent
        machine = newMachine
    }

    override fun runFrame(framebuffer: IntArray) {
        runFrame(framebuffer, renderVideo = true)
    }

    override fun runFrame(framebuffer: IntArray, renderVideo: Boolean) {
        val m = machine ?: error("Aucune ROM chargée")
        require(framebuffer.size >= video.pixelCount) {
            "Framebuffer trop petit : ${framebuffer.size} < ${video.pixelCount}"
        }
        m.ppu.renderEnabled = renderVideo
        try {
            m.runFrame(CYCLES_PER_FRAME)
        } finally {
            // Une demande de saut ne doit jamais survivre à la trame courante.
            m.ppu.renderEnabled = true
        }
        if (renderVideo) {
            System.arraycopy(m.ppu.frame, 0, framebuffer, 0, video.pixelCount)
        }
    }

    override fun setButton(button: EmulatorButton, pressed: Boolean) {
        machine?.bus?.keypad?.setButton(button, pressed)
    }

    override fun readAudio(buffer: ShortArray): Int =
        machine?.apu?.readSamples(buffer) ?: 0

    /**
     * Journal des anomalies du moteur. Branché par la couche applicative en
     * **Debug uniquement** ; laissé à `null`, aucun message n'est produit et
     * aucune chaîne n'est même construite.
     */
    var onDiagnosticEvent: ((GbaDiagnostics.Event, String) -> Unit)? = null
        set(value) {
            field = value
            // Le rappel survit à un redémarrage : la machine est reconstruite,
            // pas l'intention de journaliser.
            machine?.diagnostics?.onEvent = value
        }

    /**
     * Chronométrage des sous-systèmes. À n'activer que pour mesurer : chaque
     * relevé coûte deux lectures d'horloge, et le but est justement de ne pas
     * fausser ce qu'on mesure.
     */
    var measuringTime: Boolean
        get() = machine?.diagnostics?.measuringTime ?: false
        set(value) {
            val m = machine ?: return
            m.diagnostics.measuringTime = value
            // Le rappel de l'unité audio n'est branché que pendant la mesure :
            // laissé en place, il coûterait deux lectures d'horloge par lot,
            // même en Release.
            m.apu.onBatchNanos =
                if (value) { nanos -> m.diagnostics.addApuNanos(nanos) } else null
            // Même raison pour le comptage des pixels par couche : un incrément
            // par pixel dessiné n'a rien à faire hors d'une session de mesure.
            m.ppu.collectLayerStats = value
        }

    /** Étend le signe d'une valeur 28 bits (`BG2X`/`BG2Y`, format 20.8). */
    private fun signed28(value: Int): Int = (value shl 4) shr 4

    /**
     * Photographie de l'état du moteur, pour une surcouche de débogage. Retourne
     * `null` si aucune ROM n'est chargée.
     */
    fun debugSnapshot(): GbaDebugSnapshot? {
        val m = machine ?: return null
        val diag = m.diagnostics
        return GbaDebugSnapshot(
            instructionsPerFrame = diag.instructionsLastFrame,
            programCounter = m.cpu.state.regs[15],
            thumb = m.cpu.state.thumb,
            halted = m.cpu.state.halted,
            lastSwi = diag.lastSwi,
            lastInterruptMask = diag.lastInterruptMask,
            vcount = m.ppu.vcount,
            lastDmaChannel = m.dma.lastChannel,
            dmaActive = m.dma.isActive,
            fifoASize = m.apu.fifoSize(0),
            fifoBSize = m.apu.fifoSize(1),
            fifoAEmptyReads = m.apu.fifoEmptyReads(0),
            fifoBEmptyReads = m.apu.fifoEmptyReads(1),
            audioUnderruns = m.apu.underruns,
            unsupportedSwiCount = diag.count(GbaDiagnostics.Event.UNSUPPORTED_SWI),
            undefinedInstructionCount =
                diag.count(GbaDiagnostics.Event.UNDEFINED_INSTRUCTION),
            unsupportedAccessCount = diag.count(GbaDiagnostics.Event.UNSUPPORTED_ACCESS),
            missingInterruptCount = diag.count(GbaDiagnostics.Event.MISSING_INTERRUPT),
            decompressionErrorCount =
                diag.count(GbaDiagnostics.Event.DECOMPRESSION_ERROR),
            firstUnsupportedAddress = diag.firstUnsupportedAddress,
            dispcnt = m.bus.read16(IO_BASE),
            bg0Control = m.bus.read16(IO_BASE + 0x08),
            bg1Control = m.bus.read16(IO_BASE + 0x0A),
            bg2Control = m.bus.read16(IO_BASE + 0x0C),
            bg3Control = m.bus.read16(IO_BASE + 0x0E),
            blendControl = m.bus.read16(IO_BASE + 0x50),
            layerPixels = m.ppu.layerPixels,
            bg2ReferenceX = signed28(m.bus.read32(IO_BASE + 0x28)) shr 8,
            bg2ReferenceY = signed28(m.bus.read32(IO_BASE + 0x2C)) shr 8,
            bg2ScaleX = m.bus.read16(IO_BASE + 0x20),
            bg2ScaleY = m.bus.read16(IO_BASE + 0x26),
            bg2MatrixWrites = diag.bg2MatrixWrites,
            bg2ReferenceWrites = diag.bg2ReferenceWrites,
            swiCounts = IntArray(GbaDiagnostics.SWI_RANGE) { diag.swiCount(it) },
            ppuMillis = diag.ppuNanosLastFrame / 1_000_000.0,
            dmaMillis = diag.dmaNanosLastFrame / 1_000_000.0,
            apuMillis = diag.apuNanosLastFrame / 1_000_000.0,
        )
    }

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
        /** Base des registres d'E/S, pour la lecture des états d'affichage. */
        private const val IO_BASE = 0x0400_0000

        /** 240 points × 308 + intervalles ≈ 280 896 cycles par trame. */
        const val CYCLES_PER_FRAME = 280_896

        /** 16 777 216 / 280 896 ≈ 59,7275 Hz. */
        const val REFRESH_RATE_HZ = 16_777_216.0 / CYCLES_PER_FRAME
    }
}
