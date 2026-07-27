package com.ravenemu.emulation.api.session

import com.ravenemu.emulation.api.AudioSpec
import com.ravenemu.emulation.api.BatteryRamSnapshot
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.FramebufferFormat
import com.ravenemu.emulation.api.VideoSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cycle d'arrêt de la session, éprouvé **sans Android** grâce à une sortie
 * audio contrôlable.
 *
 * Le défaut que ces tests verrouillent est un plantage natif : libérer la
 * sortie audio alors que le thread d'émulation peut encore y écrire. Il ne se
 * produit que sur une course, donc jamais de façon reproductible sur appareil —
 * d'où une fausse sortie audio qui bloque exactement quand on le lui demande.
 */
class EmulationSessionStopTest {

    // ---- Doublures ----

    /** Moteur minimal : une trame coûte quelques microsecondes, rien de plus. */
    private class FakeCore(
        private val batterySize: Int = 0,
    ) : EmulatorCore {
        override val console = ConsoleType.GAME_BOY
        override val video = VideoSpec(width = 4, height = 4, refreshRateHz = 60.0)
        override val audio = AudioSpec(sampleRateHz = 32768, channelCount = 2)
        override val framebufferFormat = FramebufferFormat.INDEXED_4
        val frames = AtomicInteger()

        /** Contenu de la « RAM de cartouche », modifiable depuis le test. */
        var generation = 0L
        var acknowledged = -1L
        var dirtyFlag = false

        override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) = Unit
        override fun reset() = Unit
        override fun runFrame(framebuffer: IntArray) {
            frames.incrementAndGet()
        }

        override fun setButton(button: EmulatorButton, pressed: Boolean) = Unit
        override fun readAudio(buffer: ShortArray): Int = 64
        override val hasBatteryRam: Boolean get() = batterySize > 0
        override val batteryRamDirty: Boolean get() = dirtyFlag
        override fun snapshotBatteryRam(): BatteryRamSnapshot? =
            if (batterySize == 0) null else BatteryRamSnapshot(ByteArray(batterySize), generation)

        override fun acknowledgeBatteryRamSaved(generation: Long) {
            acknowledged = generation
            if (generation == this.generation) dirtyFlag = false
        }

