package com.ravenemu.android.player

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ravenemu.android.deltaskin.DeltaSkinButtonChanges
import com.ravenemu.shared.player.GbButton
import com.ravenemu.shared.library.LoadedGameInfo
import com.ravenemu.shared.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.ravenemu.android.engine.AndroidGbSession
import com.ravenemu.android.link.AndroidLinkSession
import com.ravenemu.android.link.LinkPlayback
import com.ravenemu.android.retro.AchievementsController
import com.ravenemu.android.saves.GbStateSlotsUi
import com.ravenemu.android.wireless.WirelessLink

/**
 * The player as the screens show it: the single game on screen, the linked
 * pair, the game menu and the controller layout. Buttons reach a game only
 * while it runs on screen and [isForeground] is true.
 */
internal class GbPlayerUi(
    private val gameSession: AndroidGbSession,
    private val playback: GbPlayback,
    private val linkSession: AndroidLinkSession,
    private val linkPlayback: LinkPlayback,
    private val slots: GbStateSlotsUi,
    private val retro: AchievementsController,
    private val wireless: WirelessLink,
    private val scope: CoroutineScope,
    private val isForeground: () -> Boolean,
) {
    var videoEnabled by mutableStateOf(false)
        private set
    // Linked pair: both games loaded, then playing on the link screen.
    var linkedGames by mutableStateOf(emptyList<LoadedGameInfo>())
        private set
    var linkEnabled by mutableStateOf(false)
        private set
    var linkFocus by mutableStateOf(0)
        private set
    var pausedForGameMenu by mutableStateOf(false)
        private set
    // Controller mode: landscape and full screen while a game runs.
    var controllerMode by mutableStateOf(false)
    /** Fast forward of the single game, from the top bar, its menu or the controller. */
    var fastForward by mutableStateOf(false)
        private set

    /** A game or a linked pair is on screen: the library waits. */
    val open: Boolean get() = videoEnabled || linkEnabled
    val controllerLayout: Boolean get() = controllerMode && videoEnabled
    /** The physical controller reaches the game only while it runs on screen. */
    val acceptsController: Boolean get() = (videoEnabled && !pausedForGameMenu) || linkEnabled
    /** The single game runs: on screen, its menu closed, the app in the foreground. */
    val gameRunning: Boolean get() = videoEnabled && isForeground() && !pausedForGameMenu

    /**
     * Runs [game] while it is on screen, the app in the foreground and its
     * menu closed; the linked pair runs the same way.
     */
    @Composable
    fun Effects(game: LoadedGameInfo?, settings: Settings, foreground: Boolean) {
        LaunchedEffect(
            videoEnabled, game, foreground,
            slots.restoringState, slots.stateRevision, pausedForGameMenu,
        ) {
            if (!videoEnabled || game == null) {
                playback.frame = null
                playback.videoMetrics = null
                return@LaunchedEffect
            }
            // Keep the last image visible behind the ROM-style menu sheet.
            // A game menu pauses both the native clock and AudioTrack,
            // preventing UI interaction from building an audio backlog.
            if (!foreground || slots.restoringState || pausedForGameMenu) return@LaunchedEffect
            playback.run(game, settings, retro, scope) { wireless.partnerPaused }
        }

        // The wireless cable follows the single game on screen; the linked
        // pair has its own cable.
        LaunchedEffect(videoEnabled, game, settings.wirelessLink) {
            wireless.setGame(if (videoEnabled) game else null, settings.wirelessLink)
        }
        val stopped = !foreground || slots.restoringState || pausedForGameMenu
        LaunchedEffect(stopped) { wireless.setPaused(stopped) }
        // A wireless partner would not keep up: the game runs at its pace then.
        val fastForwardOn = fastForward && !wireless.linked
        SideEffect { playback.fastForward = fastForwardOn }

        LaunchedEffect(linkEnabled, linkedGames, foreground) {
            if (!linkEnabled || linkedGames.size != 2) {
                linkPlayback.clear()
                return@LaunchedEffect
            }
            // In the background, the last pictures stay and the clock stops.
            if (!foreground) return@LaunchedEffect
            linkPlayback.run(linkedGames, settings, scope)
        }
    }

    fun startVideo() {
        videoEnabled = true
        // The game's RetroAchievements session lives while the player is open.
        scope.launch {
            try { retro.resumeGame() }
            catch (error: Exception) {
                Log.w("RavenEmuRetro", "RA game session could not start", error)
            }
        }
    }

    fun stopVideo() {
        scope.launch {
            try { retro.leaveGame() }
            catch (error: Exception) {
                Log.w("RavenEmuRetro", "RA game session could not end", error)
            }
        }
        playback.activeAudio?.stopNow()
        gameSession.releaseAllButtons()
        videoEnabled = false
        pausedForGameMenu = false
        fastForward = false
        playback.frame = null
        playback.videoMetrics = null
        playback.audioStatus = null
        scope.launch(Dispatchers.IO) {
            try {
                gameSession.saveBatteryIfDirty()
            } catch (error: Exception) {
                Log.e("RavenEmuSaves", "Battery save on player exit failed", error)
            }
        }
    }

    /** The pair just loaded, the first game in focus. */
    fun showLinked(games: List<LoadedGameInfo>) {
        linkedGames = games
        linkFocus = 0
    }

    fun startLinked() {
        linkEnabled = true
    }

    fun stopLinked() {
        linkPlayback.activeAudio?.stopNow()
        linkSession.releaseAllButtons()
        linkEnabled = false
        linkedGames = emptyList()
        linkPlayback.clear()
        // Only this pair: a new one may be loaded before the job runs.
        val pair = linkSession.pairId
        scope.launch(Dispatchers.IO) {
            try {
                linkSession.unload(pair)
            } catch (error: Exception) {
                // The pair stays in memory; the next pause or launch retries.
                Log.e("RavenEmuSaves", "Battery save on link exit failed", error)
            }
        }
    }

    /** Only the single game on screen fast-forwards. */
    fun setFastForward(on: Boolean) {
        fastForward = on && videoEnabled
    }

    fun toggleFastForward() = setFastForward(!fastForward)

    fun focusLinked(index: Int) {
        linkSession.setFocus(index)
        linkFocus = index
    }

    /** A touch button: the linked game in focus, or the single game. */
    fun setTouchButton(button: GbButton, pressed: Boolean) {
        if (linkEnabled && isForeground()) {
            linkSession.setButton(button, pressed)
        } else if (gameRunning) {
            gameSession.setButton(button, pressed)
        }
    }

    /** The buttons of a Delta skin, which only the single game shows. */
    fun setSkinButtons(changes: DeltaSkinButtonChanges) {
        if (!gameRunning) return
        for (button in changes.released) gameSession.setButton(button, false)
        for (button in changes.pressed) gameSession.setButton(button, true)
    }

    fun setGameMenuVisible(visible: Boolean) {
        if (visible) {
            slots.refreshSlots()
            // Preserve a useful report before closing the track.
            playback.activeAudio?.let {
                playback.lastAudioReport = it.exportReport()
                it.stopNow()
            }
            gameSession.releaseAllButtons()
        }
        pausedForGameMenu = visible
    }
}
