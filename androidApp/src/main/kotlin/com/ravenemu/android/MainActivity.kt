package com.ravenemu.android

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.util.Log
import android.widget.Toast
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.locks.LockSupport
import java.text.DateFormat
import java.util.Date
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.ravenemu.android.deltaskin.DeltaSkinControllerConfiguration
import com.ravenemu.android.deltaskin.DeltaSkinPlayer
import com.ravenemu.android.deltaskin.DeltaSkins
import com.ravenemu.shared.DeltaSkinEntry
import com.ravenemu.shared.GameEntry
import com.ravenemu.shared.GbFolderImportSummary
import com.ravenemu.shared.GbDisplayFilter
import com.ravenemu.shared.GbButton
import com.ravenemu.shared.GbStateSlot
import com.ravenemu.shared.GbSaveImportReview
import com.ravenemu.shared.GbVideoMetrics
import com.ravenemu.shared.GbVideoMetricsAccumulator
import com.ravenemu.shared.LoadedGameInfo
import com.ravenemu.shared.RavenEmuApp
import com.ravenemu.shared.RetroIndicatorState
import com.ravenemu.shared.RetroNoticeText
import com.ravenemu.shared.RetroProgress
import com.ravenemu.shared.Settings
import com.ravenemu.shared.RetroSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    private lateinit var gameSession: AndroidGbSession
    private var retroController: AchievementsController? = null
    private var foreground by mutableStateOf(false)
    // Written by the emulation thread, read by the UI thread.
    @Volatile private var activeAudio: AndroidGbAudioOutput? = null
    @Volatile private var lastAudioReport: String = "Aucune session audio enregistrée.\n"
    // Physical controller. Its events are taken only while the player accepts
    // input; elsewhere the D-pad keeps navigating the app as usual.
    private val gamepad = GamepadMapper()
    private val cartridgeRumble by lazy { CartridgeRumble(applicationContext) }
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
        activeAudio?.stopNow()
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

        setContent {
            val settingsStore = remember { AndroidSettingsStore(applicationContext) }
            var userSettings by remember { mutableStateOf(settingsStore.read()) }
            val romLibrary = remember { GbRomLibrary(File(filesDir, "gb-library"), contentResolver) }
            val saveTransfer = remember { GbSaveTransfer(filesDir) }
            val coverStore = remember { GbCoverStore(File(filesDir, "gb-covers"), contentResolver) }
            val autoCoverDownloader = remember { GbAutoCoverDownloader() }
            val coverPreferences = remember {
                applicationContext.getSharedPreferences("ravenemu_cover_rules", MODE_PRIVATE)
            }
            var ignoredAutoCovers by remember {
                mutableStateOf(
                    coverPreferences.getStringSet("disabled_auto_cover_ids", emptySet())
                        ?.toSet() ?: emptySet()
                )
            }
            // Jeux que l'index de libretro ne connaît pas. Les retenir évite de
            // retélécharger l'index entier à chaque lancement pour les chercher
            // à nouveau ; désactiver puis réactiver la recherche les réarme.
            var unmatchedAutoCovers by remember {
                mutableStateOf(
                    coverPreferences.getStringSet("unmatched_auto_cover_ids", emptySet())
                        ?.toSet() ?: emptySet()
                )
            }
            var autoCoverLoading by remember { mutableStateOf(false) }
            var libraryCovers by remember { mutableStateOf(emptyMap<String, ImageBitmap>()) }
            var coverBusy by remember { mutableStateOf(false) }
            var pendingCoverId by remember { mutableStateOf<String?>(null) }
            var libraryGames by remember { mutableStateOf(emptyList<GameEntry>()) }
            var activeGameId by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(romLibrary) {
                try {
                    val (games, covers) = withContext(Dispatchers.IO) {
                        val entries = romLibrary.list()
                        val pictures = entries.mapNotNull { entry ->
                            try {
                                coverStore.read(entry.id)?.asImageBitmap()?.let { entry.id to it }
                            } catch (error: Exception) {
                                Log.w("RavenEmuLibrary", "Cannot read cover for ${entry.id.take(12)}", error)
                                null
                            }
                        }.toMap()
                        entries.map { GameEntry(it.id, it.filename, it.platform) } to pictures
                    }
                    libraryGames = games
                    libraryCovers = covers
                } catch (error: Exception) {
                    Log.e("RavenEmuLibrary", "Cannot open library index", error)
                }
            }
            // Only run a cover search when the user enabled it, and then only
            // when the library changes or an excluded ROM is explicitly
            // re-enabled. Recomposition/video frames never initiate network
            // calls. Disabled by default: the requests tell a third-party
            // server which games are in the library.
            LaunchedEffect(libraryGames, ignoredAutoCovers, userSettings.onlineCovers) {
                if (!userSettings.onlineCovers) {
                    if (unmatchedAutoCovers.isNotEmpty()) {
                        unmatchedAutoCovers = emptySet()
                        coverPreferences.edit().remove("unmatched_auto_cover_ids").apply()
                    }
                    return@LaunchedEffect
                }
                if (libraryGames.isNotEmpty()) {
                    autoCoverLoading = true
                    try {
                        // The downloader limits each pass to 30 images. Scan
                        // every batch of a newly imported folder, not just the
                        // first 30 games, without blocking the Compose UI.
                        val entries = withContext(Dispatchers.IO) { romLibrary.list() }
                        for (batch in entries.chunked(30)) {
                            val pass = withContext(Dispatchers.IO) {
                                autoCoverDownloader.fillMissing(
                                    batch, coverStore, ignoredAutoCovers + unmatchedAutoCovers,
                                )
                            }
                            // Manual artwork wins over a concurrent download.
                            libraryCovers = pass.found.mapValues { it.value.asImageBitmap() } + libraryCovers
                            if (pass.unmatched.isNotEmpty()) {
                                unmatchedAutoCovers = unmatchedAutoCovers + pass.unmatched
                                coverPreferences.edit()
                                    .putStringSet("unmatched_auto_cover_ids", unmatchedAutoCovers)
                                    .apply()
                            }
                        }
                    } catch (error: Exception) {
                        Log.w("RavenEmuCover", "Automatic cover lookup unavailable", error)
                    } finally {
                        autoCoverLoading = false
                    }
                }
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
            var saveTransferBusy by remember { mutableStateOf(false) }
            var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
            var pendingImportGame by remember { mutableStateOf<String?>(null) }
            var pendingSavePayload by remember {
                mutableStateOf<GbSaveTransfer.ImportPayload?>(null)
            }
            var saveImportReview by remember { mutableStateOf<GbSaveImportReview?>(null) }
            var videoEnabled by remember { mutableStateOf(false) }
            var frame by remember { mutableStateOf<ImageBitmap?>(null) }
            var videoError by remember { mutableStateOf<String?>(null) }
            var videoMetrics by remember { mutableStateOf<GbVideoMetrics?>(null) }
            var audioStatus by remember { mutableStateOf<String?>(null) }
            var stateOperationBusy by remember { mutableStateOf(false) }
            var stateSlotsLoading by remember { mutableStateOf(false) }
            var stateSlots by remember { mutableStateOf(emptyList<GbStateSlot>()) }
            var restoringState by remember { mutableStateOf(false) }
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
            var stateRevision by remember { mutableStateOf(0) }
            val scope = rememberCoroutineScope()
            var retroSnapshot by remember { mutableStateOf(RetroSnapshot()) }
            val retroBadgeStore = remember { RetroBadgeStore(applicationContext) }
            var retroImages by remember {
                mutableStateOf(emptyMap<String, ImageBitmap>())
            }
            LaunchedEffect(retroSnapshot.avatarUrl,
                retroSnapshot.entries.map { it.badgeUrl }.distinct()) {
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
            var retroBusy by remember { mutableStateOf(false) }
            var retroNotice by remember { mutableStateOf<String?>(null) }
            var retroNoticeBadge by remember { mutableStateOf<ImageBitmap?>(null) }
            var retroNoticeVisible by remember { mutableStateOf(false) }
            // Unlocks wait their turn: several often fire on the same frame,
            // and each used to replace the previous popup at once.
            val retroUnlocks = remember { mutableStateListOf<RetroPopup>() }
            // Challenges, measured progress and leaderboard trackers stay on
            // screen until the SDK hides them (RA compliance requirement).
            val retroIndicatorState = remember { RetroIndicatorState() }
            var retroIndicators by remember { mutableStateOf(emptyList<String>()) }
            var retroProgress by remember { mutableStateOf<RetroProgress?>(null) }
            val retro = remember {
                AchievementsController(
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
                ).also { retroController = it }
            }
            // Effective only once RetroAchievements approved the app.
            LaunchedEffect(retro, userSettings.retroHardcore) {
                runCatching { retro.setHardcore(userSettings.retroHardcore) }
                    .onFailure { Log.w("RavenEmuRetro", "Hardcore switch failed", it) }
            }
            LaunchedEffect(retro) {
                try {
                    retro.restore()
                } catch (error: Exception) {
                    Log.w("RavenEmuRetro", "Token restore failed", error)
                    retroSnapshot = retroSnapshot.copy(
                        error = "Connexion RetroAchievements indisponible.",
                    )
                }
            }
            // The progress popup shows the achievement's badge; the set's
            // badges are usually loaded already.
            LaunchedEffect(retroProgress?.badgeUrl) {
                val url = retroProgress?.badgeUrl.orEmpty()
                if (url.isBlank() || url in retroImages) return@LaunchedEffect
                val badge = withContext(Dispatchers.IO) {
                    runCatching { retroBadgeStore.load(url) }.getOrNull()?.asImageBitmap()
                }
                if (badge != null) retroImages = retroImages + (url to badge)
            }
            // One popup at a time, each for the same duration. The badge is
            // fetched before the popup opens, so it never changes size while
            // shown; after 2 s without it, the star stands in.
            LaunchedEffect(Unit) {
                while (isActive) {
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
            LaunchedEffect(retro, videoEnabled, pausedForGameMenu, foreground) {
                while (isActive && (!videoEnabled || pausedForGameMenu || !foreground)) {
                    try {
                        retro.idle()
                    } catch (error: Exception) {
                        Log.w("RavenEmuRetro", "Background rcheevos tick failed", error)
                    }
                    delay(400)
                }
            }
            val refreshSlots: () -> Unit = {
                stateSlotsLoading = true
                stateSlots = emptyList()
                scope.launch {
                    try {
                        stateSlots = withContext(Dispatchers.IO) {
                            val dateFormat = DateFormat.getDateTimeInstance(
                                DateFormat.MEDIUM, DateFormat.SHORT,
                            )
                            gameSession.listStateSlots().map { stored ->
                                val thumbnail = stored.preview?.let { bytes ->
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                        ?.asImageBitmap()
                                }
                                GbStateSlot(
                                    index = stored.slot,
                                    savedAt = if (!stored.occupied) null else
                                        stored.savedAtMillis?.let {
                                            dateFormat.format(Date(it))
                                        } ?: "Date inconnue",
                                    thumbnail = thumbnail,
                                )
                            }
                        }
                    } catch (error: Exception) {
                        Log.e("RavenEmuSaves", "Cannot list save slots", error)
                        Toast.makeText(
                            applicationContext,
                            "Impossible de lire les emplacements",
                            Toast.LENGTH_LONG,
                        ).show()
                    } finally {
                        stateSlotsLoading = false
                    }
                }
            }
            val exportAudio = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("text/plain"),
            ) { uri ->
                if (uri != null) {
                    try {
                        contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(lastAudioReport.toByteArray(Charsets.UTF_8))
                        } ?: Log.e("RavenEmuAudio", "Unable to open diagnostic destination")
                    } catch (error: Exception) {
                        Log.e("RavenEmuAudio", "Unable to save audio report", error)
                    }
                }
            }

            LaunchedEffect(
                videoEnabled, loadedGame, foreground,
                restoringState, stateRevision, pausedForGameMenu,
            ) {
                if (!videoEnabled || loadedGame == null) {
                    frame = null
                    videoMetrics = null
                    return@LaunchedEffect
                }
                // Keep the last image visible behind the ROM-style menu sheet.
                // A game menu pauses both the native clock and AudioTrack,
                // preventing UI interaction from building an audio backlog.
                if (!foreground || restoringState || pausedForGameMenu) return@LaunchedEffect
                videoMetrics = null

                videoError = null
                audioStatus = null
                val currentGame = loadedGame ?: return@LaunchedEffect
                val playbackSettings = userSettings
                val targetFps = (gameSession.refreshRateHz() ?: 59.7).coerceIn(1.0, 120.0)
                val periodNanos = (1_000_000_000.0 / targetFps).roundToLong()
                val stats = GbVideoMetricsAccumulator(targetFps)
                val pixels = IntArray(currentGame.width * currentGame.height)
                // More than one GB frame of interleaved stereo, reused per frame.
                val samples = ShortArray(4096)
                val images = GbFrameBitmaps(currentGame.width, currentGame.height)
                var output: AndroidGbAudioOutput? = null

                try {
                    try {
                        val rate = gameSession.audioRateHz()
                            ?: throw IllegalStateException("Aucune piste audio GB active.")
                        output = AndroidGbAudioOutput(
                            applicationContext, rate, targetFps,
                            playbackSettings.audioEnabled, playbackSettings.audioVolume,
                        )
                        activeAudio = output
                        lastAudioReport = "Session audio démarrée; diagnostic en cours.\n"
                        audioStatus = "Audio GB optimisé (préversion)"
                    } catch (error: Exception) {
                        audioStatus = "Audio indisponible : ${error.message ?: "Erreur AudioTrack"}"
                    }

                    // The whole loop runs on the dedicated emulation thread
                    // (see GbEmulationThread): no per-frame round trip to the
                    // UI thread, nanosecond pacing, and late frames are caught
                    // up rather than dropped, so the audio never starves.
                    withContext(gbEmulationDispatcher) {
                        val rumble = if (playbackSettings.cartridgeRumble) cartridgeRumble else null
                        val pacer = GbFramePacer(periodNanos)
                        pacer.reset(System.nanoTime())
                        var lastBatteryCheckNanos = System.nanoTime()
                        while (isActive) {
                            try {
                                // A late frame is still emulated and heard;
                                // only its image is skipped (see GbFramePacer).
                                val renderVideo = pacer.shouldRender(System.nanoTime())
                                val nativeStart = System.nanoTime()
                                val sampleCount =
                                    gameSession.nextFrameWithAudioInto(pixels, samples) ?: break
                                val nativeElapsed = System.nanoTime() - nativeStart
                                // One non-blocking audio submission per frame.
                                val sink = output
                                if (sink != null) {
                                    try {
                                        sink.submit(samples, sampleCount)
                                    } catch (error: Exception) {
                                        sink.stopNow()
                                        audioStatus = "Audio interrompu : ${error.message ?: "Sortie audio interrompue"}"
                                        if (activeAudio === sink) activeAudio = null
                                        lastAudioReport = sink.exportReport()
                                        sink.close()
                                        output = null
                                    }
                                }
                                if (rumble != null) {
                                    if (gameSession.rumbleActive) rumble.pulse() else rumble.release()
                                }
                                var conversionNanos = 0L
                                if (renderVideo) {
                                    val conversionStart = System.nanoTime()
                                    GbDisplayFilter.apply(
                                        pixels, playbackSettings, currentGame.platform,
                                    )
                                    frame = images.publish(pixels)
                                    conversionNanos = System.nanoTime() - conversionStart
                                }

                                // The official SDK evaluates each emulated frame
                                // using observational reads, on its dedicated
                                // thread. Network requests never block this call.
                                try {
                                    retro.frame()
                                } catch (error: Exception) {
                                    Log.w("RavenEmuRetro", "rcheevos frame unavailable", error)
                                }
                                val now = System.nanoTime()
                                if (now - lastBatteryCheckNanos >= 5_000_000_000L) {
                                    lastBatteryCheckNanos = now
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            gameSession.saveBatteryIfDirty()
                                        } catch (error: Exception) {
                                            Log.e("RavenEmuSaves", "Battery autosave failed", error)
                                        }
                                    }
                                }
                                val sample = stats.record(now, nativeElapsed, conversionNanos)
                                if (sample != null) {
                                    videoMetrics = sample
                                    output?.let {
                                        it.recordVideo(sample.actualFps, sample.coreMs, sample.conversionMs)
                                        lastAudioReport = it.exportReport()
                                        Log.d("RavenEmuAudio", it.diagnostics())
                                    }
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                videoError = error.message ?: "Erreur de rendu Game Boy."
                                frame = null
                                break
                            }

                            val wait = pacer.nextWaitNanos(System.nanoTime())
                            if (wait > 0) LockSupport.parkNanos(wait)
                        }
                    }
                } finally {
                    cartridgeRumble.stop()
                    if (activeAudio === output) activeAudio = null
                    output?.let { lastAudioReport = it.exportReport() }
                    output?.close()
                }
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

            val coverPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                val id = pendingCoverId
                pendingCoverId = null
                if (uri != null && id != null && !coverBusy) {
                    coverBusy = true
                    scope.launch {
                        try {
                            val cover = withContext(Dispatchers.IO) {
                                check(romLibrary.list().any { it.id == id }) {
                                    "Ce jeu n'est plus présent dans la bibliothèque."
                                }
                                coverStore.save(id, uri).asImageBitmap()
                            }
                            libraryCovers = libraryCovers + (id to cover)
                            if (id in ignoredAutoCovers) {
                                ignoredAutoCovers = ignoredAutoCovers - id
                                coverPreferences.edit()
                                    .putStringSet("disabled_auto_cover_ids", ignoredAutoCovers)
                                    .apply()
                            }
                            Toast.makeText(
                                applicationContext, "Jaquette enregistrée",
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (error: Exception) {
                            Log.e("RavenEmuLibrary", "Unable to save custom cover", error)
                            Toast.makeText(
                                applicationContext,
                                "Jaquette non enregistrée : ${error.message ?: "image invalide"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        } finally {
                            coverBusy = false
                        }
                    }
                }
            }

            val exportBackup = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                val request = pendingExport
                pendingExport = null
                if (uri != null && request != null && !saveTransferBusy) {
                    saveTransferBusy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                // The active core may still own unsaved battery RAM
                                // after leaving the player; flush before exporting.
                                gameSession.saveBatteryIfDirty()
                                contentResolver.openOutputStream(uri)?.use {
                                    saveTransfer.exportBundle(request.first, it)
                                } ?: error("Destination d'export inaccessible.")
                            }
                            Toast.makeText(applicationContext,
                                "Sauvegardes exportées", Toast.LENGTH_SHORT).show()
                        } catch (error: Exception) {
                            Log.e("RavenEmuTransfer", "Unable to export full backup", error)
                            Toast.makeText(applicationContext,
                                "Export impossible : ${error.message ?: "erreur inconnue"}",
                                Toast.LENGTH_LONG).show()
                        } finally {
                            saveTransferBusy = false
                        }
                    }
                }
            }
            val exportBattery = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
            ) { uri ->
                val request = pendingExport
                pendingExport = null
                if (uri != null && request != null && !saveTransferBusy) {
                    saveTransferBusy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                gameSession.saveBatteryIfDirty()
                                contentResolver.openOutputStream(uri)?.use {
                                    saveTransfer.exportBattery(request.first, it)
                                } ?: error("Destination d'export inaccessible.")
                            }
                            Toast.makeText(applicationContext,
                                "Fichier .sav exporté", Toast.LENGTH_SHORT).show()
                        } catch (error: Exception) {
                            Log.e("RavenEmuTransfer", "Unable to export .sav", error)
                            Toast.makeText(applicationContext,
                                "Export .sav impossible : ${error.message ?: "erreur inconnue"}",
                                Toast.LENGTH_LONG).show()
                        } finally {
                            saveTransferBusy = false
                        }
                    }
                }
            }
            val importSavePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri ->
                val id = pendingImportGame
                pendingImportGame = null
                if (uri != null && id != null && !saveTransferBusy) {
                    saveTransferBusy = true
                    scope.launch {
                        try {
                            val (payload, conflicts) = withContext(Dispatchers.IO) {
                                check(romLibrary.list().any { it.id == id }) {
                                    "Ce jeu n'est plus dans la bibliothèque."
                                }
                                val filename = contentResolver.query(
                                    uri, arrayOf(OpenableColumns.DISPLAY_NAME),
                                    null, null, null,
                                )?.use { cursor ->
                                    if (cursor.moveToFirst() && !cursor.isNull(0))
                                        cursor.getString(0) else null
                                } ?: error("Nom de fichier inaccessible.")
                                val candidate = contentResolver.openInputStream(uri)?.use {
                                    saveTransfer.previewImport(it, id, filename)
                                } ?: error("Sauvegarde inaccessible.")
                                candidate to saveTransfer.conflicts(candidate)
                            }
                            val gameName = libraryGames.firstOrNull { it.id == id }?.title
                                ?: "Jeu Game Boy"
                            pendingSavePayload = payload
                            saveImportReview = GbSaveImportReview(
                                gameTitle = gameName,
                                hasBattery = payload.hasBattery,
                                slots = payload.stateSlots.map { it + 1 },
                                conflicts = conflicts,
                                rawSav = payload.rawBattery,
                            )
                        } catch (error: Exception) {
                            Log.e("RavenEmuTransfer", "Invalid save import", error)
                            Toast.makeText(applicationContext,
                                "Import refusé : ${error.message ?: "fichier invalide"}",
                                Toast.LENGTH_LONG).show()
                        } finally {
                            saveTransferBusy = false
                        }
                    }
                }
            }

            RavenEmuApp(
                retroSnapshot = retroSnapshot,
                retroImages = retroImages,
                retroBusy = retroBusy,
                retroNotice = retroNotice,
                retroNoticeBadge = retroNoticeBadge,
                retroNoticeVisible = retroNoticeVisible,
                retroIndicators = retroIndicators,
                retroProgress = retroProgress,
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
                onRetroLogin = { username, password ->
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
                },
                onRetroDisconnect = {
                    scope.launch {
                        try { retro.disconnect() }
                        catch (error: Exception) {
                            retroSnapshot = retroSnapshot.copy(
                                error = error.message ?: "Déconnexion impossible",
                            )
                        }
                        retroBusy = false
                    }
                },
                onRetroRetry = {
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
                },
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
                saveTransferBusy = saveTransferBusy,
                saveImportReview = saveImportReview,
                onCancelSaveImport = {
                    saveImportReview = null
                    pendingSavePayload = null
                },
                onConfirmSaveImport = {
                    val payload = pendingSavePayload
                    if (payload != null && !saveTransferBusy && !videoEnabled) {
                        // Drop the review before any disk mutation, and never
                        // apply a package if the target game was removed.
                        pendingSavePayload = null
                        saveImportReview = null
                        saveTransferBusy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    check(romLibrary.list().any { it.id == payload.romKey }) {
                                        "La ROM associée n'existe plus."
                                    }
                                    gameSession.releaseForSaveImport(payload.romKey)
                                    saveTransfer.apply(payload)
                                }
                                if (activeGameId == payload.romKey) {
                                    activeGameId = null
                                    loadedGame = null
                                }
                                Toast.makeText(applicationContext,
                                    "Sauvegardes importées avec succès",
                                    Toast.LENGTH_SHORT).show()
                            } catch (error: Exception) {
                                Log.e("RavenEmuTransfer", "Save import failed", error)
                                if (activeGameId == payload.romKey) {
                                    activeGameId = null
                                    loadedGame = null
                                }
                                Toast.makeText(applicationContext,
                                    "Import impossible : ${error.message ?: "erreur inconnue"}",
                                    Toast.LENGTH_LONG).show()
                            } finally {
                                saveTransferBusy = false
                            }
                        }
                    }
                },
                onExportGameBackup = { id ->
                    if (!saveTransferBusy && !videoEnabled && libraryGames.any { it.id == id }) {
                        saveTransferBusy = true
                        val gameName = libraryGames.first { it.id == id }.title
                            .substringBeforeLast('.')
                            .replace(Regex("[^A-Za-z0-9_-]+"), "-")
                            .take(48)
                            .ifBlank { "GameBoy" }
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    gameSession.saveBatteryIfDirty()
                                    check(saveTransfer.hasAnySave(id)) {
                                        "Aucune sauvegarde à exporter pour ce jeu."
                                    }
                                }
                                pendingExport = id to "zip"
                                exportBackup.launch("$gameName-ravenemu-saves.zip")
                            } catch (error: Exception) {
                                Toast.makeText(applicationContext,
                                    error.message ?: "Export impossible", Toast.LENGTH_LONG).show()
                            } finally {
                                saveTransferBusy = false
                            }
                        }
                    }
                },
                onExportBatterySave = { id ->
                    if (!saveTransferBusy && !videoEnabled && libraryGames.any { it.id == id }) {
                        saveTransferBusy = true
                        val gameName = libraryGames.first { it.id == id }.title
                            .substringBeforeLast('.')
                            .replace(Regex("[^A-Za-z0-9_-]+"), "-")
                            .take(48)
                            .ifBlank { "GameBoy" }
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    gameSession.saveBatteryIfDirty()
                                    check(saveTransfer.hasBattery(id)) {
                                        "Ce jeu n'a pas encore de sauvegarde .sav."
                                    }
                                }
                                pendingExport = id to "sav"
                                exportBattery.launch("$gameName.sav")
                            } catch (error: Exception) {
                                Toast.makeText(applicationContext,
                                    error.message ?: "Export .sav impossible",
                                    Toast.LENGTH_LONG).show()
                            } finally {
                                saveTransferBusy = false
                            }
                        }
                    }
                },
                onImportGameSave = { id ->
                    if (!saveTransferBusy && !videoEnabled && libraryGames.any { it.id == id }) {
                        pendingSavePayload = null
                        saveImportReview = null
                        pendingImportGame = id
                        importSavePicker.launch(arrayOf("application/zip",
                            "application/octet-stream", "*/*"))
                    }
                },
                libraryCovers = libraryCovers,
                coverBusy = coverBusy || autoCoverLoading,
                onChooseCover = { id ->
                    if (!coverBusy && libraryGames.any { it.id == id }) {
                        pendingCoverId = id
                        coverPicker.launch(arrayOf("image/*"))
                    }
                },
                onRemoveCover = { id ->
                    if (!coverBusy && libraryGames.any { it.id == id }) {
                        coverBusy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { coverStore.delete(id) }
                                libraryCovers = libraryCovers - id
                                ignoredAutoCovers = ignoredAutoCovers + id
                                coverPreferences.edit()
                                    .putStringSet("disabled_auto_cover_ids", ignoredAutoCovers)
                                    .apply()
                            } catch (error: Exception) {
                                Log.e("RavenEmuLibrary", "Unable to remove cover", error)
                                Toast.makeText(
                                    applicationContext, "Impossible de retirer la jaquette",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                coverBusy = false
                            }
                        }
                    }
                },
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
                    activeAudio?.stopNow()
                    gameSession.releaseAllButtons()
                    videoEnabled = false
                    pausedForGameMenu = false
                    frame = null
                    videoMetrics = null
                    audioStatus = null
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
                videoFrame = frame,
                videoError = videoError,
                videoMetrics = videoMetrics,
                onExportAudioDiagnostics = {
                    lastAudioReport = activeAudio?.exportReport() ?: lastAudioReport
                    exportAudio.launch("ravenemu-audio-diagnostic.txt")
                },
                onGameMenuVisibilityChange = { open ->
                    if (open) {
                        refreshSlots()
                        // Preserve a useful report before closing the track.
                        activeAudio?.let {
                            lastAudioReport = it.exportReport()
                            it.stopNow()
                        }
                        gameSession.releaseAllButtons()
                    }
                    pausedForGameMenu = open
                },
                stateSlots = stateSlots,
                stateSlotsLoading = stateSlotsLoading,
                stateOperationBusy = stateOperationBusy,
                onSaveState = { slot ->
                    if (!stateOperationBusy && loadedGame != null && videoEnabled) {
                        stateOperationBusy = true
                        // Private copy: the player reuses its images in rotation.
                        val screenshot = frame?.asAndroidBitmap()?.copy(Bitmap.Config.ARGB_8888, false)
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    val preview = screenshot?.let { image ->
                                        val miniature = Bitmap.createScaledBitmap(
                                            // Native size: 160x144 (GB/GBC), 240x160 (GBA).
                                            image, image.width, image.height, false,
                                        )
                                        try {
                                            ByteArrayOutputStream().use { output ->
                                                if (miniature.compress(
                                                        Bitmap.CompressFormat.PNG, 100, output,
                                                    )
                                                ) output.toByteArray() else null
                                            }
                                        } finally {
                                            if (miniature !== image) miniature.recycle()
                                        }
                                    }
                                    gameSession.saveStateSlot(slot, preview)
                                }
                                Toast.makeText(
                                    applicationContext,
                                    "État sauvegardé (emplacement ${slot + 1})",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                refreshSlots()
                            } catch (error: Exception) {
                                Log.e("RavenEmuSaves", "Save state failed", error)
                                Toast.makeText(
                                    applicationContext,
                                    "Échec du save state : ${error.message ?: "erreur inconnue"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                stateOperationBusy = false
                            }
                        }
                    }
                },
                onLoadState = { slot ->
                    if (retro.hardcoreActive) {
                        Toast.makeText(applicationContext,
                            "Mode Hardcore : le chargement d'un état est désactivé.",
                            Toast.LENGTH_LONG).show()
                    } else if (!stateOperationBusy && loadedGame != null && videoEnabled) {
                        stateOperationBusy = true
                        restoringState = true
                        activeAudio?.stopNow()
                        gameSession.releaseAllButtons()
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { gameSession.loadStateSlot(slot) }
                                try { retro.resetProgress() }
                                catch (error: Exception) {
                                    Log.w("RavenEmuRetro", "RA reset after state load failed", error)
                                }
                                Toast.makeText(
                                    applicationContext, "État chargé (emplacement ${slot + 1})",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } catch (error: Exception) {
                                Log.e("RavenEmuSaves", "Load state failed", error)
                                Toast.makeText(
                                    applicationContext,
                                    "Chargement impossible : ${error.message ?: "erreur inconnue"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                stateRevision++
                                restoringState = false
                                stateOperationBusy = false
                            }
                        }
                    }
                },
                onDeleteState = { slot ->
                    if (!stateOperationBusy && loadedGame != null && videoEnabled) {
                        stateOperationBusy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { gameSession.deleteStateSlot(slot) }
                                Toast.makeText(
                                    applicationContext,
                                    "Emplacement ${slot + 1} supprimé",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                refreshSlots()
                            } catch (error: Exception) {
                                Log.e("RavenEmuSaves", "Delete state failed", error)
                                Toast.makeText(
                                    applicationContext,
                                    "Suppression impossible : ${error.message ?: "erreur inconnue"}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            } finally {
                                stateOperationBusy = false
                            }
                        }
                    }
                },
            )
        }
    }

    override fun onDestroy() {
        activeAudio?.stopNow()
        retroController?.close()
        gameSession.close()
        super.onDestroy()
    }
}

/** Avatar plus badges: well above the largest RetroAchievements sets. */
private const val MAX_RETRO_IMAGES = 600

/** A burst beyond this is not worth minutes of popups; the list keeps them all. */
private const val MAX_QUEUED_UNLOCKS = 20

private data class RetroPopup(val text: String, val badgeUrl: String)
