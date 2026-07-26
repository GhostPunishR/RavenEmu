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
    private val audioSink: AudioSink? = null,
) {
    interface Callbacks {
        /** Trame prête (appelée depuis le thread d'émulation). */
        fun onFrame(framebuffer: IntArray)

        /**
         * Statistiques périodiques (appelée depuis le thread d'émulation).
         *
         * [frameTimeMs] est le temps moyen **de calcul** d'une trame, cadencement
         * exclu : c'est lui qui dit s'il reste de la marge, là où [fps] plafonne à
         * la fréquence de la console dès que le moteur suit.
         */
        fun onStats(fps: Double, frameTimeMs: Double)

        /** RAM de cartouche à persister (thread d'émulation). */
        fun onBatterySave(data: ByteArray)
    }

    /**
     * Sortie audio de la plateforme. [write] doit être bloquante : c'est elle
     * qui cadence la session quand l'audio est actif (synchronisation A/V).
     * [targetDurationNanos] indique la durée réelle que le bloc doit couvrir.
     */
    interface AudioSink {
        fun write(samples: ShortArray, count: Int, targetDurationNanos: Long)
        fun setVolume(volume: Float)
        fun pause()
        fun release()
    }

    private val framebuffer = IntArray(core.video.pixelCount)
    private val audioBuffer = ShortArray(8192)
    private val commands = ConcurrentLinkedQueue<(EmulatorCore) -> Unit>()

    /** Audio actif (paramètre utilisateur), modifiable à chaud. */
    @Volatile
    var audioEnabled = true

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
        // La priorité qui compte réellement est demandée depuis le thread
        // lui-même, au début de [loop].
    }

    fun pause() {
        paused = true
        audioSink?.pause()
    }

    fun resume() {
        paused = false
    }

    /** Volume audio 0..1, appliqué immédiatement. */
    fun setAudioVolume(volume: Float) {
        audioSink?.setVolume(volume)
    }

    /** Arrête le thread après une dernière sauvegarde de la RAM cartouche. */
    fun stop() {
        if (!running) return
        running = false
        thread?.join(2000)
        thread = null
        audioSink?.release()
    }

    /** Exécute [action] sur le thread d'émulation (file sans verrou). */
    fun post(action: (EmulatorCore) -> Unit) {
        commands.add(action)
    }

    fun setButton(button: EmulatorButton, pressed: Boolean) {
        post { it.setButton(button, pressed) }
    }

    private fun loop() {
        // `Thread.priority` ne fait que positionner une valeur de politesse Unix.
        // Android place les threads dans des groupes d'ordonnancement, et c'est ce
        // groupe qui décide, sur un processeur hétérogène, si le thread tourne sur
        // un cœur puissant ou sur un cœur économe. Un émulateur a besoin du
        // premier : on le demande explicitement.
        android.os.Process.setThreadPriority(
            android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY,
        )
        val basePeriodNanos = (1_000_000_000.0 / core.video.refreshRateHz).toLong()
        var nextFrameAt = System.nanoTime()
        var fpsWindowStart = System.nanoTime()
        var fpsFrames = 0
        var workNanos = 0L
        var lastBatteryCheck = System.nanoTime()

        while (running) {
            drainCommands()

            if (paused) {
                LockSupport.parkNanos(20_000_000)
                nextFrameAt = System.nanoTime()
                fpsWindowStart = System.nanoTime()
                fpsFrames = 0
                workNanos = 0
                continue
            }

            // Temps de calcul brut d'une trame : mesuré autour de l'émulation
            // seule, sans le cadencement ni l'attente audio.
            val workStart = System.nanoTime()
            core.runFrame(framebuffer)
            val frameWorkNanos = System.nanoTime() - workStart
            workNanos += frameWorkNanos

            // Audio d'abord : l'écriture bloquante cadence la session et la
            // file audio est réalimentée avant de payer le coût du rendu
            // vidéo. Hors conditions nominales (avance rapide, audio coupé,
            // vitesse débridée), les échantillons sont drainés puis
            // abandonnés et le cadencement par horloge reprend la main.
            val audioCount = core.readAudio(audioBuffer)
            val audioPaced = audioSink != null && audioEnabled && audioCount > 0 &&
                speedLimitEnabled && !fastForward
            if (audioPaced) {
                // Si le moteur est plus lent que la console, un bloc natif ne
                // couvre pas le temps écoulé et AudioTrack finit par manquer de
                // données. La sortie étire alors le bloc jusqu'au temps de calcul.
                val targetAudioNanos = maxOf(basePeriodNanos, frameWorkNanos)
                audioSink!!.write(audioBuffer, audioCount, targetAudioNanos)
            }

            callbacks.onFrame(framebuffer)
            fpsFrames++

            val now = System.nanoTime()

            if (now - fpsWindowStart >= 1_000_000_000L) {
                val fps = fpsFrames * 1_000_000_000.0 / (now - fpsWindowStart)
                val frameTimeMs = workNanos / 1_000_000.0 / fpsFrames
                callbacks.onStats(fps, frameTimeMs)
                fpsWindowStart = now
                fpsFrames = 0
                workNanos = 0
            }

            if (now - lastBatteryCheck >= BATTERY_SAVE_INTERVAL_NANOS) {
                lastBatteryCheck = now
                saveBatteryIfDirty()
            }

            val period = when {
                audioPaced -> 0L // l'écriture audio bloquante a déjà cadencé
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
