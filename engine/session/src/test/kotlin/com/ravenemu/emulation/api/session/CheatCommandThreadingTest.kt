package com.ravenemu.emulation.api.session

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CheatCommandThreadingTest {
    @Test
    fun `une liste de cheats est remplacee sur le thread de session meme en pause`() {
        val core = FakeCheatCore()
        val emulationThread = AtomicReference<Thread>()
        val session = EmulationSession(
            core = core,
            callbacks = SilentCallbacks,
            onThreadStart = { emulationThread.set(Thread.currentThread()) },
        )
        val uiThread = Thread.currentThread()
        val code = CheatCode(CheatFormat.GAMESHARK_GB_GBC, "002A00A0")

        session.start()
        try {
            session.pause()
            session.post { loadedCore ->
                (loadedCore as CheatCapableCore).replaceActiveCheats(listOf(code))
            }

            assertTrue(core.applied.await(2, TimeUnit.SECONDS), "commande de cheat non exécutée")
            assertEquals(listOf(code), core.activeCodes.get())
            assertNotSame(uiThread, core.mutationThread.get())
            assertSame(emulationThread.get(), core.mutationThread.get())
        } finally {
            assertEquals(EmulationSession.StopResult.CLEAN, session.stop())
        }
    }

    private class FakeCheatCore : EmulatorCore, CheatCapableCore {
        override val console = ConsoleType.GAME_BOY
        override val video = VideoSpec(4, 4, 60.0)
        override val audio = AudioSpec(32_768, 2)
        override val framebufferFormat = FramebufferFormat.INDEXED_4
        override val cheatSupport = CheatSupport(setOf(CheatFormat.GAMESHARK_GB_GBC))
        val activeCodes = AtomicReference<List<CheatCode>>(emptyList())
        val mutationThread = AtomicReference<Thread>()
        val applied = CountDownLatch(1)

        override fun replaceActiveCheats(codes: List<CheatCode>) {
            activeCodes.set(codes)
            mutationThread.set(Thread.currentThread())
            applied.countDown()
        }

        override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) = Unit
        override fun reset() = Unit
        override fun runFrame(framebuffer: IntArray) = Unit
        override fun setButton(button: EmulatorButton, pressed: Boolean) = Unit
        override fun readAudio(buffer: ShortArray): Int = 0
        override val hasBatteryRam = false
        override val batteryRamDirty = false
        override fun snapshotBatteryRam(): BatteryRamSnapshot? = null
        override fun acknowledgeBatteryRamSaved(generation: Long) = Unit
        override fun saveState(): ByteArray = ByteArray(0)
        override fun loadState(state: ByteArray) = Unit
    }

    private object SilentCallbacks : EmulationSession.Callbacks {
        override fun onFrame(framebuffer: IntArray) = Unit
        override fun onStats(fps: Double, frameTimeMs: Double) = Unit
        override fun onBatterySave(data: ByteArray): Boolean = true
    }
}
