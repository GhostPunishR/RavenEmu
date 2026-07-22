package com.ravenemu.app.emulation

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Sortie audio AudioTrack en mode flux. [write] est bloquant : appelé depuis
 * le thread d'émulation, il cale naturellement la cadence de la session sur
 * l'horloge audio du système (synchronisation audio/vidéo).
 */
class AndroidAudioSink(
    sampleRateHz: Int,
    samplesPerFrame: Int,
) : EmulationSession.AudioSink {

    private val track: AudioTrack

    init {
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        // Trois trames de latence : réactif sans sous-alimentation.
        val bufferBytes = maxOf(minBuffer, samplesPerFrame * 2 * 2 * 3)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
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
            track.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)
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
        } catch (_: Exception) {
        }
    }

    override fun release() {
        try {
            track.release()
        } catch (_: Exception) {
        }
    }
}
