package com.ravenemu.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.view.InputDevice
import android.view.KeyEvent
import android.view.Window
import android.view.MotionEvent
import android.util.Log
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.ravenemu.android.deltaskin.DeltaSkinControllerConfiguration
import com.ravenemu.android.deltaskin.DeltaSkinPlayer
import com.ravenemu.shared.player.GbButton
import com.ravenemu.shared.RavenEmuApp
import com.ravenemu.shared.library.GamePlatform
import com.ravenemu.shared.player.GbCheatsView
import com.ravenemu.android.deltaskin.DeltaSkinsUi
import com.ravenemu.android.engine.AndroidGbSession
import com.ravenemu.android.library.GbCoversUi
import com.ravenemu.android.library.GbLibraryUi
import com.ravenemu.android.library.GbRomLibrary
import com.ravenemu.android.library.LibraryOperationGate
import com.ravenemu.android.link.AndroidLinkSession
import com.ravenemu.android.link.LinkPlayback
import com.ravenemu.android.player.GameWindow
import com.ravenemu.android.player.GamepadMapper
import com.ravenemu.android.player.GbPlayback
import com.ravenemu.android.player.GbPlayerUi
import com.ravenemu.android.player.GbScreenshot
import com.ravenemu.android.retro.AchievementsController
import com.ravenemu.android.retro.HardcoreAvailability
import com.ravenemu.android.retro.RetroAchievementsUi
import com.ravenemu.android.saves.GbSaveTransfer
import com.ravenemu.android.saves.GbSaveTransfersUi
import com.ravenemu.android.saves.GbCheatStore
import com.ravenemu.android.saves.GbCheatsUi
import com.ravenemu.android.saves.GbStateSlotsUi
import com.ravenemu.android.saves.GbaBiosStore
import com.ravenemu.android.settings.AndroidSettingsStore
import com.ravenemu.android.wireless.LocalNetworkAccess
import com.ravenemu.android.wireless.WirelessLink

class MainActivity : ComponentActivity() {
    private lateinit var gameSession: AndroidGbSession
    private lateinit var playback: GbPlayback
    // Two games side by side, joined by a virtual link cable.
    private lateinit var linkSession: AndroidLinkSession
    private lateinit var linkPlayback: LinkPlayback
    // The single game's wireless cable, to another phone on the same network.
    private lateinit var wireless: WirelessLink
    private var linkInputEnabled = false
    private var retroController: AchievementsController? = null
    // The player of setContent, for the controller keys handled here.
    private var playerUi: GbPlayerUi? = null
    private var foreground by mutableStateOf(false)
    // Physical controller. Its events are taken only while the player accepts
    // input; elsewhere the D-pad keeps navigating the app as usual.
    private val gamepad = GamepadMapper()
    private var gamepadInputEnabled = false
    private var gamepadInUse by mutableStateOf(false)
    // Bumped by the controller's Home key to open the game menu.
    private var gameMenuRequests by mutableStateOf(0)

