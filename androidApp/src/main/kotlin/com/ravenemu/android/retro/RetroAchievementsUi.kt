package com.ravenemu.android.retro

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ravenemu.shared.retro.RetroBanner
import com.ravenemu.shared.retro.RetroBannerMotion
import com.ravenemu.shared.retro.RetroIndicatorState
import com.ravenemu.shared.retro.RetroNoticeText
import com.ravenemu.shared.retro.RetroProgress
import com.ravenemu.shared.retro.RetroProgressNotifications
import com.ravenemu.shared.retro.RetroSnapshot
import com.ravenemu.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.ravenemu.android.engine.AndroidGbSession
import com.ravenemu.android.player.GbPlayerUi

/**
 * RetroAchievements as the screens show it: account snapshot, badges, the
 * popups of unlocks and the indicators of the running game. The suspending
 * functions are the activity's effects; [retro] talks to the official SDK.
 */
internal class RetroAchievementsUi(
    applicationContext: Context,
    gameSession: AndroidGbSession,
    private val scope: CoroutineScope,
    /** The unlock chime; the activity plays it through the running game's audio. */
    private val playSound: () -> Unit = {},
    private val nowMillis: () -> Long = SystemClock::uptimeMillis,
) {
    var retroSnapshot by mutableStateOf(RetroSnapshot())
        private set
    var retroImages by mutableStateOf(emptyMap<String, ImageBitmap>())
        private set
    var retroBusy by mutableStateOf(false)
        private set
    var retroNotice by mutableStateOf<RetroBanner?>(null)
        private set
    var retroNoticeBadge by mutableStateOf<ImageBitmap?>(null)
        private set
    var retroNoticeVisible by mutableStateOf(false)
        private set
    var retroIndicators by mutableStateOf(emptyList<String>())
        private set
    var retroProgress by mutableStateOf<RetroProgress?>(null)
        private set
    private val retroBadgeStore = RetroBadgeStore(applicationContext)
    // Unlocks wait their turn: several often fire on the same frame,
    // and each used to replace the previous popup at once.
    private val retroUnlocks = mutableStateListOf<RetroPopup>()
    // Challenges and leaderboard trackers follow the SDK's SHOW/HIDE events.
    private val retroIndicatorState = RetroIndicatorState()
    private val progressNotifications = RetroProgressNotifications()
    private var progressReady = false
    private var preferences = Settings()
    private var currentPopup: RetroPopup? = null

    /** Hardcore was switched off by the app (the game could not restart): the settings follow. */
    var onHardcoreRefused: () -> Unit = {}

    /**
     * Badges, Hardcore and the session restored at launch; the popups while
     * a game shows in the foreground, the SDK ticking on its own otherwise.
     */
    @Composable
    fun Effects(hardcore: Boolean, player: GbPlayerUi, foreground: Boolean) {
        LaunchedEffect(retroSnapshot.avatarUrl, retroSnapshot.entries.map { it.badgeUrl }.distinct()) {
            loadImages()
        }
        // Effective only once RetroAchievements approved the app.
        LaunchedEffect(retro, hardcore) {
            runCatching { retro.setHardcore(hardcore) }
                .onFailure { Log.w("RavenEmuRetro", "Hardcore switch failed", it) }
        }
        LaunchedEffect(retro) { restore() }
        LaunchedEffect(retroProgress?.badgeUrl) { loadProgressBadge() }
        LaunchedEffect(player.videoEnabled, foreground) {
            if (player.videoEnabled && foreground) showUnlocks() else hidePopups()
        }
        LaunchedEffect(player.videoEnabled, foreground) {
            if (player.videoEnabled && foreground) showProgress()
            else hideProgress()
        }
        LaunchedEffect(retro, player.videoEnabled, player.pausedForGameMenu, foreground) {
            tickWhile { !player.gameRunning }
        }
    }

    fun configure(settings: Settings) {
        preferences = settings.normalized()
        progressNotifications.displayMillis = preferences.retroProgressSeconds * 1000L
        if (!preferences.retroProgressPopups) hideProgress()
        if (!preferences.retroUnlockPopups) {
            retroUnlocks.removeAll { it.award }
            if (currentPopup?.award == true) hideNotice()
        }
    }

    private fun hideNotice() {
        currentPopup = null
        retroNoticeVisible = false
        retroNotice = null
        retroNoticeBadge = null
    }

    fun hidePopups() {
        retroUnlocks.clear()
        hideNotice()
    }

    fun fetchLeaderboard(id: Int, around: Boolean) {
        scope.launch {
            runCatching { retro.fetchLeaderboard(id, around) }.onFailure {
                retroSnapshot = retroSnapshot.copy(error = "Impossible de charger les scores. Réessayez.")
            }
        }
    }
    val retro = AchievementsController(
        applicationContext, gameSession,
        onSnapshot = ::updateSnapshot,
        onEvent = ::handleEvent,
    )

    internal fun updateSnapshot(snapshot: RetroSnapshot) {
        // Closing/replacing the SDK client does not emit indicator HIDE
        // events. Reset at the session boundary, including failed logins.
        if (snapshot.username != retroSnapshot.username ||
            snapshot.gameId != retroSnapshot.gameId ||
            snapshot.username.isBlank() || snapshot.gameId == 0) {
            clearIndicators()
            if (snapshot.username != retroSnapshot.username || snapshot.gameId != retroSnapshot.gameId) hidePopups()
        }
        retroSnapshot = snapshot
        if (progressReady) {
            retroProgress = progressNotifications.update(snapshot.entries, nowMillis())
            if (!preferences.retroProgressPopups) hideProgress()
        }
    }

    internal fun handleEvent(event: AchievementClient.Event) {
        if (event.kind == "progress_baseline") {
            progressNotifications.clear()
            retroProgress = null
            progressReady = true
        }
        if (event.kind == "progress_reset" || event.kind == "hardcore_reset") {
            clearIndicators()
        }
        // Progress events omit concurrent achievements; use the complete SDK
        // measurements instead. Challenges and trackers still follow HIDE.
        if (retroIndicatorState.handles(event.kind) && !event.kind.startsWith("progress_")) {
            retroIndicatorState.update(event.kind, event.extra, event.title, event.detail, event.image)
            retroIndicators = retroIndicatorState.labels
        }
        // Unlocks and completed games show their badge, a game that starts its icon.
        val banner = RetroNoticeText.of(event.kind, event.title, event.detail, event.extra)
        if (banner != null && (!banner.award || preferences.retroUnlockPopups) && retroUnlocks.size < MAX_QUEUED_UNLOCKS) {
            retroUnlocks.add(RetroPopup(banner, event.image))
        }
        if (event.kind == "login_error" || event.kind == "login") {
            retroBusy = false
        }
        if (event.kind == "hardcore_refused") onHardcoreRefused()
    }

    private fun clearIndicators() {
        retroIndicatorState.clear()
        progressNotifications.clear()
        progressReady = false
        retroIndicators = emptyList()
        retroProgress = null
    }

    fun hideProgress() {
        progressNotifications.dismiss()
        retroProgress = null
    }

    /** Sleep until the current turn ends; no polling while there is no popup. */
    suspend fun showProgress() {
        while (currentCoroutineContext().isActive) {
            snapshotFlow { retroProgress }.filterNotNull().first()
            val transitionAt = progressNotifications.nextTransitionAt ?: continue
            delay((transitionAt - nowMillis()).coerceAtLeast(1L))
            retroProgress = progressNotifications.advance(nowMillis())
        }
    }

    /** Loads the avatar and every badge of the set not loaded yet. */
    suspend fun loadImages() {
        // Every badge of the set: a cap of 120 left the badges of
        // larger sets blank. 64x64 badges stay small in memory.
        val urls = (listOf(retroSnapshot.avatarUrl, retroSnapshot.gameImageUrl) +
            retroSnapshot.entries.map { it.badgeUrl })
            .filter { it.isNotBlank() }.distinct().take(MAX_RETRO_IMAGES)
        for (batch in urls.chunked(12)) {
            val toLoad = batch.filter { it !in retroImages }
            if (toLoad.isEmpty()) continue
            val images = withContext(Dispatchers.IO) {
                toLoad.mapNotNull { raw ->
                    try {
                        retroBadgeStore.load(raw)?.asImageBitmap()?.let { raw to it }
                    } catch (error: Exception) {
                        Log.w("RavenEmuRetro", "Badge unavailable", error)
                        null
                    }
                }.toMap()
            }
            retroImages = retroImages + images
        }
    }

    suspend fun restore() {
        try {
            retro.restore()
        } catch (error: Exception) {
            Log.w("RavenEmuRetro", "Token restore failed", error)
            retroSnapshot = retroSnapshot.copy(
                error = "Connexion RetroAchievements indisponible.",
            )
        }
    }

    /**
     * The progress popup shows the achievement's badge; the set's
     * badges are usually loaded already.
     */
    suspend fun loadProgressBadge() {
        val url = retroProgress?.badgeUrl.orEmpty()
        if (url.isBlank() || url in retroImages) return
        val badge = withContext(Dispatchers.IO) {
            runCatching { retroBadgeStore.load(url) }.getOrNull()?.asImageBitmap()
        }
        if (badge != null) retroImages = retroImages + (url to badge)
    }

    /**
     * One banner at a time, each for the same duration, its motion included.
     * The badge is fetched before the banner opens, so it never changes while
     * shown; after 2 s without it, the star stands in.
     */
    suspend fun showUnlocks() {
        while (currentCoroutineContext().isActive) {
            val unlock = snapshotFlow { retroUnlocks.firstOrNull() }
                .filterNotNull().first()
            // The unlock event carries the unlocked badge URL; the
            // list may only hold the locked one so far.
            val url = unlock.badgeUrl
            var badge = retroImages[url]
            if (badge == null && url.isNotBlank()) {
                val download = scope.async(Dispatchers.IO) {
                    runCatching { retroBadgeStore.load(url) }.getOrNull()?.asImageBitmap()
                }
                badge = withTimeoutOrNull(2_000) { download.await() }
                if (badge != null) retroImages = retroImages + (url to badge)
            }
            if (retroUnlocks.firstOrNull() !== unlock) continue
            currentPopup = unlock
            retroNoticeBadge = badge
            retroNotice = unlock.banner
            retroNoticeVisible = true
            if (unlock.award && preferences.retroPopupSound) playSound()
            delay(preferences.retroPopupSeconds * 1000L - RetroBannerMotion.EXIT_MILLIS)
            if (retroUnlocks.firstOrNull() !== unlock) continue
            // Fold and leave with the same content, then clear it.
            retroNoticeVisible = false
            delay(RetroBannerMotion.EXIT_MILLIS + 50L)
            if (retroUnlocks.firstOrNull() === unlock) {
                hideNotice()
                retroUnlocks.removeAt(0)
            }
        }
    }

    /** Keeps the SDK ticking while no game frame drives it ([idle] is read each time). */
    suspend fun tickWhile(idle: () -> Boolean) {
        while (currentCoroutineContext().isActive && idle()) {
            try {
                retro.idle()
            } catch (error: Exception) {
                Log.w("RavenEmuRetro", "Background rcheevos tick failed", error)
            }
            delay(400)
        }
    }

    fun login(username: String, password: String) {
        if (!retroBusy) {
            retroBusy = true
            scope.launch {
                try {
                    retro.login(username, password)
                } catch (error: Exception) {
                    Log.w("RavenEmuRetro", "Login could not start", error)
                    retroSnapshot = retroSnapshot.copy(
                        error = error.message ?: "Connexion impossible",
                    )
                    retroBusy = false
                }
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try { retro.disconnect() }
            catch (error: Exception) {
                retroSnapshot = retroSnapshot.copy(
                    error = error.message ?: "Déconnexion impossible",
                )
            }
            retroBusy = false
        }
    }

    fun retry() {
        if (!retroBusy) {
            retroBusy = true
            scope.launch {
                try { retro.retry() }
                catch (error: Exception) {
                    retroSnapshot = retroSnapshot.copy(
                        error = error.message ?: "Réessai impossible",
                    )
                    retroBusy = false
                }
            }
        }
    }
}

/** Avatar plus badges: well above the largest RetroAchievements sets. */
private const val MAX_RETRO_IMAGES = 600

/** A burst beyond this is not worth minutes of popups; the list keeps them all. */
private const val MAX_QUEUED_UNLOCKS = 20

private class RetroPopup(val banner: RetroBanner, val badgeUrl: String) {
    val award: Boolean get() = banner.award
}
