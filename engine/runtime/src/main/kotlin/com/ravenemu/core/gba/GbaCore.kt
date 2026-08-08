package com.ravenemu.core.gba

import com.ravenemu.core.gba.diag.GbaDebugSnapshot
import com.ravenemu.core.gba.diag.GbaDiagnostics
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.emulation.api.AudioSpec
import com.ravenemu.emulation.api.BatteryRamSnapshot
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.FramebufferFormat
import com.ravenemu.emulation.api.VideoSpec
import com.ravenemu.nativebridge.NativeCoreBridge
import com.ravenemu.nativebridge.NativeCoreHandle
import java.security.MessageDigest

/** Thin Kotlin adapter over RavenEmu's C++20 Game Boy Advance core. */
class GbaCore(
    forcedSaveType: GbaSaveType? = null,
) : EmulatorCore, AutoCloseable {

    var forcedSaveType: GbaSaveType? = forcedSaveType
        set(value) {
            field = value
            native?.let {
                NativeCoreBridge.setGbaForcedSaveType(
                    it.value(),
                    value?.ordinal ?: NO_FORCED_SAVE,
                )
            }
        }
    private var native: NativeCoreHandle? = null
    private var closed = false

    override val console: ConsoleType = ConsoleType.GAME_BOY_ADVANCE
    override val video: VideoSpec = VideoSpec(240, 160, REFRESH_RATE_HZ)
    override val audio: AudioSpec = AudioSpec(32_768, 2)
    override val framebufferFormat: FramebufferFormat = FramebufferFormat.ARGB_8888
    override val supportsVideoFrameSkipping: Boolean = true

    var romHash: ByteArray = ByteArray(0)
        private set

    var onDiagnosticEvent: ((GbaDiagnostics.Event, String) -> Unit)? = null

    private var measuringTimeRequested = false

    var measuringTime: Boolean
        get() = measuringTimeRequested
        set(value) {
            measuringTimeRequested = value
            native?.let { NativeCoreBridge.setMeasuringTime(it.value(), value) }
        }

    val saveType: GbaSaveType
        get() = native
            ?.let { GbaSaveType.entries[NativeCoreBridge.gbaSaveType(it.value())] }
            ?: GbaSaveType.NONE

    override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) {
        NativeCoreBridge.loadRom(handle(), rom, batteryRam)
        romHash = MessageDigest.getInstance("SHA-256").digest(rom)
        drainDiagnostics()
    }

    override fun reset() {
        NativeCoreBridge.reset(handle())
        drainDiagnostics()
    }

    override fun runFrame(framebuffer: IntArray) = runFrame(framebuffer, true)

    override fun runFrame(framebuffer: IntArray, renderVideo: Boolean) {
        NativeCoreBridge.runFrame(handle(), framebuffer, renderVideo)
        drainDiagnostics()
    }

    override fun setButton(button: EmulatorButton, pressed: Boolean) {
        NativeCoreBridge.setButton(handle(), button.ordinal, pressed)
    }

    override fun readAudio(buffer: ShortArray): Int =
        NativeCoreBridge.readAudio(handle(), buffer)

    override val hasBatteryRam: Boolean
        get() = native?.let { NativeCoreBridge.hasBatteryRam(it.value()) } ?: false

    override val batteryRamDirty: Boolean
        get() = native?.let { NativeCoreBridge.batteryRamDirty(it.value()) } ?: false

    override fun snapshotBatteryRam(): BatteryRamSnapshot? {
        val current = native ?: return null
        val snapshot = NativeCoreBridge.snapshotBatteryRam(current.value()) ?: return null
        return BatteryRamSnapshot(snapshot.data, snapshot.generation)
    }

    override fun acknowledgeBatteryRamSaved(generation: Long) {
        native?.let { NativeCoreBridge.acknowledgeBatteryRamSaved(it.value(), generation) }
    }

    override fun saveState(): ByteArray = NativeCoreBridge.saveState(handle())

    override fun loadState(state: ByteArray) {
        NativeCoreBridge.loadState(handle(), state)
        drainDiagnostics()
    }

    fun debugSnapshot(): GbaDebugSnapshot? {
        val current = native ?: return null
        val snapshot = NativeCoreBridge.debugSnapshot(current.value()) ?: return null
        val s = snapshot.scalars
        require(s.size >= SNAPSHOT_SCALAR_COUNT) { "Photographie GBA native invalide" }
        return GbaDebugSnapshot(
            instructionsPerFrame = s[0],
            programCounter = s[1],
            thumb = s[2] != 0,
            halted = s[3] != 0,
            lastSwi = s[4],
            lastInterruptMask = s[5],
            vcount = s[6],
            lastDmaChannel = s[7],
            dmaActive = s[8] != 0,
            fifoASize = s[9],
            fifoBSize = s[10],
            fifoAEmptyReads = s[11],
            fifoBEmptyReads = s[12],
            audioUnderruns = s[13],
            unsupportedSwiCount = s[14],
            undefinedInstructionCount = s[15],
            unsupportedAccessCount = s[16],
            missingInterruptCount = s[17],
            decompressionErrorCount = s[18],
            firstUnsupportedAddress = s[19],
            dispcnt = s[20],
            bg0Control = s[21],
            bg1Control = s[22],
            bg2Control = s[23],
            bg3Control = s[24],
            blendControl = s[25],
            blendAlpha = s[26],
            blendBrightness = s[27],
            windowInside = s[28],
            windowOutside = s[29],
            lumaMeasured = measuringTime,
            lumaMin = s[30],
            lumaMax = s[31],
            lumaMean = s[32],
            layerPixels = snapshot.layerPixels,
            bg2ReferenceX = s[33],
            bg2ReferenceY = s[34],
            bg2ScaleX = s[35],
            bg2ScaleY = s[36],
            bg2MatrixWrites = s[37],
            bg2ReferenceWrites = s[38],
            swiCounts = snapshot.swiCounts,
            ppuMillis = snapshot.timingsMillis.getOrElse(0) { 0.0 },
            dmaMillis = snapshot.timingsMillis.getOrElse(1) { 0.0 },
            apuMillis = snapshot.timingsMillis.getOrElse(2) { 0.0 },
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        native?.close()
    }

    private fun drainDiagnostics() {
        val current = native ?: return
        val batch = NativeCoreBridge.drainDiagnostics(current.value()) ?: return
        val listener = onDiagnosticEvent ?: return
        for (index in batch.events.indices) {
            val event = GbaDiagnostics.Event.entries.getOrNull(batch.events[index]) ?: continue
            listener(event, batch.details.getOrElse(index) { "" })
        }
    }

    private fun newHandle(saveType: GbaSaveType?): NativeCoreHandle =
        NativeCoreHandle(
            ConsoleType.GAME_BOY_ADVANCE.storageId,
            saveType?.ordinal ?: NO_FORCED_SAVE,
        ).also { handle ->
            NativeCoreBridge.setMeasuringTime(handle.value(), measuringTimeRequested)
        }

    private fun handle(): Long {
        check(!closed) { "Le cœur natif RavenEmu est fermé" }
        val current = native ?: newHandle(forcedSaveType).also { native = it }
        return current.value()
    }

    companion object {
        const val CYCLES_PER_FRAME = 280_896
        const val REFRESH_RATE_HZ = 16_777_216.0 / CYCLES_PER_FRAME
        private const val NO_FORCED_SAVE = -1
        private const val SNAPSHOT_SCALAR_COUNT = 39
    }
}
