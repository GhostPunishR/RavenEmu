package com.ravenemu.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.Log
import android.widget.Toast
import java.io.File
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
import androidx.compose.ui.graphics.asImageBitmap
import com.ravenemu.android.deltaskin.DeltaSkinControllerConfiguration
import com.ravenemu.android.deltaskin.DeltaSkinPlayer
import com.ravenemu.android.deltaskin.DeltaSkins
import com.ravenemu.shared.DeltaSkinEntry
import com.ravenemu.shared.GameEntry
import com.ravenemu.shared.GbFolderImportSummary
import com.ravenemu.shared.GbButton
import com.ravenemu.shared.LoadedGameInfo
import com.ravenemu.shared.RavenEmuApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var gameSession: AndroidGbSession
    private lateinit var playback: GbPlayback
    private var retroController: AchievementsController? = null
    private var foreground by mutableStateOf(false)
    // Physical controller. Its events are taken only while the player accepts
    // input; elsewhere the D-pad keeps navigating the app as usual.
    private val gamepad = GamepadMapper()
    private var gamepadInputEnabled = false
    private var gamepadInUse by mutableStateOf(false)
    // Bumped by the controller's Home key to open the game menu.
    private var gameMenuRequests by mutableStateOf(0)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (gamepadInputEnabled && foreground && event.keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) gameMenuRequests++
            return true
        }
        if (gamepadInputEnabled && foreground && gamepad.handles(event.keyCode)) {
            gamepad.key(event.keyCode, event.action, event.repeatCount)?.let { (button, pressed) ->
                gameSession.setButton(button, pressed)
            }
            gamepadInUse = true
            return true
        }
        return super.dispatchKeyEvent(event)
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
            for ((button, pressed) in changes) gameSession.setButton(button, pressed)
            if (changes.isNotEmpty()) gamepadInUse = true
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onResume() {
        super.onResume()
        foreground = true
    }

    override fun onPause() {
        foreground = false
        playback.activeAudio?.stopNow()
        gameSession.releaseAllButtons()
        gamepad.reset()
        // A lifecycle stop can terminate the process soon after this callback;
        // commit changed SRAM before returning rather than relying on a job.
        try {
            gameSession.saveBatteryIfDirty()
        } catch (error: Exception) {
            Log.e("RavenEmuSaves", "Cannot save game on pause", error)
            Toast.makeText(this, "Échec de la sauvegarde du jeu", Toast.LENGTH_LONG).show()
        }
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
        )
        gameSession = AndroidGbSession(contentResolver, filesDir)
        playback = GbPlayback(applicationContext, gameSession)

        setContent {
            val settingsStore = remember { AndroidSettingsStore(applicationContext) }
            var userSettings by remember { mutableStateOf(settingsStore.read()) }
            val romLibrary = remember { GbRomLibrary(File(filesDir, "gb-library"), contentResolver) }
            val saveTransfer = remember { GbSaveTransfer(filesDir) }
            val scope = rememberCoroutineScope()
            val covers = remember {
                GbCoversUi(applicationContext, filesDir, contentResolver, romLibrary, scope)
            }
            var libraryGames by remember { mutableStateOf(emptyList<GameEntry>()) }
            var activeGameId by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(romLibrary) {
                try {
                    val (games, coverImages) = withContext(Dispatchers.IO) {
                        val entries = romLibrary.list()
                        val pictures = entries.mapNotNull { entry ->
                            try {
                                covers.coverStore.read(entry.id)?.asImageBitmap()?.let { entry.id to it }
                            } catch (error: Exception) {
                                Log.w("RavenEmuLibrary", "Cannot read cover for ${entry.id.take(12)}", error)
                                null
                            }
                        }.toMap()
                        entries.map { GameEntry(it.id, it.filename, it.platform) } to pictures
                    }
                    libraryGames = games
                    covers.libraryCovers = coverImages
                } catch (error: Exception) {
                    Log.e("RavenEmuLibrary", "Cannot open library index", error)
                }
            }
            // Only run a cover search when the user enabled it, and then only
            // when the library changes or an excluded ROM is explicitly
            // re-enabled. Recomposition/video frames never initiate network
            // calls.
            LaunchedEffect(libraryGames, covers.ignoredAutoCovers, userSettings.onlineCovers) {
                covers.fillMissingOnline(libraryGames, userSettings.onlineCovers)
            }
            val appVersion = remember {
                runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull() ?: "0.1.0 - Bêta"
            }
            var loadedGame by remember { mutableStateOf<LoadedGameInfo?>(null) }
            var loading by remember { mutableStateOf(false) }
            var importError by remember { mutableStateOf<String?>(null) }
            var folderImportProgress by remember { mutableStateOf<String?>(null) }
            var folderImportSummary by remember { mutableStateOf<GbFolderImportSummary?>(null) }
            val saveTransfers = remember {
                GbSaveTransfersUi(
                    applicationContext, contentResolver, gameSession, romLibrary, saveTransfer, scope,
                )
            }
            var videoEnabled by remember { mutableStateOf(false) }
            val slots = remember { GbStateSlotsUi(applicationContext, gameSession, playback, scope) }
            var pausedForGameMenu by remember { mutableStateOf(false) }
            // Controller mode: landscape and full screen while a game runs.
            var controllerMode by remember { mutableStateOf(false) }
            val controllerLayout = controllerMode && videoEnabled
            LaunchedEffect(controllerLayout) {
                requestedOrientation = if (controllerLayout) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    if (controllerLayout) {
                        systemBarsBehavior =
                            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    } else {
                        show(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
            // The controller reaches the game only while it runs on screen.
            SideEffect {
                val accepts = videoEnabled && !pausedForGameMenu
                if (!accepts && gamepadInputEnabled) gamepad.reset()
                gamepadInputEnabled = accepts
            }
            val retroUi = remember {
                RetroAchievementsUi(applicationContext, gameSession, scope)
                    .also { retroController = it.retro }
            }
            val retro = retroUi.retro
            LaunchedEffect(retroUi.retroSnapshot.avatarUrl,
                retroUi.retroSnapshot.entries.map { it.badgeUrl }.distinct()) {
                retroUi.loadImages()
            }
            // Effective only once RetroAchievements approved the app.
            LaunchedEffect(retro, userSettings.retroHardcore) {
                runCatching { retro.setHardcore(userSettings.retroHardcore) }
                    .onFailure { Log.w("RavenEmuRetro", "Hardcore switch failed", it) }
            }
            LaunchedEffect(retro) { retroUi.restore() }
            LaunchedEffect(retroUi.retroProgress?.badgeUrl) { retroUi.loadProgressBadge() }
            LaunchedEffect(Unit) { retroUi.showUnlocks() }
            LaunchedEffect(retro, videoEnabled, pausedForGameMenu, foreground) {
                retroUi.tickWhile { !videoEnabled || pausedForGameMenu || !foreground }
            }
            val exportAudio = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("text/plain"),
            ) { uri ->
                if (uri != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(playback.lastAudioReport.toByteArray(Charsets.UTF_8))
                        } ?: Log.e("RavenEmuAudio", "Unable to open diagnostic destination")
                    } catch (error: Exception) {
                        Log.e("RavenEmuAudio", "Unable to save audio report", error)
                    }
                }
            }

            LaunchedEffect(
                videoEnabled, loadedGame, foreground,
                slots.restoringState, slots.stateRevision, pausedForGameMenu,
            ) {
                if (!videoEnabled || loadedGame == null) {
                    playback.frame = null
                    playback.videoMetrics = null
                    return@LaunchedEffect
                }
                // Keep the last image visible behind the ROM-style menu sheet.
                // A game menu pauses both the native clock and AudioTrack,
                // preventing UI interaction from building an audio backlog.
                if (!foreground || slots.restoringState || pausedForGameMenu) return@LaunchedEffect
                val currentGame = loadedGame ?: return@LaunchedEffect
                playback.run(currentGame, userSettings, retro, scope)
            }

            // OpenDocumentTree grants temporary read access to just the
            // chosen folder. All accepted ROMs are copied to app-private
            // storage; no broad storage permission or persisted URI required.
            val folderPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                if (uri != null && !loading && !videoEnabled) {
                    loading = true
                    importError = null
                    folderImportSummary = null
                    folderImportProgress = "Analyse du dossier…"
                    scope.launch {
                        try {
                            val (result, entries) = withContext(Dispatchers.IO) {
                                val imported = GbFolderImporter(contentResolver, romLibrary)
                                    .importTree(uri) { scanned ->
                                        if (scanned == 1 || scanned % 5 == 0) {
                                            scope.launch {
                                                folderImportProgress =
                                                    "Importation du dossier… $scanned ROM examinées"
                                            }
                                        }
                                    }
                                imported to romLibrary.list().map {
                                    GameEntry(it.id, it.filename, it.platform)
                                }
                            }
                            libraryGames = entries
                            folderImportSummary = GbFolderImportSummary(
                                added = result.added,
                                duplicate = result.duplicate,
                                invalid = result.invalid,
                                unreadable = result.unreadable,
                                otherFiles = result.otherFiles,
                                scannedFolders = result.folders,
                                limited = result.limited,
                            )
                        } catch (error: Exception) {
                            Log.e("RavenEmuFolder", "Folder ROM import failed", error)
                            importError = "Importation du dossier impossible : " +
                                (error.message ?: "erreur inconnue")
                            // Even a partial import already committed its
                            // valid ROMs. Refresh the library in that case.
                            libraryGames = withContext(Dispatchers.IO) {
                                romLibrary.list().map {
                                    GameEntry(it.id, it.filename, it.platform)
                                }
                            }
                        } finally {
                            folderImportProgress = null
                            loading = false
                        }
                    }
                }
            }

            // Delta skins: installed list, and the skin of the running game.
            val deltaSkins = remember { DeltaSkins(applicationContext) }
            var skinEntries by remember { mutableStateOf(emptyList<DeltaSkinEntry>()) }
            var skinConfiguration by remember { mutableStateOf<DeltaSkinControllerConfiguration?>(null) }
            fun refreshSkins() {
                scope.launch {
                    skinEntries = runCatching { withContext(Dispatchers.IO) { deltaSkins.entries() } }
                        .onFailure { Log.w("RavenEmuSkins", "Cannot list skins", it) }
                        .getOrDefault(emptyList())
                }
            }
            LaunchedEffect(Unit) { refreshSkins() }
            LaunchedEffect(loadedGame?.platform, skinEntries, userSettings.haptics) {
                val platform = loadedGame?.platform
                skinConfiguration = if (platform == null) null else runCatching {
                    withContext(Dispatchers.IO) { deltaSkins.configuration(platform, userSettings.haptics) }
                }.getOrNull()
            }
            val skinPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null) scope.launch {
                    val message = runCatching { withContext(Dispatchers.IO) { deltaSkins.import(uri) } }
                        .getOrElse { "Importation du skin impossible" }
                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                    refreshSkins()
                }
            }

            val activeSkin = skinConfiguration

            val documentPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri != null && !loading) {
                    loading = true
                    importError = null
                    scope.launch {
                        try {
                            val (info, stored, rom) = withContext(Dispatchers.IO) {
                                val (name, bytes) = romLibrary.readSelected(uri)
                                // Validate with native RavenGB before indexing the cartridge.
                                val info = gameSession.loadBytes(name, bytes)
                                val stored = romLibrary.add(name, bytes)
                                Triple(info, stored, bytes)
                            }
                            loadedGame = info
                            activeGameId = stored.id
                            try { retro.attachRom(rom, info.platform) }
                            catch (error: Exception) {
                                Log.w("RavenEmuRetro", "RA identify failed after import", error)
                            }
                            libraryGames = withContext(Dispatchers.IO) {
                                romLibrary.list().map {
                                    GameEntry(it.id, it.filename, it.platform)
                                }
                            }
                        } catch (exception: Exception) {
                            importError = exception.message ?: "Échec du chargement de la ROM."
                        } finally {
                            loading = false
                        }
                    }
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
            ) { uri -> saveTransfers.reviewImport(uri, libraryGames) }

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
                // Hardcore forbids restoring a save state (saving stays allowed).
                stateLoadAllowed = !retro.hardcoreActive,
                onRequestGameMenu = {
                    retro.requestPause().also { allowed ->
                        if (!allowed) Toast.makeText(
                            applicationContext,
                            "Mode Hardcore : pause indisponible pour le moment.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
                onRetroLogin = { username, password -> retroUi.login(username, password) },
                onRetroDisconnect = { retroUi.disconnect() },
                onRetroRetry = { retroUi.retry() },
                settings = userSettings,
                onSettingsChange = { updated ->
                    userSettings = updated.normalized()
                    settingsStore.write(userSettings)
                },
                appVersion = appVersion,
                onOpenLink = { url ->
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
                },
                onImportRom = {
                    if (!loading) documentPicker.launch(arrayOf("*/*"))
                },
                onImportFolder = {
                    if (!loading && !videoEnabled) folderPicker.launch(null)
                },
                folderImportProgress = folderImportProgress,
                folderImportSummary = folderImportSummary,
                onDismissFolderImportSummary = { folderImportSummary = null },
                loadedGame = loadedGame,
                libraryGames = libraryGames,
                saveTransferBusy = saveTransfers.saveTransferBusy,
                saveImportReview = saveTransfers.saveImportReview,
                onCancelSaveImport = { saveTransfers.cancelImport() },
                onConfirmSaveImport = {
                    saveTransfers.confirmImport(playerOpen = videoEnabled) { romKey ->
                        if (activeGameId == romKey) {
                            activeGameId = null
                            loadedGame = null
                        }
                    }
                },
                onExportGameBackup = { id ->
                    saveTransfers.exportGameBackup(id, libraryGames, playerOpen = videoEnabled)
                },
                onExportBatterySave = { id ->
                    saveTransfers.exportBatterySave(id, libraryGames, playerOpen = videoEnabled)
                },
                onImportGameSave = { id ->
                    saveTransfers.importGameSave(id, libraryGames, playerOpen = videoEnabled)
                },
                libraryCovers = covers.libraryCovers,
                coverBusy = covers.coverBusy || covers.autoCoverLoading,
                onChooseCover = { id -> covers.choose(id, libraryGames) },
                onRemoveCover = { id -> covers.remove(id, libraryGames) },
                onLaunchLibraryGame = { id, onReady ->
                    if (!loading && !videoEnabled) {
                        loading = true
                        importError = null
                        scope.launch {
                            try {
                                if (activeGameId != id || loadedGame == null) {
                                    val entry = libraryGames.firstOrNull { it.id == id }
                                        ?: error("Ce jeu n'est plus dans la bibliothèque.")
                                    val (result, rom) = withContext(Dispatchers.IO) {
                                        val rom = romLibrary.readRom(id)
                                        gameSession.loadBytes(entry.title, rom) to rom
                                    }
                                    loadedGame = result
                                    activeGameId = id
                                    try { retro.attachRom(rom, result.platform) }
                                    catch (error: Exception) {
                                        Log.w("RavenEmuRetro", "RA identify failed on launch", error)
                                    }
                                }
                                onReady()
                            } catch (error: Exception) {
                                Log.e("RavenEmuLibrary", "Cannot load stored GB ROM", error)
                                importError = error.message ?: "Impossible de lancer ce jeu."
                            } finally {
                                loading = false
                            }
                        }
                    }
                },
                importBusy = loading,
                importError = importError,
                onStartVideo = { videoEnabled = true },
                onStopVideo = {
                    playback.activeAudio?.stopNow()
                    gameSession.releaseAllButtons()
                    videoEnabled = false
                    pausedForGameMenu = false
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
                },
                skinControls = if (activeSkin == null) null else @Composable { image: ImageBitmap?, openMenu: () -> Unit ->
                    val configuration: DeltaSkinControllerConfiguration = activeSkin
                    DeltaSkinPlayer(
                        configuration = configuration,
                        frame = image,
                        onButtons = { changes ->
                            if (videoEnabled && foreground && !pausedForGameMenu) {
                                for (button in changes.released) gameSession.setButton(button, false)
                                for (button in changes.pressed) gameSession.setButton(button, true)
                            }
                        },
                        onMenu = openMenu,
                        onError = { code ->
                            Log.w("RavenEmuSkins", "Skin fallback: $code")
                            val platform = loadedGame?.platform
                            scope.launch(Dispatchers.IO) {
                                if (platform != null) deltaSkins.fallback(platform, configuration.skinSha256)
                            }
                            skinConfiguration = null
                            gameSession.releaseAllButtons()
                            Toast.makeText(applicationContext,
                                "Skin invalide : commandes classiques restaurées",
                                Toast.LENGTH_LONG).show()
                            refreshSkins()
                        },
                    )
                },
                deltaSkins = skinEntries,
                onImportSkin = { skinPicker.launch(arrayOf("*/*")) },
                onToggleSkin = { id ->
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { deltaSkins.toggle(id) } }
                        refreshSkins()
                    }
                },
                onDeleteSkin = { id ->
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { deltaSkins.delete(id) } }
                        Toast.makeText(applicationContext, "Skin supprimé", Toast.LENGTH_SHORT).show()
                        refreshSkins()
                    }
                },
                touchControlsVisible = !gamepadInUse,
                controllerMode = controllerMode,
                onControllerModeChange = { controllerMode = it },
                gameMenuRequests = gameMenuRequests,
                // Touching the screen brings the touch controls back.
                onScreenTouch = { gamepadInUse = false },
                onButtonChange = { button: GbButton, pressed: Boolean ->
                    if (videoEnabled && foreground && !pausedForGameMenu) {
                        gameSession.setButton(button, pressed)
                    }
                },
                videoFrame = playback.frame,
                videoError = playback.videoError,
                videoMetrics = playback.videoMetrics,
                onExportAudioDiagnostics = {
                    playback.lastAudioReport = playback.activeAudio?.exportReport() ?: playback.lastAudioReport
                    exportAudio.launch("ravenemu-audio-diagnostic.txt")
                },
                onGameMenuVisibilityChange = { open ->
                    if (open) {
                        slots.refreshSlots()
                        // Preserve a useful report before closing the track.
                        playback.activeAudio?.let {
                            playback.lastAudioReport = it.exportReport()
                            it.stopNow()
                        }
                        gameSession.releaseAllButtons()
                    }
                    pausedForGameMenu = open
                },
                stateSlots = slots.stateSlots,
                stateSlotsLoading = slots.stateSlotsLoading,
                stateOperationBusy = slots.stateOperationBusy,
                onSaveState = { slot ->
                    slots.save(slot, playing = loadedGame != null && videoEnabled)
                },
                onLoadState = { slot ->
                    slots.load(slot, playing = loadedGame != null && videoEnabled, retro)
                },
                onDeleteState = { slot ->
                    slots.delete(slot, playing = loadedGame != null && videoEnabled)
                },
            )
        }
    }

    override fun onDestroy() {
        playback.activeAudio?.stopNow()
        retroController?.close()
        gameSession.close()
        super.onDestroy()
    }
}