        override fun saveState(): ByteArray = ByteArray(0)
        override fun loadState(state: ByteArray) = Unit
    }

    /** Sortie audio dont on décide si et quand [write] se débloque. */
    private class FakeSink : EmulationSession.AudioSink {
        val released = AtomicBoolean(false)
        val writing = CountDownLatch(1)
        val unblocked = AtomicBoolean(false)
        var blockWrites = false
        var throwOnWrite = false
        private val gate = Object()

        /** Écritures survenues **après** la libération : c'est le crash guetté. */
        val writesAfterRelease = AtomicInteger()

        /** Écritures en cours à cet instant. */
        private val inFlight = AtomicInteger()

        /**
         * Vrai si [release] est survenue alors qu'une écriture était en vol.
         *
         * C'est exactement l'état qui plante en production : le thread
         * d'émulation est à l'intérieur de l'`AudioTrack` pendant qu'on le
         * libère. Le symptôme ne se voit pas dans les compteurs d'écriture — le
         * thread reste bloqué et n'écrit plus rien — d'où ce témoin dédié.
         */
        val releasedDuringWrite = AtomicBoolean(false)

        /** Si vrai, [unblock] ne débloque rien : le thread reste coincé. */
        var unblockIsIneffective = false

        override fun write(samples: ShortArray, count: Int) {
            if (released.get()) writesAfterRelease.incrementAndGet()
            if (throwOnWrite) throw IllegalStateException("sortie audio en panne")
            if (!blockWrites) return
            inFlight.incrementAndGet()
            try {
                writing.countDown()
                synchronized(gate) {
                    while (!unblocked.get()) gate.wait(50)
                }
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun setVolume(volume: Float) = Unit
        override fun pause() = Unit

        override fun unblock() {
            if (unblockIsIneffective) return
            unblocked.set(true)
            synchronized(gate) { gate.notifyAll() }
        }

        override fun release() {
            if (inFlight.get() > 0) releasedDuringWrite.set(true)
            released.set(true)
        }
    }

    private open class RecordingCallbacks : EmulationSession.Callbacks {
        val saves = AtomicInteger()
        val audioFailures = AtomicInteger()
        var saveSucceeds = true
        override fun onFrame(framebuffer: IntArray) = Unit
        override fun onStats(fps: Double, frameTimeMs: Double) = Unit
        override fun onBatterySave(data: ByteArray): Boolean {
            saves.incrementAndGet()
            return saveSucceeds
        }

        override fun onAudioFailure(error: Exception) {
            audioFailures.incrementAndGet()
        }
    }

    // ---- Tests ----

    /**
     * Le cas central : l'arrêt survient pendant une écriture audio bloquante.
     * La sortie ne doit être libérée qu'après la terminaison du thread, et
     * aucune écriture ne doit avoir lieu après.
     */
    @Test
    fun `un arret pendant une ecriture audio bloquante reste sûr`() {
        val sink = FakeSink().apply { blockWrites = true }
        val session = EmulationSession(FakeCore(), RecordingCallbacks(), sink)
        session.start()
        assertTrue(sink.writing.await(5, TimeUnit.SECONDS), "l'écriture doit se bloquer")

        val result = session.stop()

        assertEquals(EmulationSession.StopResult.CLEAN, result)
        assertTrue(sink.released.get(), "la sortie doit être libérée après la jointure")
        assertFalse(
            sink.releasedDuringWrite.get(),
            "la sortie ne doit jamais être libérée pendant une écriture en cours",
        )
        assertEquals(0, sink.writesAfterRelease.get(), "aucune écriture après libération")
    }

    /**
     * Si le thread refuse de rendre la main, la sortie audio ne doit **pas**
     * être libérée et l'appelant doit l'apprendre. Une ressource retenue vaut
     * mieux qu'un plantage natif, mais le silence ne vaut rien.
     */
    @Test
    fun `un thread qui ne rend pas la main est signale sans liberer l'audio`() {
        val sink = FakeSink().apply { blockWrites = true; unblockIsIneffective = true }
        val session = EmulationSession(FakeCore(), RecordingCallbacks(), sink)
        session.start()
        assertTrue(sink.writing.await(5, TimeUnit.SECONDS))

        val result = session.stop(timeoutMillis = 200)

        assertEquals(EmulationSession.StopResult.TIMED_OUT, result)
        assertFalse(sink.released.get(), "libérer sous un thread vivant plante le processus")

        // Le thread est encore en vie : on le libère pour ne pas fuir.
        sink.unblockIsIneffective = false
        sink.unblock()
    }

    /** Sans blocage, l'arrêt est propre et la sortie libérée. */
    @Test
    fun `un arret nominal libere la sortie audio`() {
        val sink = FakeSink()
        val core = FakeCore()
        val session = EmulationSession(core, RecordingCallbacks(), sink)
        session.start()
        while (core.frames.get() < 2) Thread.sleep(5)

        assertEquals(EmulationSession.StopResult.CLEAN, session.stop())
        assertTrue(sink.released.get())
    }

    /** Un second arrêt ne doit rien retoucher, ni relibérer la sortie. */
    @Test
    fun `les arrets repetes sont sans effet`() {
        val sink = FakeSink()
        val session = EmulationSession(FakeCore(), RecordingCallbacks(), sink)
        session.start()

        assertEquals(EmulationSession.StopResult.CLEAN, session.stop())
        assertEquals(EmulationSession.StopResult.NOT_RUNNING, session.stop())
        assertEquals(EmulationSession.StopResult.NOT_RUNNING, session.stop())
    }

    /** Arrêter une session jamais démarrée est licite. */
    @Test
    fun `arreter une session non demarree est sans effet`() {
        val sink = FakeSink()
        assertEquals(
            EmulationSession.StopResult.NOT_RUNNING,
            EmulationSession(FakeCore(), RecordingCallbacks(), sink).stop(),
        )
        assertFalse(sink.released.get())
    }

    /** Une session en pause doit s'arrêter aussi vite qu'une session active. */
    @Test
    fun `un arret pendant une pause aboutit`() {
        val sink = FakeSink()
        val session = EmulationSession(FakeCore(), RecordingCallbacks(), sink)
        session.start()
        session.pause()
        Thread.sleep(30)

        assertEquals(EmulationSession.StopResult.CLEAN, session.stop())
        assertTrue(sink.released.get())
    }

    /** L'arrêt tente une dernière sauvegarde et l'acquitte si elle aboutit. */
    @Test
    fun `l'arret sauvegarde une RAM modifiee`() {
        val core = FakeCore(batterySize = 32).apply { dirtyFlag = true; generation = 7 }
        val callbacks = RecordingCallbacks()
        val session = EmulationSession(core, callbacks, FakeSink())
        session.start()
        session.stop()

        assertTrue(callbacks.saves.get() >= 1, "une sauvegarde finale est attendue")
        assertEquals(7L, core.acknowledged)
        assertFalse(core.batteryRamDirty)
    }

    /** Si l'écriture échoue à l'arrêt, rien n'est acquitté. */
    @Test
    fun `une sauvegarde finale ratee n'est pas acquittee`() {
        val core = FakeCore(batterySize = 32).apply { dirtyFlag = true; generation = 3 }
        val callbacks = RecordingCallbacks().apply { saveSucceeds = false }
        val session = EmulationSession(core, callbacks, FakeSink())
        session.start()
        session.stop()

        assertTrue(callbacks.saves.get() >= 1)
        assertEquals(-1L, core.acknowledged, "aucun acquittement sans écriture confirmée")
        assertTrue(core.batteryRamDirty, "la sauvegarde reste à refaire")
    }

    /** Une sortie audio qui lève ne doit pas emporter la session. */
    @Test
    fun `une exception de la sortie audio ne tue pas la session`() {
        val sink = FakeSink().apply { throwOnWrite = true }
        val core = FakeCore()
        val callbacks = RecordingCallbacks()
        val session = EmulationSession(core, callbacks, sink)
        session.start()
        while (core.frames.get() < 3) Thread.sleep(5)

        assertTrue(callbacks.audioFailures.get() >= 1, "la panne doit être signalée")
        assertEquals(EmulationSession.StopResult.CLEAN, session.stop())
    }

    /** Sans RAM persistante, l'arrêt ne sollicite aucune sauvegarde. */
    @Test
    fun `sans RAM persistante l'arret ne sauvegarde rien`() {
        val callbacks = RecordingCallbacks()
        val session = EmulationSession(FakeCore(), callbacks, FakeSink())
        session.start()
        session.stop()
        assertEquals(0, callbacks.saves.get())
    }
}
