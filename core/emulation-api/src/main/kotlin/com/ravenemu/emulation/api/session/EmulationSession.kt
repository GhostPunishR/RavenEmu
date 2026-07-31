package com.ravenemu.emulation.api.session

import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.timing.AdaptiveFrameSkipper
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
    /**
     * Appelé une fois au démarrage du thread d'émulation, **sur ce thread**.
     *
     * Sert à la plateforme pour demander son groupe d'ordonnancement : c'est
     * lui qui décide, sur un processeur hétérogène, si le thread tourne sur un
     * cœur puissant ou sur un cœur économe. Laissé vide, la session reste
     * indépendante d'Android et donc testable.
     */
    private val onThreadStart: () -> Unit = {},
) {

    /** Issue d'un appel à [stop]. */
    enum class StopResult {
        /** Thread terminé et sortie audio libérée. */
        CLEAN,

        /** Aucune session n'était active : appel sans effet. */
        NOT_RUNNING,

        /**
         * Le thread n'a pas rendu la main dans le délai imparti. La sortie
         * audio n'est **pas** libérée : le thread peut encore s'en servir, et
         * la libérer sous ses pieds provoquerait un plantage natif. Mieux vaut
         * une ressource retenue qu'un crash, mais l'appelant doit le savoir.
         */
        TIMED_OUT,
    }
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

        /**
         * RAM de cartouche à persister (thread d'émulation).
         *
         * Doit retourner `true` **uniquement si l'écriture a réellement
         * abouti**. Sur `false`, le moteur reste marqué modifié et la
         * sauvegarde sera retentée : c'est ce retour qui empêche une partie de
         * disparaître en silence quand le disque est plein ou le fichier
         * verrouillé.
         */
        fun onBatterySave(data: ByteArray): Boolean

        /**
         * La sortie audio a levé une exception (thread d'émulation).
         *
         * La session poursuit sans son plutôt que de mourir : perdre le son est
         * désagréable, perdre la partie ne l'est pas du tout.
         */
        fun onAudioFailure(error: Exception) {}
    }

    /**
     * Sortie audio de la plateforme. [write] doit être bloquante : c'est elle
     * qui cadence la session quand l'audio est actif (synchronisation A/V).
     */
    interface AudioSink {
        fun write(samples: ShortArray, count: Int)
        fun underrunCount(): Int = 0
        fun setVolume(volume: Float)
        fun pause()

        /**
         * Débloque une écriture en cours et fait échouer les suivantes.
         *
         * [write] est bloquante par contrat : sans ce point de sortie, un arrêt
         * demandé pendant une écriture attendrait que la file audio se vide,
         * bien au-delà du délai d'arrêt. Appelé avant la jointure du thread.
         */
        fun unblock() {}

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

    /** Protège la paire [running]/[thread] contre des arrêts concurrents. */
    private val lifecycle = Any()

    fun start() {
        synchronized(lifecycle) {
            if (running) return
            running = true
            thread = Thread(::loop, "RavenEmu-Emulation").also {
                it.priority = Thread.MAX_PRIORITY - 1
                it.start()
            }
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

    /** Sous-alimentations relevées par la sortie audio de la plateforme. */
    fun audioOutputUnderruns(): Int = audioSink?.underrunCount() ?: 0

    /**
     * Arrête la session, dans un ordre qui ne laisse jamais le thread utiliser
     * une ressource libérée.
     *
     * 1. l'arrêt est rendu visible au thread ;
     * 2. une écriture audio bloquante est débloquée, sans quoi l'attente
     *    durerait le temps de vider la file ;
     * 3. le thread vide ses commandes et tente une dernière sauvegarde ;
     * 4. sa terminaison est **confirmée** ;
     * 5. la sortie audio n'est libérée qu'après cette confirmation.
     *
     * Idempotent : un second appel retourne [StopResult.NOT_RUNNING] sans rien
     * toucher. En cas de dépassement du délai, la sortie audio reste en vie et
     * [StopResult.TIMED_OUT] le signale — un thread encore vivant qui écrirait
     * dans un `AudioTrack` libéré planterait le processus.
     */
    fun stop(timeoutMillis: Long = STOP_TIMEOUT_MILLIS): StopResult {
        val worker = synchronized(lifecycle) {
            if (!running) return StopResult.NOT_RUNNING
            running = false
            thread
        } ?: return StopResult.NOT_RUNNING

        // Débloque l'écriture audio et le stationnement de cadencement.
        audioSink?.unblock()
        LockSupport.unpark(worker)

        worker.join(timeoutMillis)
        if (worker.isAlive) return StopResult.TIMED_OUT

        synchronized(lifecycle) { thread = null }
        audioSink?.release()
        return StopResult.CLEAN
    }

    /** Exécute [action] sur le thread d'émulation (file sans verrou). */
    fun post(action: (EmulatorCore) -> Unit) {
        commands.add(action)
    }

    fun setButton(button: EmulatorButton, pressed: Boolean) {
        post { it.setButton(button, pressed) }
    }

    /**
     * Applique un groupe de transitions dans une seule commande de session.
     *
     * Les boutons combinés d'un skin tactile deviennent donc visibles ensemble
     * par le moteur avant le prochain cycle émulé, sans intercaler une trame
     * entre A et B.
     */
    fun setButtons(
        pressed: Set<EmulatorButton>,
        released: Set<EmulatorButton>,
    ) {
        if (pressed.isEmpty() && released.isEmpty()) return
        val pressedSnapshot = pressed.toList()
        val releasedSnapshot = released.toList()
        post { target ->
            releasedSnapshot.forEach { target.setButton(it, false) }
            pressedSnapshot.forEach { target.setButton(it, true) }
        }
    }

    private fun loop() {
        // `Thread.priority` ne fait que positionner une valeur de politesse
        // Unix ; le groupe d'ordonnancement, lui, est propre à la plateforme.
        onThreadStart()
        val basePeriodNanos = (1_000_000_000.0 / core.video.refreshRateHz).toLong()
        val frameSkipper = AdaptiveFrameSkipper(basePeriodNanos)
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
                frameSkipper.reset()
                continue
            }

            // Temps de calcul brut d'une trame : mesuré autour de l'émulation
            // seule, sans le cadencement ni l'attente audio.
            val renderVideo =
                !core.supportsVideoFrameSkipping || frameSkipper.shouldRender()
            val workStart = System.nanoTime()
            core.runFrame(framebuffer, renderVideo)
            val frameWorkNanos = System.nanoTime() - workStart
            workNanos += frameWorkNanos
            if (core.supportsVideoFrameSkipping) {
                frameSkipper.onFrameCompleted(renderVideo, frameWorkNanos)
            }

            // Audio d'abord : l'écriture bloquante cadence la session et la
            // file audio est réalimentée avant de payer le coût du rendu
            // vidéo. Hors conditions nominales (avance rapide, audio coupé,
            // vitesse débridée), les échantillons sont drainés puis
            // abandonnés et le cadencement par horloge reprend la main.
            val audioCount = core.readAudio(audioBuffer)
            val audioPaced = audioSink != null && audioEnabled && audioCount > 0 &&
                speedLimitEnabled && !fastForward
            if (audioPaced) {
                // Le débit audio reste celui du moteur. Les variations de temps
                // de rendu ne doivent jamais modifier la hauteur du son.
                try {
                    audioSink!!.write(audioBuffer, audioCount)
                } catch (e: Exception) {
                    // Une sortie audio défaillante ne doit pas emporter la
                    // session : la partie continue en silence, et l'arrêt
                    // pourra encore sauvegarder la RAM de cartouche.
                    callbacks.onAudioFailure(e)
                }
            }

            if (renderVideo) callbacks.onFrame(framebuffer)
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

    /**
     * Sauvegarde en deux temps : instantané, écriture, puis acquittement.
     *
     * L'acquittement ne vient qu'après confirmation de l'écriture, et porte la
     * génération de l'instantané. Si le jeu a écrit entre-temps, le moteur
     * refuse cet acquittement et la sauvegarde suivante reprendra le travail.
     */
    private fun saveBatteryIfDirty(target: EmulatorCore = core) {
        if (!target.hasBatteryRam || !target.batteryRamDirty) return
        val snapshot = target.snapshotBatteryRam() ?: return
        if (callbacks.onBatterySave(snapshot.data)) {
            target.acknowledgeBatteryRamSaved(snapshot.generation)
        }
    }

    /** Force une sauvegarde de la RAM cartouche (pause, arrière-plan…). */
    fun flushBattery() {
        post { c -> saveBatteryIfDirty(c) }
    }

    companion object {
        /** Délai laissé au thread pour rendre la main lors d'un arrêt. */
        const val STOP_TIMEOUT_MILLIS = 2_000L

        private const val BATTERY_SAVE_INTERVAL_NANOS = 5_000_000_000L
        private const val MAX_LAG_NANOS = 100_000_000L
    }
}
