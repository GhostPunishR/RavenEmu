package com.ravenemu.android

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.ravenemu.shared.RetroIndicatorState
import com.ravenemu.shared.RetroNoticeText
import com.ravenemu.shared.RetroProgress
import com.ravenemu.shared.RetroSnapshot
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

/**
 * RetroAchievements as the screens show it: account snapshot, badges, the
 * popups of unlocks and the indicators of the running game. The suspending
 * functions are the activity's effects; [retro] talks to the official SDK.
 */
internal class RetroAchievementsUi(
    applicationContext: Context,
    gameSession: AndroidGbSession,
    private val scope: CoroutineScope,
) {
    var retroSnapshot by mutableStateOf(RetroSnapshot())
        private set
    var retroImages by mutableStateOf(emptyMap<String, ImageBitmap>())
        private set
    var retroBusy by mutableStateOf(false)
        private set
    var retroNotice by mutableStateOf<String?>(null)
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
    // Challenges, measured progress and leaderboard trackers stay on
    // screen until the SDK hides them (RA compliance requirement).
    private val retroIndicatorState = RetroIndicatorState()
    val retro = AchievementsController(
        applicationContext, gameSession,
        onSnapshot = { snapshot -> retroSnapshot = snapshot },
        onEvent = { event ->
            if (event.kind == "login" || event.kind == "game") {
                // A new session or game: earlier indicators are void.
                retroIndicatorState.clear()
                retroIndicators = emptyList()
                retroProgress = null
            }
            if (retroIndicatorState.handles(event.kind)) {
                retroIndicatorState.update(event.kind, event.extra, event.title, event.detail, event.image)
                retroIndicators = retroIndicatorState.labels
                retroProgress = retroIndicatorState.progress
            }
            val popup = RetroNoticeText.of(event.kind, event.title, event.detail, event.extra)
            if (popup != null && retroUnlocks.size < MAX_QUEUED_UNLOCKS) {
                // Only an unlock has a badge; other notices keep the star.
                val badge = if (event.kind == "achievement") event.image else ""
                retroUnlocks.add(RetroPopup(popup, badge))
            }
            if (event.kind == "login_error" || event.kind == "login") {
                retroBusy = false
            }
        },
    )

    /** Loads the avatar and every badge of the set not loaded yet. */
    suspend fun loadImages() {
        // Every badge of the set: a cap of 120 left the badges of
        // larger sets blank. 64x64 badges stay small in memory.
        val urls = (listOf(retroSnapshot.avatarUrl) +
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
     * One popup at a time, each for the same duration. The badge is
     * fetched before the popup opens, so it never changes size while
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
            retroNoticeBadge = badge
            retroNotice = unlock.text
            retroNoticeVisible = true
            delay(4_800)
            // Fade out with the same content, then clear it.
            retroNoticeVisible = false
            delay(400)
            retroNotice = null
            retroNoticeBadge = null
            retroUnlocks.removeAt(0)
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

private data class RetroPopup(val text: String, val badgeUrl: String)
