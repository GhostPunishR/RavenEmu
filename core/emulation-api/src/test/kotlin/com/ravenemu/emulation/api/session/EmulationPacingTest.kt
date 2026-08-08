package com.ravenemu.emulation.api.session

import com.ravenemu.emulation.api.AudioSpec
import com.ravenemu.emulation.api.BatteryRamSnapshot
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.FramebufferFormat
import com.ravenemu.emulation.api.VideoSpec
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cadence de la session : celle de la console, sans exception.
 *
 * RavenEmu exposait une avance rapide (multiplicateur de 2 à 4) et un
 * interrupteur « limiter la vitesse » qui, désactivé, laissait la session
 * tourner aussi vite que l'appareil le permettait. Les deux ont été retirés :
 * une partie accélérée n'est plus la console qu'on prétend reproduire, et le
 * son en porte la marque le premier.
 *
 * Ces tests verrouillent la décision des deux côtés — ce que la session
 * **fait**, et ce qu'elle **offre**. Le second compte autant : un réglage
 * retiré de l'interface mais laissé dans le cœur finit par être rebranché.
 */
class EmulationPacingTest {

    /** Moteur qui ne coûte rien : seule la cadence de la session est mesurée. */
    private class IdleCore(private val hz: Double) : EmulatorCore {
        override val console = ConsoleType.GAME_BOY
        override val video = VideoSpec(width = 4, height = 4, refreshRateHz = hz)
        override val audio = AudioSpec(sampleRateHz = 32768, channelCount = 2)
        override val framebufferFormat = FramebufferFormat.INDEXED_4
        val frames = AtomicInteger()

        override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) = Unit
        override fun reset() = Unit
        override fun runFrame(framebuffer: IntArray) {
            frames.incrementAndGet()
        }

        override fun setButton(button: EmulatorButton, pressed: Boolean) = Unit
        override fun readAudio(buffer: ShortArray): Int = 0
        override val hasBatteryRam = false
        override val batteryRamDirty = false
        override fun snapshotBatteryRam(): BatteryRamSnapshot? = null
        override fun acknowledgeBatteryRamSaved(generation: Long) = Unit
        override fun saveState(): ByteArray = ByteArray(0)
        override fun loadState(state: ByteArray) = Unit
    }

    private class SilentCallbacks : EmulationSession.Callbacks {
        override fun onFrame(framebuffer: IntArray) = Unit
        override fun onStats(fps: Double, frameTimeMs: Double) = Unit
        override fun onBatterySave(data: ByteArray): Boolean = true
    }

    @Test
    fun `la session ne depasse pas la cadence de la console`() {
        // Sans plafond, un moteur qui ne fait rien produirait des dizaines de
        // milliers de trames dans cette fenêtre. La borne haute est large :
        // elle vise à distinguer « cadencé » de « débridé », pas à mesurer une
        // gigue de quelques trames sur une machine d'intégration chargée.
        val hz = 60.0
        val fenetreMs = 600L
        val core = IdleCore(hz)
        val session = EmulationSession(core, SilentCallbacks())

        session.start()
        Thread.sleep(fenetreMs)
        val produites = core.frames.get()
        session.stop()

        val attendues = hz * fenetreMs / 1000.0
        assertTrue(
            produites <= attendues * 3,
            "Session débridée : $produites trames en $fenetreMs ms, " +
                "pour ${attendues.toInt()} attendues à $hz Hz",
        )
        assertTrue(
            produites > 0,
            "La session n'a produit aucune trame : la mesure ne prouverait rien",
        )
    }

    @Test
    fun `une console plus lente est cadencee plus lentement`() {
        // Contrepartie du test précédent : il ne suffit pas d'être plafonné,
        // encore faut-il l'être à la cadence de la console émulée. Un plafond
        // constant passerait le premier test et échouerait ici.
        val fenetreMs = 600L

        fun trames(hz: Double): Int {
            val core = IdleCore(hz)
            val session = EmulationSession(core, SilentCallbacks())
            session.start()
            Thread.sleep(fenetreMs)
            val n = core.frames.get()
            session.stop()
            return n
        }

        val lente = trames(10.0)
        val rapide = trames(60.0)
        assertTrue(
            rapide > lente * 2,
            "La cadence ne suit pas la console : $lente trames à 10 Hz " +
                "contre $rapide à 60 Hz",
        )
    }

    @Test
    fun `la session n'offre aucun moyen d'accelerer l'emulation`() {
        // Garde-fou sur l'API elle-même. Un réglage retiré de l'interface mais
        // laissé dans le cœur se rebranche en une ligne ; ce test le refuse.
        val interdits = setOf("fastForward", "fastForwardMultiplier", "speedLimitEnabled")
        val exposes = EmulationSession::class.java.methods
            .map { it.name }
            .filter { nom ->
                interdits.any { nom.equals(it, ignoreCase = true) ||
                    nom.equals("get$it", ignoreCase = true) ||
                    nom.equals("set$it", ignoreCase = true) }
            }
        assertEquals(
            emptyList(),
            exposes,
            "L'accélération de l'émulation a été réintroduite dans la session",
        )
    }
}
