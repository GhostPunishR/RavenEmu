package com.ravenemu.app.emulation

import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/**
 * Session d'émulation : possède le thread dédié qui cadence le moteur, le
 * framebuffer partagé et la file de commandes. Le thread d'interface ne
 * touche jamais le moteur directement : toute opération passe par [post] et
 * s'exécute sur le thread d'émulation, y compris en pause.
 *
 * Cadencement : horloge monotone, période dérivée de la fréquence native de
 * la console, avance rapide par division de période, rattrapage borné pour
 * éviter les spirales de retard.
 */
class EmulationSession(
    private val core: EmulatorCore,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        /** Trame prête (appelée depuis le thread d'émulation). */
        fun onFrame(framebuffer: IntArray)

        /** Statistiques périodiques (appelée depuis le thread d'émulation). */
        fun onStats(fps: Double)

        /** RAM de cartouche à persister (thread d'émulation). */
        fun onBatterySave(data: ByteArray)
    }

    private val framebuffer = IntArray(core.video.pixelCount)
    private val commands = ConcurrentLinkedQueue<(EmulatorCore) -> Unit>()

    @Volatile
    private var running = false

    @Volatile
    var paused = false
        private set

    @Volatile
    var fastForward = false

    @Volatile
    var speedLimitEnabled = true

    @Volatile
    var fastForwardMultiplier = 2

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "RavenEmu-Emulation").also {
            it.priority = Thread.MAX_PRIORITY - 1
            it.start()
        }
    }

    fun pause() {
        paused = true
    }

    fun resume() {
        paused = false
    }

    /** Arrête le thread après une dernière sauvegarde de la RAM cartouche. */
    fun stop() {
        if (!running) return
        running = false
        thread?.join(2000)
        thread = null
    }

    /** Exécute [action] sur le thread d'émulation (file sans verrou). */
    fun post(action: (EmulatorCore) -> Unit) {
        commands.add(action)
    }

    fun setButton(button: EmulatorButton, pressed: Boolean) {
        post { it.setButton(button, pressed) }
    }

    private fun loop() {
        val basePeriodNanos = (1_000_000_000.0 / core.video.refreshRateHz).toLong()
        var nextFrameAt = System.nanoTime()
        var fpsWindowStart = System.nanoTime()
        var fpsFrames = 0
        var lastBatteryCheck = System.nanoTime()

        while (running) {
            drainCommands()

            if (paused) {
                LockSupport.parkNanos(20_000_000)
                nextFrameAt = System.nanoTime()
                fpsWindowStart = System.nanoTime()
                fpsFrames = 0
                continue
            }

            core.runFrame(framebuffer)
            callbacks.onFrame(framebuffer)
            fpsFrames++

            val now = System.nanoTime()

            if (now - fpsWindowStart >= 1_000_000_000L) {
                val fps = fpsFrames * 1_000_000_000.0 / (now - fpsWindowStart)
                callbacks.onStats(fps)
                fpsWindowStart = now
                fpsFrames = 0
            }

            if (now - lastBatteryCheck >= BATTERY_SAVE_INTERVAL_NANOS) {
                lastBatteryCheck = now
                saveBatteryIfDirty()
            }

            val period = when {
                !speedLimitEnabled -> 0L
                fastForward -> basePeriodNanos / fastForwardMultiplier
                else -> basePeriodNanos
            }
            if (period > 0) {
                nextFrameAt += period
                val wait = nextFrameAt - System.nanoTime()
                if (wait > 0) {
                    LockSupport.parkNanos(wait)
                } else if (wait < -MAX_LAG_NANOS) {
                    // Rattrapage borné : on abandonne le retard accumulé.
                    nextFrameAt = System.nanoTime()
                }
            } else {
                nextFrameAt = System.nanoTime()
            }
        }

        drainCommands()
        saveBatteryIfDirty()
    }

    private fun drainCommands() {
        while (true) {
            val command = commands.poll() ?: return
            try {
                command(core)
            } catch (_: Exception) {
                // Une commande défaillante ne doit pas tuer la session.
            }
        }
    }

    private fun saveBatteryIfDirty() {
        if (core.hasBatteryRam && core.batteryRamDirty) {
            core.exportBatteryRam()?.let(callbacks::onBatterySave)
        }
    }

    /** Force une sauvegarde de la RAM cartouche (pause, arrière-plan…). */
    fun flushBattery() {
        post { c ->
            if (c.hasBatteryRam && c.batteryRamDirty) {
                c.exportBatteryRam()?.let(callbacks::onBatterySave)
            }
        }
    }

    companion object {
        private const val BATTERY_SAVE_INTERVAL_NANOS = 5_000_000_000L
        private const val MAX_LAG_NANOS = 100_000_000L
    }
}
