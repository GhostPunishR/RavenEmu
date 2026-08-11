package com.ravenemu.core.gb

import com.ravenemu.emulation.api.AudioSpec
import com.ravenemu.emulation.api.BatteryRamSnapshot
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.FramebufferFormat
import com.ravenemu.emulation.api.VideoSpec
import com.ravenemu.emulation.cheats.CheatCapableCore
import com.ravenemu.emulation.cheats.CheatCode
import com.ravenemu.emulation.cheats.CheatFormat
import com.ravenemu.emulation.cheats.CheatSupport
import com.ravenemu.nativebridge.NativeCoreBridge
import com.ravenemu.nativebridge.NativeCoreHandle
import java.security.MessageDigest

/** Thin Kotlin adapter over RavenEmu's C++20 Game Boy/Game Boy Color core. */
class GameBoyCore(
    private val clock: () -> Long = SYSTEM_CLOCK,
) : EmulatorCore, CheatCapableCore, AutoCloseable {

    private val native = lazy(LazyThreadSafetyMode.NONE) {
        NativeCoreHandle(ConsoleType.GAME_BOY.storageId, NO_FORCED_SAVE)
    }
    private var closed = false
    private val usesCustomClock = clock !== SYSTEM_CLOCK

    override val console: ConsoleType = ConsoleType.GAME_BOY
    override val video: VideoSpec = VideoSpec(160, 144, REFRESH_RATE_HZ)
    override val audio: AudioSpec = AudioSpec(32_768, 2)
    override val framebufferFormat: FramebufferFormat
        get() = if (native.isInitialized()) {
            FramebufferFormat.entries[NativeCoreBridge.framebufferFormat(handle())]
        } else {
            FramebufferFormat.INDEXED_4
        }

    override val cheatSupport: CheatSupport
        get() {
            if (!native.isInitialized()) return CheatSupport(emptySet())
            val formats = NativeCoreBridge.supportedCheatFormats(handle()).map { id ->
                requireNotNull(CheatFormat.fromStorageId(id)) {
                    "Format de cheat natif inconnu : $id"
                }
            }.toSet()
            return CheatSupport(formats)
        }

    var romHash: ByteArray = ByteArray(0)
        private set

    override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) {
        syncClock()
        NativeCoreBridge.loadRom(handle(), rom, batteryRam)
        romHash = MessageDigest.getInstance("SHA-256").digest(rom)
    }

    override fun reset() {
        syncClock()
        NativeCoreBridge.reset(handle())
    }

    override fun runFrame(framebuffer: IntArray) {
        syncClock()
        NativeCoreBridge.runFrame(handle(), framebuffer, true)
    }

    override fun runFrame(framebuffer: IntArray, renderVideo: Boolean) {
        syncClock()
        NativeCoreBridge.runFrame(handle(), framebuffer, renderVideo)
    }

    override fun setButton(button: EmulatorButton, pressed: Boolean) {
        NativeCoreBridge.setButton(handle(), button.ordinal, pressed)
    }

    override fun replaceActiveCheats(codes: List<CheatCode>) {
        NativeCoreBridge.replaceActiveCheats(
            handle(),
            codes.map { it.format.storageId }.toIntArray(),
            codes.map(CheatCode::normalized).toTypedArray(),
        )
    }

    override fun readAudio(buffer: ShortArray): Int =
        NativeCoreBridge.readAudio(handle(), buffer)

    override val rumbleActive: Boolean
        get() = native.takeIf { it.isInitialized() }
            ?.let { NativeCoreBridge.rumbleActive(it.value.value()) }
            ?: false

    override val hasBatteryRam: Boolean
        get() = native.takeIf { it.isInitialized() }
            ?.let { NativeCoreBridge.hasBatteryRam(it.value.value()) }
            ?: false

    override val batteryRamDirty: Boolean
        get() = native.takeIf { it.isInitialized() }
            ?.let { NativeCoreBridge.batteryRamDirty(it.value.value()) }
            ?: false

    override fun snapshotBatteryRam(): BatteryRamSnapshot? {
        syncClock()
        if (!native.isInitialized()) return null
        val snapshot = NativeCoreBridge.snapshotBatteryRam(handle()) ?: return null
        return BatteryRamSnapshot(snapshot.data, snapshot.generation)
    }

    override fun acknowledgeBatteryRamSaved(generation: Long) {
        if (native.isInitialized()) {
            NativeCoreBridge.acknowledgeBatteryRamSaved(handle(), generation)
        }
    }

    override fun saveState(): ByteArray {
        syncClock()
        return NativeCoreBridge.saveState(handle())
    }

    override fun loadState(state: ByteArray) {
        syncClock()
        NativeCoreBridge.loadState(handle(), state)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (native.isInitialized()) native.value.close()
    }

    private fun handle(): Long {
        check(!closed) { "Le cœur natif RavenEmu est fermé" }
        return native.value.value()
    }

    private fun syncClock() {
        if (usesCustomClock) {
            NativeCoreBridge.setClockEpoch(handle(), true, clock())
        }
    }

    companion object {
        const val CYCLES_PER_FRAME = 70_224
        const val REFRESH_RATE_HZ = 4_194_304.0 / CYCLES_PER_FRAME
        private const val NO_FORCED_SAVE = -1
        private val SYSTEM_CLOCK: () -> Long = { System.currentTimeMillis() / 1_000 }
    }
}
