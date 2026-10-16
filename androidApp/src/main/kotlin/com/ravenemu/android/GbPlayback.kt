package com.ravenemu.android

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.ravenemu.shared.GbDisplayFilter
import com.ravenemu.shared.GbVideoMetrics
import com.ravenemu.shared.GbVideoMetricsAccumulator
import com.ravenemu.shared.LoadedGameInfo
import com.ravenemu.shared.Settings
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

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
    private val cartridgeRumble by lazy { CartridgeRumble(context) }
    private val cartridgeSensors by lazy { CartridgeSensors(context) }

    /** Plays [currentGame] until cancelled, then releases its audio, rumble and sensors. */
    suspend fun run(
        currentGame: LoadedGameInfo,
        playbackSettings: Settings,
        retro: AchievementsController,
        scope: CoroutineScope,
    ) {
        videoMetrics = null

        videoError = null
        audioStatus = null
        val targetFps = (gameSession.refreshRateHz() ?: 59.7).coerceIn(1.0, 120.0)
        val periodNanos = (1_000_000_000.0 / targetFps).roundToLong()
        val stats = GbVideoMetricsAccumulator(targetFps)
        val pixels = IntArray(currentGame.width * currentGame.height)
        // More than one GB frame of interleaved stereo, reused per frame.
        val samples = ShortArray(4096)
        val images = GbFrameBitmaps(currentGame.width, currentGame.height)
        var output: AndroidGbAudioOutput? = null
        // Only a cartridge with sensors listens to the phone's, and only
        // while it plays: the game menu and the background stop this run.
        val sensorKinds = gameSession.cartridgeSensors()
        val sensors = if (sensorKinds != 0) cartridgeSensors else null

        try {
            sensors?.start(sensorKinds, playbackSettings)
            try {
                val rate = gameSession.audioRateHz()
                    ?: throw IllegalStateException("Aucune piste audio GB active.")
                output = AndroidGbAudioOutput(
                    context, rate, targetFps,
                    playbackSettings.audioEnabled, playbackSettings.audioVolume,
                )
                activeAudio = output
                lastAudioReport = "Session audio démarrée; diagnostic en cours.\n"
                audioStatus = "Audio GB optimisé (préversion)"
            } catch (error: Exception) {
                audioStatus = "Audio indisponible : ${error.message ?: "Erreur AudioTrack"}"
            }

            // The whole loop runs on the dedicated emulation thread
            // (see GbEmulationThread): no per-frame round trip to the
            // UI thread, nanosecond pacing, and late frames are caught
            // up rather than dropped, so the audio never starves.
            withContext(gbEmulationDispatcher) {
                val rumble = if (playbackSettings.cartridgeRumble) cartridgeRumble else null
                val pacer = GbFramePacer(periodNanos)
                pacer.reset(System.nanoTime())
                var lastBatteryCheckNanos = System.nanoTime()
                while (isActive) {
                    try {
                        // A late frame is still emulated and heard;
                        // only its image is skipped (see GbFramePacer).
                        val renderVideo = pacer.shouldRender(System.nanoTime())
                        val nativeStart = System.nanoTime()
                        val sampleCount =
                            gameSession.nextFrameWithAudioInto(pixels, samples, sensors) ?: break
                        val nativeElapsed = System.nanoTime() - nativeStart
                        // One non-blocking audio submission per frame.
                        val sink = output
                        if (sink != null) {
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
                        if (rumble != null) {
                            if (gameSession.rumbleActive) rumble.pulse() else rumble.release()
                        }
                        var conversionNanos = 0L
                        if (renderVideo) {
                            val conversionStart = System.nanoTime()
                            GbDisplayFilter.apply(
                                pixels, playbackSettings, currentGame.platform,
                            )
                            frame = images.publish(pixels)
                            conversionNanos = System.nanoTime() - conversionStart
                        }

                        // The official SDK evaluates each emulated frame
                        // using observational reads, on its dedicated
                        // thread. Network requests never block this call.
                        try {
                            retro.frame()
                        } catch (error: Exception) {
                            Log.w("RavenEmuRetro", "rcheevos frame unavailable", error)
                        }
                        val now = System.nanoTime()
                        if (now - lastBatteryCheckNanos >= 5_000_000_000L) {
                            lastBatteryCheckNanos = now
                            scope.launch(Dispatchers.IO) {
                                try {
                                    gameSession.saveBatteryIfDirty()
                                } catch (error: Exception) {
                                    Log.e("RavenEmuSaves", "Battery autosave failed", error)
                                }
                            }
                        }
                        val sample = stats.record(now, nativeElapsed, conversionNanos)
                        if (sample != null) {
                            videoMetrics = sample
                            output?.let {
                                it.recordVideo(sample.actualFps, sample.coreMs, sample.conversionMs)
                                lastAudioReport = it.exportReport()
                                Log.d("RavenEmuAudio", it.diagnostics())
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        videoError = error.message ?: "Erreur de rendu Game Boy."
                        frame = null
                        break
                    }

                    val wait = pacer.nextWaitNanos(System.nanoTime())
                    if (wait > 0) LockSupport.parkNanos(wait)
                }
            }
        } finally {
            sensors?.stop()
            cartridgeRumble.stop()
            if (activeAudio === output) activeAudio = null
            output?.let { lastAudioReport = it.exportReport() }
            output?.close()
        }
    }
}