    private fun handleGamepadKeyEvent(event: KeyEvent): Boolean {
        if (gamepadInputEnabled && foreground && event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) gameMenuRequests++
            return true
        }
        // The right stick's click, free on the console, toggles fast forward;
        // the left one, held, rewinds.
        if (gamepadInputEnabled && foreground && event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) playerUi?.toggleFastForward()
            return true
        }
        if (gamepadInputEnabled && foreground && event.keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) playerUi?.holdRewind(true)
            if (event.action == KeyEvent.ACTION_UP) playerUi?.holdRewind(false)
            return true
        }
        if (gamepadInputEnabled && foreground && gamepad.handles(event.keyCode)) {
            gamepad.key(event.keyCode, event.action, event.repeatCount)?.let { (button, pressed) ->
                setButton(button, pressed)
            }
            gamepadInUse = true
            return true
        }
        return false
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (gamepadInputEnabled && foreground &&
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val changes = gamepad.axes(
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y),
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y),
            )
            for ((button, pressed) in changes) setButton(button, pressed)
            if (changes.isNotEmpty()) gamepadInUse = true
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // A physical controller drives the linked game in focus, or the single game.
    private fun setButton(button: GbButton, pressed: Boolean) {
        if (linkInputEnabled) linkSession.setButton(button, pressed)
        else gameSession.setButton(button, pressed)
    }

    override fun onStart() {
        super.onStart()
        wireless.setVisible(true)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        playback.activeAudio?.stopNow()
        linkPlayback.activeAudio?.stopNow()
        gameSession.releaseAllButtons()
        linkSession.releaseAllButtons()
        gamepad.reset()
        // A lifecycle stop can terminate the process soon after this callback;
        // commit changed SRAM before returning rather than relying on a job.
        // A frame may wait for the wireless partner: onStop saves after
        // unplugging if the console stays busy here.
        saveGames(waitMillis = PAUSE_SAVE_WAIT_MILLIS)
        super.onPause()
    }

    override fun onStop() {
        // In the background, the wireless cable comes out: the partner's game
        // shows its own link error rather than waiting for this one.
        gameSession.unplugRemoteLink()
        wireless.setVisible(false)
        saveGames(waitMillis = null)
        super.onStop()
    }

    private fun saveGames(waitMillis: Long?) {
        try {
            if (waitMillis == null) gameSession.saveBatteryIfDirty()
            else gameSession.saveBatteryIfDirty(waitMillis)
            linkSession.saveBatteriesIfDirty()
        } catch (error: Exception) {
            Log.e("RavenEmuSaves", "Cannot save game on pause", error)
            Toast.makeText(this, "Échec de la sauvegarde du jeu", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        // Window.Callback is the public event-dispatch contract. Calling
        // ComponentActivity's AndroidX-internal dispatch override fails Lint.
        val activityCallback = window.callback
        window.callback = object : Window.Callback by activityCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                handleGamepadKeyEvent(event) || activityCallback.dispatchKeyEvent(event)
        }
        gameSession = AndroidGbSession(contentResolver, filesDir)
        playback = GbPlayback(applicationContext, gameSession)
        linkSession = AndroidLinkSession(filesDir)
        linkPlayback = LinkPlayback(applicationContext, linkSession)
        wireless = WirelessLink(applicationContext, gameSession)

        setContent {
            val settingsStore = remember { AndroidSettingsStore(applicationContext) }
            var userSettings by remember { mutableStateOf(settingsStore.read()) }
            val romLibrary = remember { GbRomLibrary(File(filesDir, "gb-library"), contentResolver) }
            val saveTransfer = remember { GbSaveTransfer(filesDir) }
            val scope = rememberCoroutineScope()
            val libraryOperations = remember { LibraryOperationGate() }
            val covers = remember {
                GbCoversUi(applicationContext, filesDir, contentResolver, romLibrary, scope)
            }
            val appVersion = remember {
                runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "0.1.0 - Bêta"
            }
            val saveTransfers = remember {
                GbSaveTransfersUi(
                    applicationContext, contentResolver, gameSession, linkSession, libraryOperations,
                    romLibrary, saveTransfer, scope,
                )
            }
            val slots = remember { GbStateSlotsUi(applicationContext, gameSession, playback, scope) }
            val cheats = remember {
                GbCheatsUi(applicationContext, gameSession, GbCheatStore(File(filesDir, "gb-cheats")), scope)
            }
            val gbaBios = remember { GbaBiosStore(File(filesDir, "bios")) }
            var gbaBiosInstalled by remember { mutableStateOf(gbaBios.bytes() != null) }
            val retroUi = remember {
                RetroAchievementsUi(applicationContext, gameSession, scope, playSound = {
                    (playback.activeAudio ?: linkPlayback.activeAudio)?.playChime()
                }).also {
                    retroController = it.retro
                    // A refused Hardcore stays off: the next launch must not ask again.
                    it.onHardcoreRefused = {
                        userSettings = userSettings.copy(retroHardcore = false).normalized()
                        settingsStore.write(userSettings)
                    }
                }
            }
            val retro = retroUi.retro
            val player = remember {
                GbPlayerUi(gameSession, playback, linkSession, linkPlayback, slots, retro, wireless, scope) { foreground }
                    .also { playerUi = it }
            }
            val library = remember {
                GbLibraryUi(
                    applicationContext, contentResolver, romLibrary, covers, gameSession, linkSession,
                    retro, player, libraryOperations, scope,
                )
            }
            val skins = remember { DeltaSkinsUi(applicationContext, scope) }

            library.Effects(userSettings.onlineCovers)
            // Settings > Console: read by the session at each load; the colors change at once.
            val console = remember(userSettings.gbInColor, userSettings.gbColors, userSettings.gbcOnGba,
                userSettings.gbaBios, gbaBiosInstalled) {
                AndroidGbSession.ConsoleOptions(
                    gbInColor = userSettings.gbInColor,
                    gbColors = userSettings.gbColors,
                    gbcOnGba = userSettings.gbcOnGba,
                    gbaBios = if (userSettings.gbaBios != 0) gbaBios.bytes() else null,
                    skipBiosIntro = userSettings.gbaBios == 2,
                )
            }
            SideEffect { gameSession.console = console }
            LaunchedEffect(userSettings.gbInColor, userSettings.gbcOnGba, userSettings.gbaBios, gbaBiosInstalled) {
                library.reloadOnNextLaunch()
            }
            LaunchedEffect(userSettings.gbColors) {
                withContext(Dispatchers.IO) { gameSession.applyGbColors(userSettings.gbColors) }
            }
            // The running game's cheats, and a word when the real BIOS is wanted but missing.
            LaunchedEffect(library.loadedGame, player.videoEnabled) {
                val game = library.loadedGame.takeIf { player.videoEnabled }
                cheats.open(game)
                if (game?.platform == GamePlatform.GBA && userSettings.gbaBios != 0 && gbaBios.bytes() == null) {
                    Toast.makeText(applicationContext,
                        "Vrai BIOS GBA absent : RavenCore utilise le sien. Choisissez son fichier dans Paramètres → Console.",
                        Toast.LENGTH_LONG).show()
                }
            }
            ControllerLayout(player.controllerLayout)
            GameWindow(
                window,
                playing = player.open,
                running = player.open && !player.pausedForGameMenu,
                gameFps = when {
                    player.videoEnabled && library.loadedGame != null -> gameSession.refreshRateHz()
                    player.linkEnabled -> linkSession.refreshRateHz()
                    else -> null
                },
            )
            // The controller reaches the game only while it runs on screen.
            val acceptsController = player.acceptsController
            val linkInput = player.linkEnabled
            SideEffect {
                if (!acceptsController && gamepadInputEnabled) gamepad.reset()
                gamepadInputEnabled = acceptsController
                linkInputEnabled = linkInput
            }
            retroUi.Effects(userSettings.retroHardcore, player, foreground)
            SideEffect { retroUi.configure(userSettings) }
            player.Effects(library.loadedGame, userSettings, foreground)
            skins.Effects(library.loadedGame?.platform, userSettings.haptics)

            val exportAudio = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("text/plain"),
            ) { uri -> if (uri != null) writeAudioReport(uri) }
            val folderPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree(),
            ) { uri -> library.importFolder(uri) }
            val documentPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri -> library.importRom(uri) }
            // Android 17 : le câble sans fil demande l'accès au réseau local.
            val localNetwork = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
            ) { granted -> wireless.onLocalNetworkPermission(granted) }
            val localNetworkRequest = wireless.localNetworkRequest
            LaunchedEffect(localNetworkRequest) {
                if (localNetworkRequest) localNetwork.launch(LocalNetworkAccess.PERMISSION)
            }
            skins.skinPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri -> skins.import(uri) }
            val biosPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) scope.launch {
                    val message = try {
                        withContext(Dispatchers.IO) { gbaBios.install(contentResolver, uri) }
                        "BIOS GBA installé"
                    } catch (error: Exception) {
                        error.message ?: "BIOS GBA non installé"
                    }
                    gbaBiosInstalled = gbaBios.bytes() != null
                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                }
            }
            covers.coverPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri -> covers.saveChosen(uri) }
            saveTransfers.exportBackup = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri -> saveTransfers.writeBackup(uri) }
            saveTransfers.exportBattery = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri -> saveTransfers.writeBattery(uri) }
            saveTransfers.importSavePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri -> saveTransfers.reviewImport(uri, library.games) }

            val activeSkin = skins.configuration
            // A save slot acts on the game only while it is loaded and on screen.
            fun playing() = library.loadedGame != null && player.videoEnabled

            RavenEmuApp(
                retroSnapshot = retroUi.retroSnapshot,
                retroImages = retroUi.retroImages,
                retroBusy = retroUi.retroBusy,
                retroNotice = retroUi.retroNotice,
                retroNoticeBadge = retroUi.retroNoticeBadge,
                retroNoticeVisible = retroUi.retroNoticeVisible,
                retroIndicators = retroUi.retroIndicators,
                retroProgress = retroUi.retroProgress,
                hardcoreAvailable = HardcoreAvailability.APPROVED,
                // Hardcore forbids restoring a save state (saving stays allowed),
                // and so does the wireless cable: the partner would not follow.
                stateLoadAllowed = !retroUi.retroSnapshot.hardcore && !wireless.linked,
                wirelessLink = wireless.view,
                onWirelessLinkChoice = { id -> wireless.choose(id) },
                onWirelessUnplug = { wireless.unplug() },
                onRequestGameMenu = { requestGameMenu(retro) },
                onRetroLogin = { username, password -> retroUi.login(username, password) },
                onRetroDisconnect = { retroUi.disconnect() },
                onRetroRetry = { retroUi.retry() },
                onRetroLeaderboard = { id, around -> retroUi.fetchLeaderboard(id, around) },
                settings = userSettings,
                onSettingsChange = { updated ->
                    userSettings = updated.normalized()
                    settingsStore.write(userSettings)
                },
                appVersion = appVersion,
                onOpenLink = ::openLink,
                onImportRom = {
                    if (!libraryOperations.isBusy()) documentPicker.launch(arrayOf("*/*"))
                },
                onImportFolder = {
                    if (!libraryOperations.isBusy() && !player.open) folderPicker.launch(null)
                },
                onRefreshLibrary = { library.refresh() },
                folderImportProgress = library.folderImportProgress,
                folderImportSummary = library.folderImportSummary,
                onDismissFolderImportSummary = { library.dismissFolderImportSummary() },
                loadedGame = library.loadedGame,
                libraryGames = library.games,
                saveTransferBusy = saveTransfers.saveTransferBusy,
                saveImportReview = saveTransfers.saveImportReview,
                onCancelSaveImport = { saveTransfers.cancelImport() },
                onConfirmSaveImport = {
                    saveTransfers.confirmImport(playerOpen = player.open) { romKey ->
                        library.forgetLoaded(romKey)
                    }
                },
                onExportAllSaves = {
                    saveTransfers.exportAllSaves(library.games, playerOpen = player.open)
                },
                onExportBatterySave = { id, format ->
                    saveTransfers.exportBatterySave(id, format, library.games, playerOpen = player.open)
                },
                onImportGameSave = { id ->
                    saveTransfers.importGameSave(id, library.games, playerOpen = player.open)
                },
                libraryCovers = covers.libraryCovers,
                coverBusy = covers.coverBusy || covers.autoCoverLoading,
                onChooseCover = { id -> covers.choose(id, library.games) },
                onRemoveCover = { id -> covers.remove(id, library.games) },
                onLaunchLibraryGame = { id, onReady -> library.launch(id, onReady) },
                importBusy = library.loading,
                importError = library.importError,
                onLaunchLinkedGames = { firstId, secondId, onReady ->
                    library.launchLinked(firstId, secondId, onReady)
                },
                onStartLinked = { player.startLinked() },
                onStopLinked = { player.stopLinked() },
                linkedGames = player.linkedGames,
                linkFrames = listOf(linkPlayback.firstFrame, linkPlayback.secondFrame),
                linkFocus = player.linkFocus,
                onLinkFocus = { index -> player.focusLinked(index) },
                linkError = linkPlayback.error,
                linkMetrics = linkPlayback.metrics,
                onStartVideo = { player.startVideo() },
                onStopVideo = { player.stopVideo() },
                skinControls = if (activeSkin == null) null else @Composable { image: ImageBitmap?, openMenu: () -> Unit ->
                    val configuration: DeltaSkinControllerConfiguration = activeSkin
                    DeltaSkinPlayer(
                        configuration = configuration,
                        frame = image,
                        onButtons = { changes -> player.setSkinButtons(changes) },
                        onMenu = openMenu,
                        onError = { code ->
                            skins.fallBack(code, library.loadedGame?.platform, configuration)
                            gameSession.releaseAllButtons()
                        },
                    )
                },
                deltaSkins = skins.entries,
                onImportSkin = { skins.skinPicker.launch(arrayOf("*/*")) },
                onToggleSkin = { id -> skins.toggle(id) },
                onDeleteSkin = { id -> skins.delete(id) },
                gbaBiosInstalled = gbaBiosInstalled,
                onChooseGbaBios = { biosPicker.launch(arrayOf("*/*")) },
                onRemoveGbaBios = {
                    gbaBios.remove()
                    gbaBiosInstalled = false
                },
                cheats = GbCheatsView(
                    cheats = cheats.cheats,
                    allowed = !retroUi.retroSnapshot.hardcore,
                    onAdd = { name, code -> cheats.add(name, code) },
                    onToggle = { index -> cheats.toggle(index) },
                    onDelete = { index -> cheats.delete(index) },
                ),
                touchControlsVisible = !gamepadInUse,
                controllerMode = player.controllerMode,
                onControllerModeChange = { player.controllerMode = it },
                gameMenuRequests = gameMenuRequests,
                // Touching the screen brings the touch controls back.
                onScreenTouch = { gamepadInUse = false },
                onButtonChange = { button: GbButton, pressed: Boolean ->
                    player.setTouchButton(button, pressed)
                },
                videoFrame = playback.frame,
                videoError = playback.videoError,
                videoMetrics = playback.videoMetrics,
                onExportAudioDiagnostics = {
                    playback.lastAudioReport = playback.activeAudio?.exportReport() ?: playback.lastAudioReport
                    exportAudio.launch("ravenemu-audio-diagnostic.txt")
                },
                onGameMenuVisibilityChange = { open -> player.setGameMenuVisible(open) },
                stateSlots = slots.stateSlots,
                stateSlotsLoading = slots.stateSlotsLoading,
                stateOperationBusy = slots.stateOperationBusy,
                onSaveState = { slot -> slots.save(slot, playing = playing()) },
                onLoadState = { slot -> slots.load(slot, playing = playing(), retro) },
                onDeleteState = { slot -> slots.delete(slot, playing = playing()) },
                loadUndoable = slots.loadUndoable,
                onUndoLoadState = { slots.undoLoad(playing = playing(), retro) },
                // Shown as it runs: never while the wireless cable is linked.
                fastForward = player.fastForward && !wireless.linked,
                onFastForwardChange = { on -> player.requestFastForward(on) },
                // Rewind is neither for Hardcore nor for the wireless cable.
                rewinding = player.rewinding && userSettings.rewind && !wireless.linked &&
                    !retroUi.retroSnapshot.hardcore,
                rewindEnd = playback.rewindEnd,
                onRewindHold = if (userSettings.rewind && !wireless.linked && !retroUi.retroSnapshot.hardcore) {
                    { held -> player.holdRewind(held) }
                } else null,
                onScreenshot = {
                    library.loadedGame?.let { game ->
                        GbScreenshot.capture(applicationContext, scope, playback.frame, game.filename)
                    }
                },
            )
        }
    }

    // Controller mode: landscape and full screen while a game runs.
    @Composable
    private fun ControllerLayout(enabled: Boolean) {
        LaunchedEffect(enabled) {
            requestedOrientation = if (enabled) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            WindowCompat.getInsetsController(window, window.decorView).apply {
                if (enabled) {
                    systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                } else {
                    show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
    }

    private fun requestGameMenu(retro: AchievementsController): Boolean =
        retro.requestPause().also { allowed ->
            if (!allowed) Toast.makeText(
                applicationContext,
                "Mode Hardcore : pause indisponible pour le moment.",
                Toast.LENGTH_SHORT,
            ).show()
        }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (error: Exception) {
            Log.e("RavenEmuSettings", "Cannot open information link", error)
            Toast.makeText(
                applicationContext, "Impossible d'ouvrir le lien",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun writeAudioReport(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(playback.lastAudioReport.toByteArray(Charsets.UTF_8))
            } ?: Log.e("RavenEmuAudio", "Unable to open diagnostic destination")
        } catch (error: Exception) {
            Log.e("RavenEmuAudio", "Unable to save audio report", error)
        }
    }

    override fun onDestroy() {
        playback.activeAudio?.stopNow()
        linkPlayback.activeAudio?.stopNow()
        retroController?.close()
        wireless.close()
        gameSession.close()
        linkSession.close()
        super.onDestroy()
    }

    private companion object {
        // The UI thread gives a busy console this long before onStop saves.
        const val PAUSE_SAVE_WAIT_MILLIS = 300L
    }
}
