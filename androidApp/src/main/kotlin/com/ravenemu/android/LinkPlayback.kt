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
 * Pictures and sound of two linked games (see [AndroidLinkSession]). [run]
 * drives both on the dedicated emulation thread, paced like [GbPlayback];
 * the Compose state below is written from there and read by the screen.
 */
internal class LinkPlayback(
    private val context: Context,
    private val session: AndroidLinkSession,
) {
    var firstFrame by mutableStateOf<ImageBitmap?>(null)
    var secondFrame by mutableStateOf<ImageBitmap?>(null)
    var error by mutableStateOf<String?>(null)
    var metrics by mutableStateOf<GbVideoMetrics?>(null)
    @Volatile var activeAudio: AndroidGbAudioOutput? = null
        private set

    fun clear() {
        firstFrame = null
        secondFrame = null
        metrics = null
    }

    /** Plays the linked pair until cancelled, then releases the audio track. */
    suspend fun run(games: List<LoadedGameInfo>, settings: Settings, scope: CoroutineScope) {
        require(games.size == 2)
        error = null
        clear()
        val (first, second) = games
        val targetFps = (session.refreshRateHz() ?: 59.7).coerceIn(1.0, 120.0)
        val periodNanos = (1_000_000_000.0 / targetFps).roundToLong()
        val stats = GbVideoMetricsAccumulator(targetFps)
        val firstPixels = IntArray(first.width * first.height)
        val secondPixels = IntArray(second.width * second.height)
        val samples = ShortArray(4096)
        val discarded = ShortArray(4096)
        val firstImages = GbFrameBitmaps(first.width, first.height)
        val secondImages = GbFrameBitmaps(second.width, second.height)
        var output: AndroidGbAudioOutput? = null
        try {
            try {
                val rate = session.audioRateHz() ?: throw IllegalStateException("Aucune piste audio active.")
                output = AndroidGbAudioOutput(
                    context, rate, targetFps, settings.audioEnabled, settings.audioVolume,
                )
                activeAudio = output
            } catch (audioError: Exception) {
                Log.w(TAG, "Linked audio unavailable", audioError)
            }
            withContext(gbEmulationDispatcher) {
                val pacer = GbFramePacer(periodNanos)
                pacer.reset(System.nanoTime())
                var lastBatteryCheckNanos = System.nanoTime()
                while (isActive) {
                    try {
                        val renderVideo = pacer.shouldRender(System.nanoTime())
                        val nativeStart = System.nanoTime()
                        val sampleCount =
                            session.nextFramesInto(firstPixels, secondPixels, samples, discarded) ?: break
                        val nativeElapsed = System.nanoTime() - nativeStart
                        val sink = output
                        if (sink != null) {
                            try {
                                sink.submit(samples, sampleCount)
                            } catch (audioError: Exception) {
                                Log.w(TAG, "Linked audio interrupted", audioError)
                                sink.stopNow()
                                if (activeAudio === sink) activeAudio = null
                                sink.close()
                                output = null
                            }
                        }
                        var conversionNanos = 0L
                        if (renderVideo) {
                            val conversionStart = System.nanoTime()
                            GbDisplayFilter.apply(firstPixels, settings, first.platform)
                            GbDisplayFilter.apply(secondPixels, settings, second.platform)
                            firstFrame = firstImages.publish(firstPixels)
                            secondFrame = secondImages.publish(secondPixels)
                            conversionNanos = System.nanoTime() - conversionStart
                        }
                        val now = System.nanoTime()
                        if (now - lastBatteryCheckNanos >= 5_000_000_000L) {
                            lastBatteryCheckNanos = now
                            scope.launch(Dispatchers.IO) {
                                try {
                                    session.saveBatteriesIfDirty()
                                } catch (saveError: Exception) {
                                    Log.e("RavenEmuSaves", "Linked battery autosave failed", saveError)
                                }
                            }
                        }
                        stats.record(now, nativeElapsed, conversionNanos)?.let { metrics = it }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (frameError: Exception) {
                        error = frameError.message ?: "Erreur du câble link."
                        clear()
                        break
                    }
                    val wait = pacer.nextWaitNanos(System.nanoTime())
                    if (wait > 0) LockSupport.parkNanos(wait)
                }
            }
        } finally {
            if (activeAudio === output) activeAudio = null
            output?.close()
        }
    }

    private companion object {
        const val TAG = "RavenEmuLink"
    }
}
