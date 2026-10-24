package com.ravenemu.android.player

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.ravenemu.shared.settings.GbDisplayFilter
import com.ravenemu.shared.settings.GbFrameBlender
import com.ravenemu.shared.player.FAST_FORWARD_SPEED
import com.ravenemu.shared.player.GbVideoMetrics
import com.ravenemu.shared.player.GbVideoMetricsAccumulator
import com.ravenemu.shared.library.LoadedGameInfo
import com.ravenemu.shared.settings.Settings
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong
import com.ravenemu.android.audio.AndroidGbAudioOutput
import com.ravenemu.android.engine.AndroidGbSession
import com.ravenemu.android.retro.AchievementsController

/** How long the emulation loop sleeps between idle ticks while a barrier holds. */
private const val HOLD_TICK_NANOS = 50_000_000L

/**
 * Picture, sound and measurements of the running game. [run] drives the
 * emulation on its dedicated thread; the Compose state below is written from
 * there and read by the player screen.
 */
internal class GbPlayback(
    private val context: Context,
    private val gameSession: AndroidGbSession,
) {
    var frame by mutableStateOf<ImageBitmap?>(null)
    var videoError by mutableStateOf<String?>(null)
    var videoMetrics by mutableStateOf<GbVideoMetrics?>(null)
    var audioStatus by mutableStateOf<String?>(null)
    // Written by the emulation thread, read by the UI thread.
    @Volatile var activeAudio: AndroidGbAudioOutput? = null
        private set
    @Volatile var lastAudioReport: String = "Aucune session audio enregistrée.\n"
    /** Fast forward, written by the player screen (see GbPlayerUi). */
    @Volatile var fastForward = false
    /** Each frame is kept for rewind (the setting, outside Hardcore and the wireless cable). */
    @Volatile var rewindEnabled = false
    /** Rewind held, written by the player screen. */
    @Volatile var rewinding = false
    /** The rewind reached the oldest frame kept. */
    var rewindEnd by mutableStateOf(false)
    private val cartridgeRumble by lazy { CartridgeRumble(context) }
    private val cartridgeSensors by lazy { CartridgeSensors(context) }
    private val vsync by lazy { DisplayVsync(context) }

    /**
     * Plays [currentGame] until cancelled, then releases its audio, rumble and
     * sensors. No frame runs while [partnerPaused]: the wireless link partner
     * has its game menu open.
     */
    suspend fun run(
        currentGame: LoadedGameInfo,
        playbackSettings: Settings,
        retro: AchievementsController,
        scope: CoroutineScope,
        partnerPaused: () -> Boolean = { false },
    ) {
        videoMetrics = null

        videoError = null
        audioStatus = null
        val targetFps = (gameSession.refreshRateHz() ?: 59.7).coerceIn(1.0, 120.0)
        // Only a cartridge with sensors listens to the phone's, and only
        // while it plays: the game menu and the background stop this run.
        val sensorKinds = gameSession.cartridgeSensors()
        val sensors = if (sensorKinds != 0) cartridgeSensors else null
        val loop = EmulationLoop(currentGame, playbackSettings, retro, scope, targetFps, sensors, partnerPaused)

        try {
            sensors?.start(sensorKinds, playbackSettings)
            vsync.start()
            loop.output = openAudio(targetFps, playbackSettings)

            // The whole loop runs on the dedicated emulation thread
            // (see GbEmulationThread): no per-frame round trip to the
            // UI thread, nanosecond pacing, and late frames are caught
            // up rather than dropped, so the audio never starves.
            withContext(gbEmulationDispatcher) { loop.emulate() }
        } finally {
            vsync.stop()
            sensors?.stop()
            cartridgeRumble.stop()
            val output = loop.output
            if (activeAudio === output) activeAudio = null
            output?.let { lastAudioReport = it.exportReport() }
            output?.close()
        }
    }

    /** The audio track of the run, or null when the core or AudioTrack has none. */
    private fun openAudio(targetFps: Double, playbackSettings: Settings): AndroidGbAudioOutput? = try {
        val rate = gameSession.audioRateHz()
            ?: throw IllegalStateException("Aucune piste audio GB active.")
        AndroidGbAudioOutput(
            context, rate, targetFps,
            playbackSettings.audioEnabled, playbackSettings.audioVolume,
        ).also { output ->
            activeAudio = output
            lastAudioReport = "Session audio démarrée; diagnostic en cours.\n"
            audioStatus = "Audio GB optimisé (préversion)"
        }
    } catch (error: Exception) {
        audioStatus = "Audio indisponible : ${error.message ?: "Erreur AudioTrack"}"
        null
    }

    /** One run of a game on the emulation thread: its buffers, pacing and audio. */
    private inner class EmulationLoop(
        private val game: LoadedGameInfo,
        private val settings: Settings,
        private val retro: AchievementsController,
        private val scope: CoroutineScope,
        targetFps: Double,
        private val sensors: CartridgeSensors?,
        private val partnerPaused: () -> Boolean,
    ) {
        private val periodNanos = (1_000_000_000.0 / targetFps).roundToLong()
        private val stats = GbVideoMetricsAccumulator(targetFps)
        private val pixels = IntArray(game.width * game.height)
        // More than one GB frame of interleaved stereo, reused per frame.
        private val samples = ShortArray(4096)
        private val images = GbFrameBitmaps(game.width, game.height)
        private val blender = GbFrameBlender()
        /** Dropped when the track fails; whatever remains is closed after the run. */
        var output: AndroidGbAudioOutput? = null
        private var rumble: CartridgeRumble? = null
        private var lastBatteryCheckNanos = 0L
        private var retroFailureLogged = false
        private var lastSampleCount = 0

        suspend fun emulate() {
            rumble = if (settings.cartridgeRumble) cartridgeRumble else null
            val pacer = GbFramePacer(periodNanos)
            pacer.reset(System.nanoTime())
            lastBatteryCheckNanos = System.nanoTime()
            var held = false
            while (currentCoroutineContext().isActive) {
                // Hardcore barrier: no frame before RetroAchievements knows
                // the game. Idle ticks carry the sign-in and identification.
                if (retro.framesOnHold) {
                    held = true
                    try {
                        retro.idle()
                    } catch (error: Exception) {
                        if (!retroFailureLogged) Log.w("RavenEmuRetro", "rcheevos idle tick failed", error)
                        retroFailureLogged = true
                    }
                    LockSupport.parkNanos(HOLD_TICK_NANOS)
                    continue
                }
                // The wireless link partner's game menu is open; the link
                // still answers it meanwhile (see WirelessLink).
                if (partnerPaused()) {
                    held = true
                    LockSupport.parkNanos(HOLD_TICK_NANOS)
                    continue
                }
                if (held) {
                    // The wait is not lateness: no catch-up burst after it.
                    held = false
                    pacer.reset(System.nanoTime())
                }
                try {
                    // A late frame is still emulated and heard;
                    // only its image is skipped (see GbFramePacer).
                    val renderVideo = pacer.shouldRender(System.nanoTime())
                    if (rewinding && rewindEnabled) {
                        stepBack(renderVideo)
                    } else {
                        if (rewindEnd) rewindEnd = false
                        // Fast forward runs several frames in one period. Only the
                        // last is shown and heard: the sound keeps its pitch and
                        // its pace, in pieces, and the audio queue stays fed.
                        val frames = if (fastForward) FAST_FORWARD_SPEED else 1
                        var running = true
                        for (index in 1..frames) {
                            val last = index == frames
                            running = step(renderVideo && last, sound = last)
                            if (!running) break
                        }
                        if (!running) break
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    videoError = error.message ?: "Erreur de rendu Game Boy."
                    frame = null
                    break
                }

                // On the display's beat when it allows (see DisplayCadence);
                // the sound follows the game's speed.
                val wait = pacer.nextWaitNanos(System.nanoTime(), vsync.timeNanos, vsync.periodNanos)
                output?.speed = pacer.speed
                if (wait > 0) LockSupport.parkNanos(wait)
            }
        }

        /**
         * Emulates one frame, plays its sound when [sound] and shows it when
         * [renderVideo]; false once the core stops.
         */
        private suspend fun step(renderVideo: Boolean, sound: Boolean = true): Boolean {
            val nativeStart = System.nanoTime()
            val sampleCount =
                gameSession.nextFrameWithAudioInto(pixels, samples, sensors, keepForRewind = rewindEnabled)
                    ?: return false
            val nativeElapsed = System.nanoTime() - nativeStart
            lastSampleCount = sampleCount
            if (sound) submitAudio(sampleCount)
            rumble?.let { if (gameSession.rumbleActive) it.pulse() else it.release() }
            val conversionNanos = if (renderVideo) showFrame() else 0L

            // The official SDK evaluates each emulated frame
            // using observational reads, on its dedicated
            // thread. Network requests never block this call.
            try {
                retro.frame()
            } catch (error: Exception) {
                // The controller tells the player and leaves Hardcore; one log per run.
                if (!retroFailureLogged) Log.w("RavenEmuRetro", "rcheevos frame unavailable", error)
                retroFailureLogged = true
            }
            val now = System.nanoTime()
            autosaveBattery(now)
            recordMetrics(now, nativeElapsed, conversionNanos)
            return true
        }

        /**
         * One frame back, shown, in silence: the audio queue stays fed. At the
         * oldest frame kept, the picture stays. RetroAchievements does not count
         * rewound frames; its network keeps going.
         */
        private suspend fun stepBack(renderVideo: Boolean) {
            val count = gameSession.rewindFrameInto(pixels, samples)
            if (count == null) {
                if (!rewindEnd) rewindEnd = true
                samples.fill(0, 0, lastSampleCount)
                submitAudio(lastSampleCount)
            } else {
                if (rewindEnd) rewindEnd = false
                submitAudio(count)
                if (renderVideo) showFrame()
            }
            rumble?.release()
            try {
                retro.idle()
            } catch (error: Exception) {
                if (!retroFailureLogged) Log.w("RavenEmuRetro", "rcheevos idle tick failed", error)
                retroFailureLogged = true
            }
        }

        /** One non-blocking audio submission per frame; a failing track is dropped. */
        private fun submitAudio(sampleCount: Int) {
            val sink = output ?: return
            try {
                sink.submit(samples, sampleCount)
            } catch (error: Exception) {
                sink.stopNow()
                audioStatus = "Audio interrompu : ${error.message ?: "Sortie audio interrompue"}"
                if (activeAudio === sink) activeAudio = null
                lastAudioReport = sink.exportReport()
                sink.close()
                output = null
            }
        }

        /** Publishes the picture; returns the time its conversion took. */
        private fun showFrame(): Long {
            val conversionStart = System.nanoTime()
            GbDisplayFilter.apply(pixels, settings, game.platform)
            blender.apply(pixels, settings.frameBlending)
            frame = images.publish(pixels)
            return System.nanoTime() - conversionStart
        }

        private fun autosaveBattery(now: Long) {
            if (now - lastBatteryCheckNanos < 5_000_000_000L) return
            lastBatteryCheckNanos = now
            scope.launch(Dispatchers.IO) {
                try {
                    gameSession.saveBatteryIfDirty()
                } catch (error: Exception) {
                    Log.e("RavenEmuSaves", "Battery autosave failed", error)
                }
            }
        }

        private fun recordMetrics(now: Long, nativeElapsed: Long, conversionNanos: Long) {
            val sample = stats.record(now, nativeElapsed, conversionNanos) ?: return
            videoMetrics = sample
            output?.let {
                it.recordVideo(sample.actualFps, sample.coreMs, sample.conversionMs)
                lastAudioReport = it.exportReport()
                Log.d("RavenEmuAudio", it.diagnostics())
            }
        }
    }
}
