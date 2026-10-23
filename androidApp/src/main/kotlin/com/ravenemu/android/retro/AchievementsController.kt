package com.ravenemu.android.retro

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.ravenemu.shared.GamePlatform
import com.ravenemu.shared.RomImportPolicy
import com.ravenemu.shared.RetroSnapshot
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import com.ravenemu.android.engine.AndroidGbSession
import com.ravenemu.android.saves.GbBatteryStore

/**
 * Owns the official SDK on one dedicated thread (RavenEmu 0.8.1's ownership rule).
 * HTTP never calls the native client directly. Memory reads only observe the
 * active native GB core under AndroidGbSession's existing lock.
 */
internal class AchievementsController(
    context: Context,
    private val session: AndroidGbSession,
    private val onSnapshot: (RetroSnapshot) -> Unit,
    private val onEvent: (AchievementClient.Event) -> Unit,
) {
    private val applicationContext = context.applicationContext
    private val account = AchievementAccount(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RavenEmu-RetroAchievements").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var client: AchievementClient? = null // dedicated SDK thread only
    // The cartridge whose session runs while the player is open; the
    // parked one waits for the player to open again.
    private var currentRom: ByteArray? = null
    private var currentPlatform = GamePlatform.GB
    private var parkedRom: ByteArray? = null
    private var parkedPlatform = GamePlatform.GB
    private var connected = false
    private var currentUser = ""
    private var lastSnapshotMs = 0L
    private var stopped = false
    private var progressBaselinePending = false
    private var resetDuringTick = false
    // Hardcore needs RetroAchievements' approval: wanted AND approved.
    private var hardcoreWanted = false
    private var stage = HardcoreGate.Stage.IDLE
    private var retryAttempt = 0
    private var retryAtMs = 0L
    private var evaluationFailureReported = false

    /** Read by the UI: true while the SDK runs this session in Hardcore. */
    @Volatile var hardcoreActive: Boolean = false
        private set

    /**
     * Read by the emulation loop before each frame: true while Hardcore waits
     * for the sign-in or the identification of the ROM (see [HardcoreGate]).
     * The loop then only ticks [idle].
     */
    @Volatile var framesOnHold: Boolean = false
        private set

    /** Hardcore wanted or running: no save state may be loaded. */
    val stateLoadLocked: Boolean get() = session.stateLoadLocked

    /** Persisted token is used only after a prior, explicit login. */
    suspend fun restore() = withContext(dispatcher) {
        if (stopped) return@withContext
        account.read()?.let { start(it.username, it.token, true) }
    }

    suspend fun login(username: String, password: String) = withContext(dispatcher) {
        require(username.isNotBlank() && password.isNotEmpty()) {
            "Saisissez votre nom d'utilisateur et mot de passe."
        }
        if (stopped) return@withContext
        retryAttempt = 0
        start(username.trim(), password, false)
    }

    suspend fun retry() = withContext(dispatcher) {
        // A tracked game keeps its session: the SDK reconnects by itself, and
        // a new session would restart the game in Hardcore.
        if (!stopped && connected && stage == HardcoreGate.Stage.TRACKING) {
            client?.let { publish(it.snapshot()) }
            return@withContext
        }
        if (!stopped) {
            retryAttempt = 0
            val identity = account.read()
            if (identity != null) start(identity.username, identity.token, true)
            else publish(RetroSnapshot(status = "Connectez votre compte RetroAchievements."))
        }
    }

    /**
     * The cartridge the player will run. Its session starts with [resumeGame],
     * so the library never opens one; a running session is replaced at once.
     */
    suspend fun selectRom(rom: ByteArray, platform: GamePlatform) = withContext(dispatcher) {
        if (stopped) return@withContext
        if (currentRom != null) {
            attach(rom, platform)
            return@withContext
        }
        parkedRom = rom.copyOf()
        parkedPlatform = platform
    }

    /** The player opens: the selected cartridge's session starts. */
    suspend fun resumeGame() = withContext(dispatcher) {
        if (stopped) return@withContext
        val rom = parkedRom ?: return@withContext
        attach(rom, parkedPlatform)
    }

    /**
     * The player closes: the game's session ends, so neither its data nor a
     * retry outlives it. The cartridge stays selected for [resumeGame].
     */
    suspend fun leaveGame() = withContext(dispatcher) {
        if (stopped) return@withContext
        val rom = currentRom ?: return@withContext
        parkedRom = rom
        parkedPlatform = currentPlatform
        currentRom = null
        progressBaselinePending = false
        stage = HardcoreGate.Stage.IDLE
        updateGate()
        client?.let {
            it.unload()
            publish(it.snapshot())
        }
    }

    private fun attach(rom: ByteArray, platform: GamePlatform) {
        parkedRom = null
        val id = GbBatteryStore.keyForRom(rom)
        val currentId = currentRom?.let(GbBatteryStore::keyForRom)
        if (id == currentId) return
        currentRom = rom.copyOf()
        currentPlatform = platform
        // New ROM means a new SDK game session, including pending award
        // requests. Never let the old game's award be associated with a
        // different cartridge.
        retryAttempt = 0
        val credentials = account.read()
        if (credentials != null) start(credentials.username, credentials.token, true)
        else {
            // No account: nothing to evaluate, the game runs.
            stage = HardcoreGate.Stage.IDLE
            updateGate()
            if (client != null) {
                client?.close()
                client = null
                connected = false
                publish(RetroSnapshot(status = "Non connecté"))
            }
        }
    }

    suspend fun frame() = withContext(dispatcher) {
        if (!stopped) poll(frame = true)
    }

    suspend fun idle() = withContext(dispatcher) {
        if (!stopped) poll(frame = false)
    }

    suspend fun fetchLeaderboard(id: Int, aroundUser: Boolean) = withContext(dispatcher) {
        if (!stopped && connected) {
            client?.fetchLeaderboard(id, aroundUser)
            client?.let { publish(it.snapshot()) }
        }
    }

    suspend fun resetProgress() = withContext(dispatcher) {
        if (!stopped) {
            client?.reset()
            progressBaselinePending = true
            main.post {
                if (!stopped) onEvent(AchievementClient.Event("progress_reset", "", "", "", ""))
            }
        }
    }

    /**
     * Hardcore on or off. Turning it on with a game loaded makes the SDK ask
     * for a reset, which [handleEvent] performs. Turning it off is immediate.
     */
    suspend fun setHardcore(enabled: Boolean) = withContext(dispatcher) {
        hardcoreWanted = RetroHardcorePolicy.enabled(enabled)
        // State loads lock before the SDK enters Hardcore and unlock only once it left.
        updateGate()
        client?.setHardcore(hardcoreWanted)
        updateGate()
        client?.let { publish(it.snapshot()) }
    }

    /**
     * Serialize state restores and Hardcore transitions on the SDK owner. The
     * session refuses the restore itself while Hardcore is wanted or running.
     */
    suspend fun loadState(slot: Int) = withContext(dispatcher) {
        check(!stopped)
        session.loadStateSlot(slot)
        resetProgress()
    }

    /**
     * The game menu pauses the game. In Hardcore the SDK limits how often a
     * game may pause; it records the pause when it allows it. Blocks the
     * caller only for the SDK thread's current step.
     */
    fun requestPause(): Boolean {
        if (!hardcoreActive || stopped) return true
        return runBlocking(dispatcher) { client?.canPause() ?: true }
    }

    suspend fun disconnect() = withContext(dispatcher) {
        account.clear()
        // Signing out forgets this device's unsent unlocks too (RavenEmu 0.8.1 rule):
        // they belong to an account that is no longer signed in here.
        runCatching { AwardJournal(applicationContext).clear() }
        client?.close()
        client = null
        connected = false
        currentUser = ""
        stage = HardcoreGate.Stage.IDLE
        updateGate()
        publish(RetroSnapshot(status = "Non connecté"))
    }

    private fun start(username: String, secret: String, token: Boolean) {
        client?.close()
        client = null
        progressBaselinePending = false
        connected = false
        evaluationFailureReported = false
        currentUser = username
        try {
            val fresh = AchievementClient(
                readMemory = { address, count -> session.readAchievementMemory(address, count) },
                // Each client gets a fresh lease. Sign-out permanently revokes
                // old clients, including HTTP callbacks still finishing late.
                journal = AwardJournal(applicationContext),
                account = username,
                userAgent = RetroUserAgent.of(applicationContext),
                onEvent = { event -> handleEvent(event) },
            )
            client = fresh
            // Before the game loads, so switching it on needs no reset.
            fresh.setHardcore(hardcoreWanted)
            stage = HardcoreGate.Stage.SIGNING_IN
            updateGate()
            fresh.login(username, secret, token)
        } catch (error: Exception) {
            // No sign-in in flight: nothing would ever lift the barrier.
            client?.close()
            client = null
            stage = HardcoreGate.Stage.UNTRACKED
            leaveHardcore("Le client RetroAchievements n'a pas pu démarrer.")
            updateGate()
            throw error
        }
        publish(RetroSnapshot(status = "Connexion en cours…", busy = true))
        // Never retain the plaintext password; it is needed only for this
        // immediate native SDK call. The account saves only the returned token.
    }

    private fun handleEvent(event: AchievementClient.Event) {
        when (event.kind) {
            "login" -> {
                val name = event.title.ifBlank { currentUser }
                val token = event.extra
                if (token.isNotEmpty()) {
                    // A device with unavailable Keystore can still use this
                    // active session; don't strand the game on token-save failure.
                    runCatching {
                        account.save(name, token, event.detail.ifBlank { name }, event.image)
                    }
                }
                connected = true
                loadCurrentGame()
            }
            // An unreachable service is waited for. A refused account leaves
            // nothing to evaluate: the game runs untracked, the card says why.
            "login_error" -> {
                connected = false
                if (event.extra == "offline") waitForNetwork(event)
                else stage = HardcoreGate.Stage.UNTRACKED
            }
            "game" -> {
                stage = HardcoreGate.Stage.TRACKING
                retryAttempt = 0
                progressBaselinePending = true
                // The game may have run a few frames before the SDK knew it
                // (the barrier engages once the session starts): in Hardcore
                // it starts over from power-on, as the SDK does.
                if (client?.hardcore() == true) restartForHardcore()
            }
            // Nothing to evaluate: the game runs without achievements, and says so.
            "game_unknown", "game_error" -> {
                stage = HardcoreGate.Stage.UNTRACKED
                retryAttempt = 0
                notice(event.title, event.detail)
            }
            "game_offline" -> waitForNetwork(event)
            // Required by rcheevos before Hardcore processing can resume.
            "hardcore_reset" -> restartForHardcore()
        }
        updateGate()
        // Indicator and leaderboard events can arrive every frame (a running
        // timer); they change no achievement state, so skip the snapshot copy.
        if (event.kind !in TRANSIENT_EVENTS) {
            val snapshot = client?.snapshot() ?: RetroSnapshot()
            publish(snapshot.copy(error = if (event.kind.endsWith("_error")) event.title else null))
        }
        // The SDK login event includes a secret token; it is saved above
        // and never forwarded to Compose, toasts or diagnostics.
        val publicEvent = if (event.kind == "login") event.copy(extra = "")
            else event
        main.post { if (!stopped) onEvent(publicEvent) }
    }

    private fun poll(frame: Boolean) {
        val c = client ?: return
        val processFrame = frame && connected && currentRom != null
        resetDuringTick = false
        try {
            c.tick(processFrame)
        } catch (error: Exception) {
            // The caller logs it; Hardcore cannot go on without this frame.
            frameEvaluationFailed()
            throw error
        }
        val now = System.nanoTime() / 1_000_000L
        if (processFrame && progressBaselinePending && !resetDuringTick) {
            // The first evaluated frame initializes Measured values. They
            // are a baseline, not gains (rc_client ignores UNKNOWN -> value).
            progressBaselinePending = false
            main.post {
                if (!stopped) onEvent(AchievementClient.Event("progress_baseline", "", "", "", ""))
            }
            lastSnapshotMs = now
            publish(c.snapshot())
        } else if (now - lastSnapshotMs >= 1200L) {
            lastSnapshotMs = now
            publish(c.snapshot())
        }
        // Last: signing in again replaces the client used above.
        if (stage == HardcoreGate.Stage.WAITING_NETWORK && framesOnHold && now >= retryAtMs) retryConnection()
    }

    /** The platform that picked the core also picks the RA console, so both agree. */
    private fun loadCurrentGame() {
        val rom = currentRom
        if (rom == null) {
            stage = HardcoreGate.Stage.IDLE
            return
        }
        stage = HardcoreGate.Stage.LOADING
        client?.load(RomImportPolicy.retroAchievementsConsole(currentPlatform), rom)
    }

    /**
     * The service is out of reach, which is not a refusal: Hardcore holds the
     * game and tries again, Softcore plays on. Said once per wait.
     */
    private fun waitForNetwork(event: AchievementClient.Event) {
        if (retryAttempt == 0) {
            if (hardcoreWanted && currentRom != null) notice("Mode Hardcore en attente",
                "RetroAchievements ne répond pas : la partie reprend dès sa réponse. " +
                    "Désactivez le Hardcore pour jouer hors ligne.")
            else if (event.kind == "game_offline") notice(event.title, event.detail)
        }
        stage = HardcoreGate.Stage.WAITING_NETWORK
        retryAtMs = System.nanoTime() / 1_000_000L + HardcoreGate.retryDelayMillis(retryAttempt++)
    }

    private fun retryConnection() {
        if (connected) {
            loadCurrentGame()
        } else {
            val identity = account.read()
            if (identity != null) start(identity.username, identity.token, true)
            else stage = HardcoreGate.Stage.IDLE
        }
        updateGate()
    }

    /**
     * The game restarts from power-on, and so does the SDK's state: rcheevos
     * requires it before Hardcore evaluates a game. If the game cannot
     * restart, Hardcore is left.
     */
    private fun restartForHardcore() {
        if (runCatching { session.resetGame() }.isFailure) leaveHardcore("Le redémarrage du jeu a échoué.")
        client?.reset()
        progressBaselinePending = true
        resetDuringTick = true
    }

    /** A failure Hardcore cannot go on with: back to Softcore, and the settings follow. */
    private fun leaveHardcore(detail: String) {
        if (!hardcoreWanted && client?.hardcore() != true) return
        hardcoreWanted = false
        client?.setHardcore(false)
        updateGate()
        // The settings follow, so the next launch does not ask again.
        main.post { if (!stopped) onEvent(AchievementClient.Event("hardcore_refused",
            "Mode Hardcore désactivé", detail, "", "")) }
    }

    /** The SDK could not evaluate a frame: said once, and Hardcore cannot go on without it. */
    private fun frameEvaluationFailed() {
        leaveHardcore("L'évaluation des succès a échoué.")
        if (evaluationFailureReported) return
        evaluationFailureReported = true
        notice("Succès RetroAchievements interrompus",
            "Une erreur empêche l'évaluation des succès de cette partie.")
    }

    private fun notice(title: String, detail: String) {
        main.post { if (!stopped) onEvent(AchievementClient.Event("notice", title, detail, "", "")) }
    }

    /** Publishes the barrier and the state-load lock after a change of mode or stage. */
    private fun updateGate() {
        hardcoreActive = client?.hardcore() ?: false
        session.stateLoadLocked = hardcoreWanted || hardcoreActive
        framesOnHold = HardcoreGate.holdFrames(hardcoreWanted, currentRom != null, stage)
    }

    private fun publish(snapshot: RetroSnapshot) {
        main.post { if (!stopped) onSnapshot(snapshot) }
    }

    fun close() {
        if (stopped) return
        stopped = true
        try {
            executor.submit {
                client?.close()
                client = null
                currentRom = null
                parkedRom = null
            }.get(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
            // Teardown errors must not crash or block app shutdown.
        } finally {
            dispatcher.close()
        }
    }

    private companion object {
        val TRANSIENT_EVENTS = setOf(
            "progress_show", "progress_hide", "challenge_show", "challenge_hide",
            "tracker_show", "tracker_hide", "leaderboard_started", "leaderboard_failed",
            "leaderboard_submitted", "leaderboard_scoreboard",
        )
    }
}
