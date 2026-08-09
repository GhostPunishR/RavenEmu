package com.ravenemu.emulation.api.session

import com.ravenemu.emulation.api.AudioSpec
import com.ravenemu.emulation.api.BatteryRamSnapshot
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.VideoSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RumbleSinkTest {
    @Test
    fun `la session relaie et coupe le rumble`() {
        val core = FakeCore()
        val on = CountDownLatch(1)
        val off = CountDownLatch(1)
        val sink = object : EmulationSession.RumbleSink {
            @Volatile var currentState = false
            override fun setActive(active: Boolean) {
                currentState = active
                if (active) on.countDown() else off.countDown()
            }
        }
        val session = EmulationSession(core, NoopCallbacks, rumbleSink = sink)
        session.start()
        core.rumble = true
        assertTrue(on.await(1, TimeUnit.SECONDS), "le rumble actif n'a pas été relayé")
        assertTrue(sink.currentState)
        session.pause()
        assertFalse(sink.currentState)
        session.stop()
        assertTrue(off.await(1, TimeUnit.SECONDS))
    }

    private class FakeCore : EmulatorCore {
        @Volatile var rumble = false
        override val console = ConsoleType.GAME_BOY
        override val video = VideoSpec(1, 1, 120.0)
        override val audio = AudioSpec(32_768, 2)
        override val rumbleActive get() = rumble
        override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) = Unit
        override fun reset() = Unit
        override fun runFrame(framebuffer: IntArray) { framebuffer[0] = 0 }
        override fun setButton(button: EmulatorButton, pressed: Boolean) = Unit
        override fun readAudio(buffer: ShortArray) = 0
        override val hasBatteryRam = false
        override val batteryRamDirty = false
        override fun snapshotBatteryRam(): BatteryRamSnapshot? = null
        override fun acknowledgeBatteryRamSaved(generation: Long) = Unit
        override fun saveState() = byteArrayOf()
        override fun loadState(state: ByteArray) = Unit
    }

    private object NoopCallbacks : EmulationSession.Callbacks {
        override fun onFrame(framebuffer: IntArray) = Unit
        override fun onStats(fps: Double, frameTimeMs: Double) = Unit
        override fun onBatterySave(data: ByteArray) = true
    }
}
