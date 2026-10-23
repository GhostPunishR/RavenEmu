package com.ravenemu.android.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.ravenemu.shared.GbPcmQueue
import java.util.Locale
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Audio output based on RavenEmu 0.8.1's proven transport.
 *
 * Convert the native GB stereo PCM16 stream with a band-limited resampler to
 * the phone's preferred rate (usually 48kHz). Keep two frames queued before
 * starting playback and gently adapt to the device audio clock.
 *
 * Unlike the RavenEmu 0.8.1 sink, the RavenEmu preview keeps non-blocking AudioTrack writes
 * so a temporary device stall does not freeze the video coroutine. Pending
 * samples are bounded and always kept as complete stereo pairs.
 */
class AndroidGbAudioOutput(
    context: Context,
    sampleRateHz: Int,
    videoRateHz: Double,
    soundEnabled: Boolean = true,
    volumePercent: Int = 100,
) : AutoCloseable {
    private val lock = Any()
    private val outputRate = resolveNativeRate(context)
    private val resampler = BandLimitedResampler(sampleRateHz, outputRate)
    private val outputSamplesPerFrame: Int
    private val primer: AudioBufferPrimer
    private val governor: AudioClockGovernor
    private val queue: GbPcmQueue
    private val scratch = ShortArray(4096)
    private var resampled = ShortArray(4096)
    private val track: AudioTrack

    private var writtenFrames = 0L
    private var droppedSamples = 0L
    private var startupBlocks = 0
    private var firstSoundRecorded = false
    private var firstNativeSoundRecorded = false
    private var firstNonzeroBlock = -1
    private var stoppedDiagnostics: String? = null
    private var lastLoggedSecond = -1L
    private val startNanos = System.nanoTime()
    private val report = StringBuilder("RavenEmu RavenEmu GB audio startup diagnostic\n")
    private val fadeFrames = (outputRate * 180 / 1000).coerceAtLeast(1) // smooth 180 ms startup fade after silence
    private var rampFrame = 0
    private var closed = false
    // The unlock chime rides on the game's own samples: same track, volume and pauses.
    private val effects = SoundMixer()
    private val chime by lazy { unlockChime(outputRate) }

    init {
        require(sampleRateHz in 8000..192000) { "Fréquence audio native invalide." }
        require(videoRateHz > 0.0 && videoRateHz.isFinite()) { "Cadence GB invalide." }
        outputSamplesPerFrame = ceil(outputRate / videoRateHz).toInt() * 2
        primer = AudioBufferPrimer(outputSamplesPerFrame * PRIME_VIDEO_FRAMES)
        governor = AudioClockGovernor(outputSamplesPerFrame / 2 * PRIME_VIDEO_FRAMES)
        queue = GbPcmQueue(maxOf(outputRate / 2, 8192).let { if (it % 2 == 0) it else it + 1 })

        val minBuffer = AudioTrack.getMinBufferSize(
            outputRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "Sortie stéréo PCM16 indisponible." }
        val bufferBytes = maxOf(
            minBuffer,
            outputSamplesPerFrame * BYTES_PER_SAMPLE * BUFFER_VIDEO_FRAMES,
        )

        val candidate = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (candidate.state != AudioTrack.STATE_INITIALIZED) {
            candidate.release()
            throw IllegalStateException("Impossible d'initialiser AudioTrack.")
        }
        track = candidate
        // Output gain only: do not change the clock, priming, resampler or
        // 180 ms start fade validated on the phone.
        track.setVolume(if (soundEnabled) volumePercent.coerceIn(0, 100) / 100f else 0f)
    }

    /** Submit samples on the emulation worker after each RavenGB frame. */
    fun submit(samples: ShortArray, count: Int) {
        synchronized(lock) {
            if (closed) return
            require(count in 0..samples.size && count % CHANNELS == 0) {
                "Échantillons PCM stéréo invalides."
            }
            if (!firstNativeSoundRecorded && (0 until count).any { samples[it].toInt() != 0 }) {
                firstNativeSoundRecorded = true
                record("first_nonzero_native block=$startupBlocks")
                logPcm("first_nonzero_native", samples, count)
            }
            if (startupBlocks < 12 ||
                (firstNonzeroBlock >= 0 && startupBlocks <= firstNonzeroBlock + 20)
            ) logPcm("native", samples, count)
            val needed = resampler.maxOutput(count, correctionForDrift())
            if (needed > resampled.size) resampled = ShortArray(needed)
            val produced = resampler.resample(
                samples,
                count,
                resampled,
                governor.rateScale,
            )
            fadeIn(resampled, produced)
            effects.mix(resampled, produced)
            if (!firstSoundRecorded && produced > 0 && resampled.take(produced).any { it.toInt() != 0 }) {
                firstSoundRecorded = true
                firstNonzeroBlock = startupBlocks
                record("first_nonzero_audio block=$startupBlocks elapsedMs=${(System.nanoTime() - startNanos) / 1_000_000} nativeCount=$count outputCount=$produced")
                logPcm("first_nonzero_output", resampled, produced)
            }
            if (startupBlocks < 12 ||
                (firstNonzeroBlock >= 0 && startupBlocks <= firstNonzeroBlock + 20)
            ) logPcm("output", resampled, produced)
            startupBlocks++
            droppedSamples += queue.append(resampled, produced).toLong()

            // A partial non-blocking write stays in the bounded FIFO and is
            // retried on the next frame, without causing skips or duplicates.
            while (queue.size > 0) {
                val available = queue.copyHead(scratch)
                val written = track.write(
                    scratch, 0, available, AudioTrack.WRITE_NON_BLOCKING,
                )
                if (written < 0) {
                    throw IllegalStateException("AudioTrack.write: $written")
                }
                if (written == 0) break
                require(written <= available && written % CHANNELS == 0) {
                    "AudioTrack a retourné un nombre incomplet d'échantillons stéréo."
                }
                queue.discard(written)
                writtenFrames += written / CHANNELS
                if (primer.onSamplesQueued(written)) {
                    record("AudioTrack.play: startupBlocks=$startupBlocks queuedFrames=$writtenFrames underruns=${track.underrunCount}")
                    track.play()
                }
            }
            val second = (System.nanoTime() - startNanos) / 1_000_000_000L
            if (second != lastLoggedSecond && second <= 120) {
                lastLoggedSecond = second
                record("t=${second}s written=$writtenFrames played=${track.playbackHeadPosition.toLong() and MASK_32} underruns=${track.underrunCount} pendingPCM=${queue.size / CHANNELS} dropped=${droppedSamples / CHANNELS} state=${track.playState}")
            }
        }
    }

    /** Plays the unlock chime over the game's next samples; a new chime restarts it. */
    fun playChime() {
        synchronized(lock) {
            if (!closed) effects.play(chime)
        }
    }

    /**
     * Per-second video timing from the player, added to the exported report:
     * a slow second then shows whether the engine or the image took the time.
     */
    fun recordVideo(fps: Double, coreMs: Double, conversionMs: Double) {
        synchronized(lock) {
            val second = (System.nanoTime() - startNanos) / 1_000_000_000L
            if (closed || second > 120) return
            record(
                String.format(
                    Locale.ROOT, "video t=%ds fps=%.1f moteurMs=%.2f convMs=%.2f",
                    second, fps, coreMs, conversionMs,
                ),
            )
        }
    }

    private fun correctionForDrift(): Double {
        if (!primer.playbackStarted) return 1.0
        val played = track.playbackHeadPosition.toLong() and MASK_32
        val queued = (writtenFrames - played) and MASK_32
        return governor.onQueuedFrames(queued.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Diagnostic for the first PCM blocks; avoids recording or exposing game audio. */
    private fun logPcm(stage: String, samples: ShortArray, count: Int) {
        var peak = 0
        var jump = 0
        var previousLeft = 0
        var previousRight = 0
        var sumLeft = 0L
        var sumRight = 0L
        for (i in 0 until count step CHANNELS) {
            val left = samples[i].toInt()
            val right = samples[i + 1].toInt()
            sumLeft += left
            sumRight += right
            peak = maxOf(peak, kotlin.math.abs(left), kotlin.math.abs(right))
            if (i != 0) jump = maxOf(jump, kotlin.math.abs(left - previousLeft), kotlin.math.abs(right - previousRight))
            previousLeft = left
            previousRight = right
        }
        record("startup block=$startupBlocks stage=$stage samples=$count firstL=${if (count > 0) samples[0] else 0} firstR=${if (count > 1) samples[1] else 0} peak=$peak maxJump=$jump meanL=${if (count > 0) sumLeft / (count / 2) else 0} meanR=${if (count > 0) sumRight / (count / 2) else 0}")
    }

    private fun record(message: String) {
        Log.d(TAG, message)
        if (report.length < 24000) report.append(message).append('\n')
    }

    fun exportReport(): String = synchronized(lock) {
        report.toString() + diagnostics() + "\n"
    }

    /** Live diagnostics to distinguish device underruns from bounded FIFO overflow. */
    fun diagnostics(): String = synchronized(lock) {
        stoppedDiagnostics ?: "Audio: underruns=${track.underrunCount}, pertesPCM=${droppedSamples / CHANNELS} frames, " +
            "written=$writtenFrames played=${track.playbackHeadPosition.toLong() and MASK_32}, " +
            "file=${queue.size / CHANNELS} PCM, " +
            "latence~=${((writtenFrames - (track.playbackHeadPosition.toLong() and MASK_32)).coerceAtLeast(0L) * 1000L / outputRate)}ms"
    }

    /**
     * The ROM can produce hundreds of milliseconds of silence before its first
     * audible sample. Start the fade at the first nonzero stereo frame rather
     * than consuming the whole fade over silence.
     */
    private fun fadeIn(samples: ShortArray, count: Int) {
        for (index in 0 until count step CHANNELS) {
            val left = samples[index].toInt()
            val right = samples[index + 1].toInt()
            if (rampFrame == 0 && left == 0 && right == 0) continue
            if (rampFrame >= fadeFrames) break
            val phase = rampFrame.toDouble() / fadeFrames
            val gain = 0.5 - 0.5 * cos(PI * phase)
            samples[index] = (left * gain).roundToInt().coerceIn(-32768, 32767).toShort()
            samples[index + 1] = (right * gain).roundToInt().coerceIn(-32768, 32767).toShort()
            if (rampFrame == 0) record("fade_in_started block=$startupBlocks")
            rampFrame++
        }
    }

    /** Stop promptly when the user exits or backgrounds the app. */
    fun stopNow() {
        synchronized(lock) {
            if (closed) return
            stoppedDiagnostics = diagnostics()
            closed = true
            try {
                track.pause()
                track.flush()
            } catch (_: IllegalStateException) {
                // A failed track must not prevent lifecycle cleanup.
            }
            queue.clear()
        }
    }

    override fun close() {
        stopNow()
        synchronized(lock) {
            track.release()
        }
    }

    private companion object {
        const val TAG = "RavenEmuAudio"
        const val CHANNELS = 2
        const val BYTES_PER_SAMPLE = 2
        const val BUFFER_VIDEO_FRAMES = 4
        const val PRIME_VIDEO_FRAMES = 2
        const val MASK_32 = 0xFFFF_FFFFL

        fun resolveNativeRate(context: Context): Int = try {
            val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val reported = manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            if (reported != null && reported in 8000..192000) reported else 48000
        } catch (_: Exception) {
            48000
        }
    }
}
