package com.ravenemu.android

import com.ravenemu.shared.GamePlatform

/**
 * JNI adapter over RavenCore's pinned portable C ABI.
 * Own one instance per running game and call it from a single emulation thread.
 * No ROMs or BIOS files are bundled with the APK.
 *
 * Video, PCM16 audio and touch input are wired on Android RavenEmu.
 * The application host owns real-time pacing and audio output.
 */
class NativeCore(system: Int) : AutoCloseable {
    private var pointer: Long = nativeCreate(system).also {
        check(it != 0L) { nativeLastError() }
    }
    val width: Int get() = nativeWidth(requireOpen())
    val height: Int get() = nativeHeight(requireOpen())
    val fps: Double get() = nativeFps(requireOpen())
    val audioRate: Int get() = nativeAudioRate(requireOpen())

    fun load(rom: ByteArray, battery: ByteArray? = null) {
        check(nativeLoad(requireOpen(), rom, battery)) { nativeLastError() }
    }

    /** Fills a caller-owned ARGB8888 framebuffer without reallocating it. */
    fun frameInto(pixels: IntArray) {
        require(pixels.size == width * height) { "Invalid framebuffer dimensions" }
        check(nativeFrame(requireOpen(), pixels)) { nativeLastError() }
    }

    /** Convenience method for callers that need a new buffer. */
    fun frame(): IntArray = IntArray(width * height).also(::frameInto)

    /** Reuse a caller-provided PCM16 stereo sample buffer. */
    fun readAudioInto(output: ShortArray): Int {
        val count = nativeReadAudio(requireOpen(), output)
        check(count in 0..output.size) { "Invalid audio sample count from native bridge" }
        return count
    }

    fun readAudio(maxSamples: Int = 4096): ShortArray {
        require(maxSamples >= 0)
        val buffer = ShortArray(maxSamples)
        return buffer.copyOf(readAudioInto(buffer))
    }

    fun button(button: Int, pressed: Boolean) {
        check(nativeButton(requireOpen(), button, pressed)) { nativeLastError() }
    }

    fun reset() {
        check(nativeReset(requireOpen())) { nativeLastError() }
    }

    fun saveState(): ByteArray {
        val data = ByteArray(nativeStateSize(requireOpen()))
        check(data.isNotEmpty() && nativeSaveState(requireOpen(), data)) { nativeLastError() }
        return data
    }

    fun loadState(data: ByteArray) {
        check(nativeLoadState(requireOpen(), data)) { nativeLastError() }
    }

    /** Only observational RA memory access; call on the session's native lock. */
    fun readAchievementMemory(address: Int, output: ByteArray): Int {
        require(address >= 0 && output.size <= 0x58000)
        return nativeAchievementMemory(requireOpen(), address, output).also {
            check(it in 0..output.size) { "Invalid achievement memory read" }
        }
    }

    fun batterySnapshot(): ByteArray {
        val size = nativeBatterySize(requireOpen())
        if (size == 0) return ByteArray(0)
        return ByteArray(size).also {
            check(nativeCopyBattery(requireOpen(), it)) { nativeLastError() }
        }
    }

    data class BatterySnapshot(val bytes: ByteArray, val generation: Long)

    /**
     * True while the cartridge's rumble motor runs (GB MBC5 Rumble, GBA Drill
     * Dozer and WarioWare: Twisted!). Sample it after each frame.
     */
    fun rumbleActive(): Boolean = nativeRumbleActive(requireOpen())

    /** Only copy when the core reports changed battery-backed storage. */
    fun batteryDirty(): Boolean = nativeBatteryDirty(requireOpen())

    fun batterySnapshotWithGeneration(): BatterySnapshot? {
        val size = nativeBatterySize(requireOpen())
        if (size <= 0) return null
        val buffer = ByteArray(size)
        val generation = LongArray(1)
        check(nativeCopyBatteryGeneration(requireOpen(), buffer, generation)) { nativeLastError() }
        return BatterySnapshot(buffer, generation[0])
    }

    /** Acknowledge only after the host has committed the data to disk. */
    fun acknowledgeBattery(generation: Long) {
        check(nativeAcknowledgeBattery(requireOpen(), generation)) { nativeLastError() }
    }

    private fun requireOpen(): Long = pointer.also {
        check(it != 0L) { "Native emulator is closed" }
    }

    override fun close() {
        if (pointer != 0L) {
            nativeDestroy(pointer)
            pointer = 0L
        }
    }

    private external fun nativeCreate(system: Int): Long
    private external fun nativeDestroy(pointer: Long)
    private external fun nativeLoad(pointer: Long, rom: ByteArray, battery: ByteArray?): Boolean
    private external fun nativeWidth(pointer: Long): Int
    private external fun nativeHeight(pointer: Long): Int
    private external fun nativeFps(pointer: Long): Double
    private external fun nativeAudioRate(pointer: Long): Int
    private external fun nativeFrame(pointer: Long, output: IntArray): Boolean
    private external fun nativeReadAudio(pointer: Long, output: ShortArray): Int
    private external fun nativeButton(pointer: Long, button: Int, pressed: Boolean): Boolean
    private external fun nativeReset(pointer: Long): Boolean
    private external fun nativeAchievementMemory(
        pointer: Long, address: Int, output: ByteArray,
    ): Int
    private external fun nativeStateSize(pointer: Long): Int
    private external fun nativeSaveState(pointer: Long, output: ByteArray): Boolean
    private external fun nativeLoadState(pointer: Long, input: ByteArray): Boolean
    private external fun nativeRumbleActive(pointer: Long): Boolean
    private external fun nativeBatteryDirty(pointer: Long): Boolean
    private external fun nativeCopyBatteryGeneration(
        pointer: Long, output: ByteArray, generation: LongArray,
    ): Boolean
    private external fun nativeAcknowledgeBattery(pointer: Long, generation: Long): Boolean
    private external fun nativeBatterySize(pointer: Long): Int
    private external fun nativeCopyBattery(pointer: Long, output: ByteArray): Boolean
    private external fun nativeLastError(): String

    companion object {
        const val GB = 0
        const val GBC = 1
        const val GBA = 2

        fun systemFor(platform: GamePlatform): Int = when (platform) {
            GamePlatform.GB -> GB
            GamePlatform.GBC -> GBC
            GamePlatform.GBA -> GBA
        }

        init { System.loadLibrary("ravenemu_android") }
    }
}
