package com.ravenemu.app.emulation

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.ravenemu.emulation.api.audio.LinearResampler
import kotlin.math.ceil

/**
 * Sortie audio AudioTrack en mode flux.
 *
 * Le moteur produit ses échantillons à [sourceRateHz] (32768 Hz). Plutôt que
 * de laisser le système rééchantillonner vers le débit de sortie — avec une
 * qualité variable selon l'appareil — on ouvre l'AudioTrack au **débit natif**
 * du périphérique et on rééchantillonne nous-mêmes ([LinearResampler]).
 *
 * [write] est bloquant : appelé depuis le thread d'émulation, il cale la
 * cadence de la session sur l'horloge audio du système (synchronisation
 * audio/vidéo), quel que soit le débit de sortie.
 */
class AndroidAudioSink(
    context: Context,
    sourceRateHz: Int,
    sourceSamplesPerFrame: Int,
) : EmulationSession.AudioSink {

    private val outputRate = resolveNativeRate(context)
    private val resampler = LinearResampler(sourceRateHz, outputRate)
    private var resampled = ShortArray(0)
    private val track: AudioTrack

    init {
        val outputSamplesPerFrame =
            ceil(sourceSamplesPerFrame.toDouble() * outputRate / sourceRateHz).toInt()
        val minBuffer = AudioTrack.getMinBufferSize(
            outputRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        // Le rendu vidéo étant découplé, le thread d'émulation ne fait plus
        // qu'un travail bref entre deux écritures : trois trames de sortie
        // (~50 ms) suffisent contre les sous-alimentations, tout en réduisant
        // la latence audio pour rapprocher le son de l'image (synchro A/V).
        val bufferBytes = maxOf(minBuffer, outputSamplesPerFrame * 2 * 2 * 3)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // Chemin basse latence lorsqu'il est disponible (réduit le
            // tampon matériel, donc le décalage son/image).
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    override fun write(samples: ShortArray, count: Int) {
        try {
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
            val needed = resampler.maxOutput(count)
            if (resampled.size < needed) resampled = ShortArray(needed)
            val produced = resampler.resample(samples, count, resampled)
            track.write(resampled, 0, produced, AudioTrack.WRITE_BLOCKING)
        } catch (_: Exception) {
            // Une sortie audio défaillante ne doit pas interrompre le jeu.
        }
    }

    override fun setVolume(volume: Float) {
        try {
            track.setVolume(volume.coerceIn(0f, 1f))
        } catch (_: Exception) {
        }
    }

    override fun pause() {
        try {
            track.pause()
            track.flush()
            resampler.reset()
        } catch (_: Exception) {
        }
    }

    override fun release() {
        try {
            track.release()
        } catch (_: Exception) {
        }
    }

    private companion object {
        /** Débit de sortie natif du périphérique, avec repli sûr sur 48 kHz. */
        fun resolveNativeRate(context: Context): Int {
            return try {
                val manager =
                    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val reported = manager
                    .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                    ?.toIntOrNull()
                if (reported != null && reported in 8000..192000) reported else 48000
            } catch (_: Exception) {
                48000
            }
        }
    }
}
