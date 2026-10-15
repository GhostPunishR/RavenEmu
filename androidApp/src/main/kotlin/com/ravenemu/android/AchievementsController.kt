package com.ravenemu.android

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
    private val account = AchievementAccount(context.applicationContext)
    private val journal = AwardJournal(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RavenEmu-RetroAchievements").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var client: AchievementClient? = null // dedicated SDK thread only
    private var currentRom: ByteArray? = null
    private var currentPlatform = GamePlatform.GB
    private var connected = false
    private var currentUser = ""
    private var lastSnapshotMs = 0L
    private var stopped = false
    // Hardcore needs RetroAchievements' approval: wanted AND approved.
    private var hardcoreWanted = false

    /** Read by the UI: true while the SDK runs this session in Hardcore. */
    @Volatile var hardcoreActive: Boolean = false
        private set

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
        start(username.trim(), password, false)
    }

    suspend fun retry() = withContext(dispatcher) {
        if (!stopped) {
            val identity = account.read()
            if (identity != null) start(identity.username, identity.token, true)
            else publish(RetroSnapshot(status = "Connectez votre compte RetroAchievements."))
        }
    }

    suspend fun attachRom(rom: ByteArray, platform: GamePlatform) = withContext(dispatcher) {
        if (stopped) return@withContext
        val id = GbBatteryStore.keyForRom(rom)
        val currentId = currentRom?.let(GbBatteryStore::keyForRom)
        if (id == currentId) return@withContext
        currentRom = rom.copyOf()
        currentPlatform = platform
        // New ROM means a new SDK game session, including pending award
        // requests. Never let the old game's award be associated with a
        // different cartridge.
        val credentials = account.read()
        if (credentials != null) start(credentials.username, credentials.token, true)
        else if (client != null) {
            client?.close()
            client = null
            connected = false
            publish(RetroSnapshot(status = "Non connecté"))
        }
    }

    suspend fun frame() = withContext(dispatcher) {
        if (!stopped) poll(frame = true)
    }

    suspend fun idle() = withContext(dispatcher) {
        if (!stopped) poll(frame = false)
    }

    suspend fun resetProgress() = withContext(dispatcher) {
        if (!stopped) client?.reset()
    }

    /**
     * Hardcore on or off. Turning it on with a game loaded makes the SDK ask
     * for a reset, which [handleEvent] performs. Turning it off is immediate.
     */
    suspend fun setHardcore(enabled: Boolean) = withContext(dispatcher) {
        hardcoreWanted = enabled && HardcoreAvailability.APPROVED
        client?.setHardcore(hardcoreWanted)
        hardcoreActive = client?.hardcore() ?: false
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
        runCatching { journal.clear() }
        client?.close()
        client = null
        connected = false
        currentUser = ""
        publish(RetroSnapshot(status = "Non connecté"))
    }

    private fun start(username: String, secret: String, token: Boolean) {
        client?.close()
        connected = false
        currentUser = username
        val fresh = AchievementClient(
            readMemory = { address, count -> session.readAchievementMemory(address, count) },
            journal = journal,
            account = username,
            onEvent = { event -> handleEvent(event) },
        )
        client = fresh
        // Before the game loads, so switching it on needs no reset.
        fresh.setHardcore(hardcoreWanted)
        hardcoreActive = fresh.hardcore()
        fresh.login(username, secret, token)
        publish(RetroSnapshot(status = "Connexion en cours…", busy = true))
        // Never retain the plaintext password; it is needed only for this
        // immediate native SDK call. The account saves only the returned token.
    }

    private fun handleEvent(event: AchievementClient.Event) {
        if (event.kind == "login") {
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
            // The platform that picked the core also picks the RA console,
            // so the emulator and the service always agree.
            currentRom?.let { rom ->
                client?.load(RomImportPolicy.retroAchievementsConsole(currentPlatform), rom)
            }
        }
        if (event.kind == "login_error") {
            connected = false
        }
        if (event.kind == "hardcore_reset") {
            // Required by rcheevos before Hardcore processing can resume:
            // the game restarts from power-on, and so does the SDK's state.
            runCatching { session.resetGame() }
            client?.reset()
        }
        hardcoreActive = client?.hardcore() ?: false
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
        c.tick(frame && connected && currentRom != null)
        val now = System.nanoTime() / 1_000_000L
        if (now - lastSnapshotMs >= 1200L) {
            lastSnapshotMs = now
            publish(c.snapshot())
        }
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
