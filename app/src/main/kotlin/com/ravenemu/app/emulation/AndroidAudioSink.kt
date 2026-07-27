package com.ravenemu.app.emulation

import com.ravenemu.emulation.api.session.EmulationSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.ravenemu.emulation.api.audio.AudioBufferPrimer
import com.ravenemu.emulation.api.audio.LinearResampler
import kotlin.math.ceil

/**
 * Sortie audio AudioTrack en mode flux.
 *
 * Le moteur produit ses échantillons à [sourceRateHz] (32768 Hz). Plutôt que
 * de laisser le système rééchantillonner vers le débit de sortie, avec une
 * qualité variable selon l'appareil, on ouvre l'AudioTrack au **débit natif**
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
    private val primer: AudioBufferPrimer
    private val track: AudioTrack

    /** Passé à `true` par [unblock] : plus aucune écriture n'est tentée. */
    @Volatile
    private var stopped = false

    init {
        val outputFramesPerVideoFrame =
            ceil(sourceSamplesPerFrame.toDouble() * outputRate / sourceRateHz).toInt()
        val minBuffer = AudioTrack.getMinBufferSize(
            outputRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(0)
        // Huit trames absorbent un pic ponctuel d'ordonnancement Android.
        // Six trames sont écrites avant le premier appel à play(), soit environ
        // 100 ms de réserve à 60 Hz. La marge restante évite de vider
        // la piste sans modifier la fréquence ni les timings du moteur.
        val bufferBytes = maxOf(
            minBuffer,
            outputFramesPerVideoFrame *
                CHANNEL_COUNT *
                BYTES_PER_SAMPLE *
                BUFFER_VIDEO_FRAMES,
        )
        primer = AudioBufferPrimer(
            outputFramesPerVideoFrame * CHANNEL_COUNT * PRIME_VIDEO_FRAMES,
        )
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
            recoverFromUnderrun()

            // Le rééchantillonnage conserve toujours le débit natif du moteur.
            // Une variation du temps de rendu ne doit jamais modifier la hauteur
            // du son, en particulier sur les moteurs GB et GBC.
            if (stopped) return
            val needed = resampler.maxOutput(count)
            if (resampled.size < needed) resampled = ShortArray(needed)
            val produced = resampler.resample(samples, count, resampled)

            // WRITE_BLOCKING peut encore retourner une écriture partielle si la
            // piste change d'état. Ne jamais abandonner silencieusement la fin.
            var offset = 0
            while (offset < produced) {
                val written = track.write(
                    resampled,
                    offset,
                    produced - offset,
                    AudioTrack.WRITE_BLOCKING,
                )
                if (written <= 0) break
                offset += written
            }

            // AudioTrack ne commence à consommer qu'après le préremplissage.
            // Les appels suivants maintiennent cette avance au lieu de courir
            // en permanence au bord d'une nouvelle rupture.
            if (primer.onSamplesQueued(offset)) track.play()
        } catch (_: Exception) {
            // Une sortie audio défaillante ne doit pas interrompre le jeu.
        }
    }

    override fun underrunCount(): Int = currentUnderrunCount()

    /**
     * Une rupture vide l'avance accumulée. On arrête alors la piste, on jette
     * son tampon devenu discontinu et on repasse par le même préremplissage.
     */
    private fun recoverFromUnderrun() {
        if (!primer.onUnderrunCount(currentUnderrunCount())) return
        track.pause()
        track.flush()
        primer.reset(currentUnderrunCount())
    }

    private fun currentUnderrunCount(): Int = try {
        track.underrunCount.coerceAtLeast(0)
    } catch (_: Exception) {
        0
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
            primer.reset(currentUnderrunCount())
            resampler.reset()
        } catch (_: Exception) {
        }
    }

    /**
     * Débloque une écriture en cours et refuse les suivantes.
     *
     * `AudioTrack.write` en mode bloquant n'a pas d'interruption : elle rend la
     * main quand la file se vide. `pause` puis `flush` la vident d'un coup, et
     * [stopped] fait échouer les écritures suivantes — c'est ce qui permet à un
     * arrêt de session d'aboutir dans son délai au lieu d'attendre la fin du
     * tampon.
     */
    override fun unblock() {
        stopped = true
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

    private companion object {
        const val CHANNEL_COUNT = 2
        const val BYTES_PER_SAMPLE = 2
        const val BUFFER_VIDEO_FRAMES = 8
        const val PRIME_VIDEO_FRAMES = 6

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
