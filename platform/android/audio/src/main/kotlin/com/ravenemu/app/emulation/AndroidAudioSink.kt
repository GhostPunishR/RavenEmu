package com.ravenemu.app.emulation

import com.ravenemu.emulation.api.session.EmulationSession
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.ravenemu.emulation.api.audio.AudioBufferPrimer
import com.ravenemu.emulation.api.audio.AudioClockGovernor
import com.ravenemu.emulation.api.audio.AudioTransportStats
import com.ravenemu.emulation.api.audio.BandLimitedResampler
import kotlin.math.ceil

/**
 * Sortie audio AudioTrack en mode flux.
 *
 * Le moteur produit ses échantillons à [sourceRateHz] (32768 Hz). Plutôt que
 * de laisser le système rééchantillonner vers le débit de sortie, avec une
 * qualité variable selon l'appareil, on ouvre l'AudioTrack au **débit natif**
 * du périphérique et on rééchantillonne nous-mêmes ([BandLimitedResampler]).
 *
 * [write] est bloquant : appelé depuis le thread d'émulation, il cale la
 * cadence de la session sur l'horloge audio du système (synchronisation
 * audio/vidéo), quel que soit le débit de sortie.
 *
 * Ce calage laisse subsister une **dérive** : la seconde émulée et la seconde
 * du quartz ne durent pas exactement pareil, et l'écart s'accumule toujours
 * dans le même sens jusqu'à vider ou saturer la piste. [AudioClockGovernor] le
 * rattrape en continu, par une correction de débit trop petite pour s'entendre,
 * ce qui évite la séquence rupture-vidage-repréremplissage et le blanc
 * périodique qu'elle produisait.
 *
 * [stats] relève ce que devient chaque bloc le long de la chaîne. Inactif par
 * défaut, il ne coûte que la lecture d'un booléen par appel.
 */
