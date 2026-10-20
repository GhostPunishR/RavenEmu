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
    // Two games side by side, joined by a virtual link cable.
    private lateinit var linkSession: AndroidLinkSession
    private lateinit var linkPlayback: LinkPlayback
    private var linkInputEnabled = false
    private var retroController: AchievementsController? = null
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
        try {
            gameSession.saveBatteryIfDirty()
            linkSession.saveBatteriesIfDirty()
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
            var libraryGames by remember { mutableStateOf(emptyList<GameEntry>()) }
            var activeGameId by remember { mutableStateOf<String?>(null) }
            // Each refresh from the library menu searches the missing covers again.
            var libraryRefreshes by remember { mutableStateOf(0) }
            // Games and covers as stored; throws when the index cannot be read.
            suspend fun reloadLibrary() {
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
            }
            LaunchedEffect(romLibrary) {
                try {
                    reloadLibrary()
                } catch (error: Exception) {
                    Log.e("RavenEmuLibrary", "Cannot open library index", error)
                }
            }
            // Only run a cover search when the user enabled it, and then only
            // when the library changes, an excluded ROM is explicitly
            // re-enabled or the library is refreshed. Recomposition/video
            // frames never initiate network calls.
            LaunchedEffect(libraryGames, covers.ignoredAutoCovers, userSettings.onlineCovers, libraryRefreshes) {
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
                    applicationContext, contentResolver, gameSession, linkSession, libraryOperations,
                    romLibrary, saveTransfer, scope,
                )
            }
            var videoEnabled by remember { mutableStateOf(false) }
            // Linked pair: both games loaded, then playing on the link screen.
            var linkedGames by remember { mutableStateOf(emptyList<LoadedGameInfo>()) }
            var linkEnabled by remember { mutableStateOf(false) }
            var linkFocus by remember { mutableStateOf(0) }
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
                val accepts = (videoEnabled && !pausedForGameMenu) || linkEnabled
                if (!accepts && gamepadInputEnabled) gamepad.reset()
                gamepadInputEnabled = accepts
                linkInputEnabled = linkEnabled
            }
            val retroUi = remember {
                RetroAchievementsUi(applicationContext, gameSession, scope).also {
                    retroController = it.retro
                    // A refused Hardcore stays off: the next launch must not ask again.
                    it.onHardcoreRefused = {
                        userSettings = userSettings.copy(retroHardcore = false).normalized()
                        settingsStore.write(userSettings)
                    }
                }
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
            SideEffect { retroUi.configure(userSettings) }
            LaunchedEffect(videoEnabled, foreground) {
                if (videoEnabled && foreground) retroUi.showUnlocks() else retroUi.hidePopups()
            }
            LaunchedEffect(videoEnabled, foreground) {
                if (videoEnabled && foreground) retroUi.showProgress()
                else retroUi.hideProgress()
            }
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

            LaunchedEffect(linkEnabled, linkedGames, foreground) {
                if (!linkEnabled || linkedGames.size != 2) {
                    linkPlayback.clear()
                    return@LaunchedEffect
                }
                // In the background, the last pictures stay and the clock stops.
                if (!foreground) return@LaunchedEffect
                linkPlayback.run(linkedGames, userSettings, scope)
            }

            // OpenDocumentTree grants temporary read access to just the
            // chosen folder. All accepted ROMs are copied to app-private
            // storage; no broad storage permission or persisted URI required.
            val folderPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree(),
            ) { uri ->
                if (uri != null && !loading && !videoEnabled && !linkEnabled) {
                    val operation = libraryOperations.tryAcquire()
                        ?: return@rememberLauncherForActivityResult
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
                            operation.close()
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
                if (uri != null && !loading && !videoEnabled && !linkEnabled) {
                    val operation = libraryOperations.tryAcquire()
                        ?: return@rememberLauncherForActivityResult
                    loading = true
                    importError = null
                    scope.launch {
                        try {
                            val (info, stored, rom) = withContext(Dispatchers.IO) {
                                // A pair just left behind writes its saves first.
                                linkSession.unload()
                                val (name, bytes) = romLibrary.readSelected(uri)
                                // Validate with native RavenGB before indexing the cartridge.
                                val info = gameSession.loadBytes(name, bytes)
                                val stored = romLibrary.add(name, bytes)
                                Triple(info, stored, bytes)
                            }
                            loadedGame = info
                            activeGameId = stored.id
                            try { retro.selectRom(rom, info.platform) }
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
                            operation.close()
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
                stateLoadAllowed = !retroUi.retroSnapshot.hardcore,
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
                onRetroLeaderboard = { id, around -> retroUi.fetchLeaderboard(id, around) },
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
                    if (!libraryOperations.isBusy()) documentPicker.launch(arrayOf("*/*"))
                },
                onImportFolder = {
                    if (!libraryOperations.isBusy() && !videoEnabled && !linkEnabled) folderPicker.launch(null)
                },
                onRefreshLibrary = refresh@{
                    if (loading || videoEnabled || linkEnabled) return@refresh
                    val operation = libraryOperations.tryAcquire() ?: return@refresh
                    loading = true
                    importError = null
                    folderImportProgress = "Actualisation de la bibliothèque…"
                    scope.launch {
                        try {
                            reloadLibrary()
                            // Games the cover index did not know are searched again.
                            covers.forgetUnmatched()
                            libraryRefreshes++
                            val count = libraryGames.size
                            Toast.makeText(applicationContext,
                                "Bibliothèque actualisée : $count ${if (count > 1) "jeux" else "jeu"}",
                                Toast.LENGTH_SHORT).show()
                        } catch (error: Exception) {
                            Log.e("RavenEmuLibrary", "Library refresh failed", error)
                            importError = "Actualisation impossible : " +
                                (error.message ?: "erreur inconnue")
                        } finally {
                            folderImportProgress = null
                            loading = false
                            operation.close()
                        }
                    }
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
                    saveTransfers.confirmImport(playerOpen = videoEnabled || linkEnabled) { romKey ->
                        if (activeGameId == romKey) {
                            activeGameId = null
                            loadedGame = null
                        }
                    }
                },
                onExportAllSaves = {
                    saveTransfers.exportAllSaves(libraryGames, playerOpen = videoEnabled || linkEnabled)
                },
                onExportBatterySave = { id, format ->
                    saveTransfers.exportBatterySave(id, format, libraryGames, playerOpen = videoEnabled || linkEnabled)
                },
                onImportGameSave = { id ->
                    saveTransfers.importGameSave(id, libraryGames, playerOpen = videoEnabled || linkEnabled)
                },
                libraryCovers = covers.libraryCovers,
                coverBusy = covers.coverBusy || covers.autoCoverLoading,
                onChooseCover = { id -> covers.choose(id, libraryGames) },
                onRemoveCover = { id -> covers.remove(id, libraryGames) },
                onLaunchLibraryGame = launchGame@{ id, onReady ->
                    if (!loading && !videoEnabled && !linkEnabled) {
                        val operation = libraryOperations.tryAcquire() ?: return@launchGame
                        loading = true
                        importError = null
                        scope.launch {
                            try {
                                if (activeGameId != id || loadedGame == null) {
                                    val entry = libraryGames.firstOrNull { it.id == id }
                                        ?: error("Ce jeu n'est plus dans la bibliothèque.")
                                    val (result, rom) = withContext(Dispatchers.IO) {
                                        // A pair just left behind writes its saves first.
                                        linkSession.unload()
                                        val rom = romLibrary.readRom(id)
                                        gameSession.loadBytes(entry.title, rom) to rom
                                    }
                                    loadedGame = result
                                    activeGameId = id
                                    try { retro.selectRom(rom, result.platform) }
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
                                operation.close()
                            }
                        }
                    }
                },
                importBusy = loading,
                importError = importError,
                onLaunchLinkedGames = launchLinked@{ firstId, secondId, onReady ->
                    if (!loading && !videoEnabled && !linkEnabled) {
                        val operation = libraryOperations.tryAcquire() ?: return@launchLinked
                        loading = true
                        importError = null
                        scope.launch {
                            try {
                                val ids = listOf(firstId, secondId)
                                val entries = ids.map { id ->
                                    libraryGames.firstOrNull { it.id == id }
                                        ?: error("Ce jeu n'est plus dans la bibliothèque.")
                                }
                                val infos = withContext(Dispatchers.IO) {
                                    val roms = entries.map { it.title to romLibrary.readRom(it.id) }
                                    // A game held by the single player writes its save and
                                    // leaves memory: only one copy of a save may run at once.
                                    for (id in ids) gameSession.releaseForSaveImport(id)
                                    linkSession.load(roms)
                                }
                                if (activeGameId in ids) {
                                    activeGameId = null
                                    loadedGame = null
                                }
                                linkedGames = infos
                                linkFocus = 0
                                onReady()
                            } catch (error: Exception) {
                                Log.e("RavenEmuLink", "Cannot load linked games", error)
                                importError = error.message ?: "Impossible de relier ces jeux."
                            } finally {
                                loading = false
                                operation.close()
                            }
                        }
                    }
                },
                onStartLinked = { linkEnabled = true },
                onStopLinked = {
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
                },
                linkedGames = linkedGames,
                linkFrames = listOf(linkPlayback.firstFrame, linkPlayback.secondFrame),
                linkFocus = linkFocus,
                onLinkFocus = { index ->
                    linkSession.setFocus(index)
                    linkFocus = index
                },
                linkError = linkPlayback.error,
                linkMetrics = linkPlayback.metrics,
                onStartVideo = {
                    videoEnabled = true
                    // The game's RetroAchievements session lives while the player is open.
                    scope.launch {
                        try { retro.resumeGame() }
                        catch (error: Exception) {
                            Log.w("RavenEmuRetro", "RA game session could not start", error)
                        }
                    }
                },
                onStopVideo = {
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
                    if (linkEnabled && foreground) {
                        linkSession.setButton(button, pressed)
                    } else if (videoEnabled && foreground && !pausedForGameMenu) {
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
        linkPlayback.activeAudio?.stopNow()
        retroController?.close()
        gameSession.close()
        linkSession.close()
        super.onDestroy()
    }
}