class AndroidAudioSink(
    context: Context,
    sourceRateHz: Int,
    sourceSamplesPerFrame: Int,
    val stats: AudioTransportStats = AudioTransportStats(),
) : EmulationSession.AudioSink {

    private val outputRate = resolveNativeRate(context)
    private val resampler = BandLimitedResampler(sourceRateHz, outputRate)
    private var resampled = ShortArray(0)
    private val primer: AudioBufferPrimer
    private val governor: AudioClockGovernor
    private val track: AudioTrack

    /**
     * Trames remises à la piste depuis le dernier vidage.
     *
     * `playbackHeadPosition` compte les trames **jouées** et repart de zéro à
     * chaque `flush` : les deux compteurs sont donc remis à zéro ensemble, et
     * leur différence est l'avance encore en attente dans la sortie.
     */
    private var framesWritten = 0L

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
        // L'avance visée est celle que le préremplissage vient d'installer :
        // l'asservissement a pour seul rôle de la maintenir.
        governor = AudioClockGovernor(outputFramesPerVideoFrame * PRIME_VIDEO_FRAMES)
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

    /**
     * Écrit un bloc, **sans rattraper d'exception**.
     *
     * La session appelante traite déjà l'échec : elle poursuit sans son et le
     * rapporte par `onAudioFailure`. Rattraper ici rendait ce chemin
     * inaccessible — une piste en erreur restait muette sans que rien, ni
     * journal, ni compteur, ni message, ne l'indique.
     */
    override fun write(samples: ShortArray, count: Int) {
        recoverFromUnderrun()

        // Le rééchantillonnage suit le débit natif du moteur, à la correction
        // de dérive près : une variation du temps de rendu ne doit jamais
        // modifier la hauteur du son, en particulier sur les moteurs GB et GBC.
        if (stopped) return
        val scale = correctionForDrift()
        val needed = resampler.maxOutput(count, scale)
        if (resampled.size < needed) resampled = ShortArray(needed)
        val produced = resampler.resample(samples, count, resampled, scale)

        // WRITE_BLOCKING peut encore retourner une écriture partielle si la
        // piste change d'état. La fin du bloc est alors perdue : c'est une
        // discontinuité, elle est comptée plutôt qu'ignorée.
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
        framesWritten += offset / CHANNEL_COUNT
        stats.onBlock(submitted = count, resampled = produced, written = offset)

        // AudioTrack ne commence à consommer qu'après le préremplissage.
        // Les appels suivants maintiennent cette avance au lieu de courir
        // en permanence au bord d'une nouvelle rupture.
        if (primer.onSamplesQueued(offset)) track.play()
    }

    override fun underrunCount(): Int = currentUnderrunCount()

    /**
     * Correction de débit à appliquer au bloc courant.
     *
     * Tant que le préremplissage n'est pas terminé, la piste ne consomme rien :
     * l'avance mesurée serait celle d'un démarrage, pas d'une dérive, et
     * l'asservissement partirait dans le décor. On produit alors au débit natif.
     */
    private fun correctionForDrift(): Double {
        if (!primer.playbackStarted) return 1.0
        val played = try {
            // Le compteur de la plateforme est un entier **32 bits non signé**
            // rendu dans un `Int` : il passe par les négatifs au bout d'une
            // douzaine d'heures de lecture continue. Lu sans conversion, il
            // ferait bondir l'avance calculée d'un coup et gèlerait
            // l'asservissement sur un relevé absurde.
            track.playbackHeadPosition.toLong() and MASK_32
        } catch (e: Exception) {
            stats.onFailure(e)
            return governor.rateScale
        }
        // La différence est prise dans le même espace : l'avance réelle est
        // petite et positive, elle traverse donc le rebouclage sans accroc. Une
        // valeur invraisemblable, elle, ressort énorme et l'asservissement la
        // rejette.
        val queued = (framesWritten - played) and MASK_32
        return governor.onQueuedFrames(queued.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * Une rupture vide l'avance accumulée. On arrête alors la piste, on jette
     * son tampon devenu discontinu et on repasse par le même préremplissage.
     */
    private fun recoverFromUnderrun() {
        val ruptures = currentUnderrunCount()
        stats.onUnderrunCount(ruptures)
        if (!primer.onUnderrunCount(ruptures)) return
        stats.onRestart()
        track.pause()
        track.flush()
        primer.reset(currentUnderrunCount())
        forgetQueuedFrames()
    }

    /**
     * Après un vidage, la piste et son compteur de trames jouées repartent de
     * zéro : le nôtre aussi, et la correction avec, faute de quoi la première
     * mesure d'après comparerait deux origines différentes.
     */
    private fun forgetQueuedFrames() {
        framesWritten = 0L
        governor.reset()
    }

    private fun currentUnderrunCount(): Int = try {
        track.underrunCount.coerceAtLeast(0)
    } catch (e: Exception) {
        stats.onFailure(e)
        0
    }

    /**
     * Les points d'entrée qui suivent sont appelés depuis l'interface et depuis
     * l'arrêt de session, hors du `try` de la boucle d'émulation : une exception
     * y remonterait jusqu'à un appelant qui ne peut rien en faire, et
     * empêcherait l'arrêt d'aboutir. Ils la retiennent donc, mais la comptent.
     */
    override fun setVolume(volume: Float) {
        try {
            track.setVolume(volume.coerceIn(0f, 1f))
        } catch (e: Exception) {
            stats.onFailure(e)
        }
    }

    override fun pause() {
        try {
            track.pause()
            track.flush()
            primer.reset(currentUnderrunCount())
            forgetQueuedFrames()
            resampler.reset()
        } catch (e: Exception) {
            stats.onFailure(e)
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
        } catch (e: Exception) {
            stats.onFailure(e)
        }
    }

    override fun release() {
        try {
            track.release()
        } catch (e: Exception) {
            stats.onFailure(e)
        }
    }

    private companion object {
        const val CHANNEL_COUNT = 2
        const val BYTES_PER_SAMPLE = 2
        const val BUFFER_VIDEO_FRAMES = 8
        const val PRIME_VIDEO_FRAMES = 6

        /** Espace du compteur de trames jouées de la plateforme. */
        const val MASK_32 = 0xFFFF_FFFFL

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
