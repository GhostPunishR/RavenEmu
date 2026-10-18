package com.ravenemu.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class RavenScreen { LIBRARY, SETTINGS, ABOUT, PLAYER, LINK_PLAYER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RavenEmuApp(
    onImportRom: (() -> Unit)? = null,
    onImportFolder: (() -> Unit)? = null,
    folderImportSummary: GbFolderImportSummary? = null,
    folderImportProgress: String? = null,
    onDismissFolderImportSummary: (() -> Unit)? = null,
    loadedGame: LoadedGameInfo? = null,
    libraryGames: List<GameEntry> = emptyList(),
    libraryCovers: Map<String, ImageBitmap> = emptyMap(),
    onChooseCover: ((String) -> Unit)? = null,
    onRemoveCover: ((String) -> Unit)? = null,
    coverBusy: Boolean = false,
    onLaunchLibraryGame: ((String, () -> Unit) -> Unit)? = null,
    onExportGameBackup: ((String) -> Unit)? = null,
    onExportBatterySave: ((String) -> Unit)? = null,
    onImportGameSave: ((String) -> Unit)? = null,
    saveTransferBusy: Boolean = false,
    saveImportReview: GbSaveImportReview? = null,
    onConfirmSaveImport: (() -> Unit)? = null,
    onCancelSaveImport: (() -> Unit)? = null,
    importBusy: Boolean = false,
    importError: String? = null,
    onStartVideo: (() -> Unit)? = null,
    onStopVideo: (() -> Unit)? = null,
    /** Loads two library games linked by cable, then calls back once both are ready. */
    onLaunchLinkedGames: ((String, String, () -> Unit) -> Unit)? = null,
    onStartLinked: (() -> Unit)? = null,
    onStopLinked: (() -> Unit)? = null,
    linkedGames: List<LoadedGameInfo> = emptyList(),
    linkFrames: List<ImageBitmap?> = emptyList(),
    linkFocus: Int = 0,
    onLinkFocus: ((Int) -> Unit)? = null,
    linkError: String? = null,
    linkMetrics: GbVideoMetrics? = null,
    videoFrame: ImageBitmap? = null,
    videoError: String? = null,
    videoMetrics: GbVideoMetrics? = null,
    onButtonChange: ((GbButton, Boolean) -> Unit)? = null,
    touchControlsVisible: Boolean = true,
    onScreenTouch: (() -> Unit)? = null,
    onExportAudioDiagnostics: (() -> Unit)? = null,
    onSaveState: ((Int) -> Unit)? = null,
    onLoadState: ((Int) -> Unit)? = null,
    onDeleteState: ((Int) -> Unit)? = null,
    stateSlots: List<GbStateSlot> = emptyList(),
    stateSlotsLoading: Boolean = false,
    stateOperationBusy: Boolean = false,
    onGameMenuVisibilityChange: ((Boolean) -> Unit)? = null,
    retroSnapshot: RetroSnapshot = RetroSnapshot(),
    retroImages: Map<String, ImageBitmap> = emptyMap(),
    retroBusy: Boolean = false,
    onRetroLogin: ((String, String) -> Unit)? = null,
    onRetroDisconnect: (() -> Unit)? = null,
    onRetroRetry: (() -> Unit)? = null,
    retroNotice: String? = null,
    retroNoticeBadge: ImageBitmap? = null,
    retroNoticeVisible: Boolean = retroNotice != null,
    retroIndicators: List<String> = emptyList(),
    retroProgress: RetroProgress? = null,
    hardcoreAvailable: Boolean = false,
    stateLoadAllowed: Boolean = true,
    onRequestGameMenu: (() -> Boolean)? = null,
    /** Android Delta skin for the running game, or null for the classic controls. */
    skinControls: (@Composable (frame: ImageBitmap?, openMenu: () -> Unit) -> Unit)? = null,
    /** Controller mode: landscape, full screen, no touch controls (host rotates). */
    controllerMode: Boolean = false,
    onControllerModeChange: ((Boolean) -> Unit)? = null,
    /** Bumped by the host to open the game menu (controller's Home key). */
    gameMenuRequests: Int = 0,
    deltaSkins: List<DeltaSkinEntry> = emptyList(),
    onImportSkin: (() -> Unit)? = null,
    onToggleSkin: ((String) -> Unit)? = null,
    onDeleteSkin: ((String) -> Unit)? = null,
    settings: Settings = Settings(),
    onSettingsChange: ((Settings) -> Unit)? = null,
    appVersion: String = "0.1.0 - Bêta",
    onOpenLink: ((String) -> Unit)? = null,
) {
    var screen by remember { mutableStateOf(RavenScreen.LIBRARY) }
    var search by remember { mutableStateOf("") }
    var listMode by remember { mutableStateOf(false) }
    var gameDetails by remember { mutableStateOf<GameEntry?>(null) }
    var linkPartnerFor by remember { mutableStateOf<GameEntry?>(null) }
    val games = if (libraryGames.isNotEmpty()) libraryGames
        else if (loadedGame == null) emptyList() else listOf(
            GameEntry("active", loadedGame.filename, loadedGame.platform),
        )
    val pages = LibraryNavigation.pages(games)
    val pagerState = rememberPagerState(pageCount = { pages.size })
    var pageRequest by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pageRequest) {
        val target = pageRequest ?: return@LaunchedEffect
        if (target in pages.indices) pagerState.animateScrollToPage(target)
        pageRequest = null
    }

    LaunchedEffect(pages.size) {
        if (pagerState.currentPage >= pages.size) pagerState.scrollToPage(0)
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = RavenAccent,
        background = RavenBackground,
        surface = RavenSurface,
        onBackground = RavenText,
        onSurface = RavenText,
        onPrimary = RavenBackground,
    )) {
        Column(
            Modifier.fillMaxSize().background(RavenBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            when (screen) {
                RavenScreen.LIBRARY -> {
                    LibraryToolbar(
                        title = pages.getOrElse(pagerState.currentPage) { pages.first() }.title,
                        importEnabled = onImportRom != null && !importBusy && !saveTransferBusy,
                        listMode = listMode,
                        onSettings = { screen = RavenScreen.SETTINGS },
                        onAdd = { onImportRom?.invoke() },
                        onToggleView = { listMode = !listMode },
                        onImportFolder = onImportFolder,
                        onAbout = { screen = RavenScreen.ABOUT },
                    )
                    SearchField(query = search, onChange = { search = it })
                    if (importBusy) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(folderImportProgress ?: "Chargement de la ROM...",
                                fontSize = 12.sp, color = RavenMuted)
                        }
                    }
                    if (importError != null) {
                        Text(
                            importError,
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            color = RavenDanger,
                            fontSize = 12.sp,
                        )
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) { index ->
                        val page = pages[index]
                        val entries = LibraryNavigation.gamesForPage(games, page, search)
                        LibraryPageContent(
                            entries = entries,
                            covers = libraryCovers,
                            emptyMessage = when {
                                search.isNotBlank() -> "Aucun jeu ne correspond à cette recherche."
                                games.isEmpty() && onImportRom != null ->
                                    "Aucun jeu détecté.\nAjoutez une ROM avec le bouton + de la barre."
                                games.isEmpty() -> "Aucun jeu détecté.\nImport iOS à venir."
                                else -> "Aucun jeu ${page.title} dans votre bibliothèque."
                            },
                            listMode = listMode,
                            onSelect = { gameDetails = it },
                        )
                    }
                    PageIndicator(pages, pagerState.currentPage) { pageRequest = it }
                    Text(
                        "RavenEmu By GhostPunishR",
                        modifier = Modifier.fillMaxWidth().height(28.dp),
                        color = RavenMuted,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
                RavenScreen.SETTINGS -> SettingsScreen(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    appVersion = appVersion,
                    onOpenLink = onOpenLink,
                    aboutOnly = false,
                    retroSnapshot = retroSnapshot,
                    retroImages = retroImages,
                    retroBusy = retroBusy,
                    onRetroLogin = onRetroLogin,
                    onRetroDisconnect = onRetroDisconnect,
                    onRetroRetry = onRetroRetry,
                    hardcoreAvailable = hardcoreAvailable,
                    deltaSkins = deltaSkins,
                    onImportSkin = onImportSkin,
                    onToggleSkin = onToggleSkin,
                    onDeleteSkin = onDeleteSkin,
                    onBack = { screen = RavenScreen.LIBRARY },
                )
                RavenScreen.ABOUT -> SettingsScreen(
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    appVersion = appVersion,
                    onOpenLink = onOpenLink,
                    aboutOnly = true,
                    onBack = { screen = RavenScreen.LIBRARY },
                )
                RavenScreen.PLAYER -> GbVideoScreen(
                    settings = settings,
                    title = loadedGame?.filename.orEmpty(),
                    platform = loadedGame?.platform ?: GamePlatform.GB,
                    frame = videoFrame,
                    error = videoError,
                    metrics = videoMetrics,
                    onButtonChange = onButtonChange,
                    touchControlsVisible = touchControlsVisible,
                    onScreenTouch = onScreenTouch,
                    onExportAudioDiagnostics = onExportAudioDiagnostics,
                    onSaveState = onSaveState,
                    onLoadState = onLoadState,
                    onDeleteState = onDeleteState,
                    stateSlots = stateSlots,
                    stateSlotsLoading = stateSlotsLoading,
                    stateOperationBusy = stateOperationBusy,
                    onGameMenuVisibilityChange = onGameMenuVisibilityChange,
                    retroSnapshot = retroSnapshot,
                    retroImages = retroImages,
                    retroBusy = retroBusy,
                    onRetroLogin = onRetroLogin,
                    onRetroDisconnect = onRetroDisconnect,
                    onRetroRetry = onRetroRetry,
                    onOpenLink = onOpenLink,
                    retroNotice = retroNotice,
                    retroNoticeBadge = retroNoticeBadge,
                    retroNoticeVisible = retroNoticeVisible,
                    retroIndicators = retroIndicators,
                    retroProgress = retroProgress,
                    stateLoadAllowed = stateLoadAllowed,
                    onRequestGameMenu = onRequestGameMenu,
                    skinControls = skinControls,
                    controllerMode = controllerMode,
                    onControllerModeChange = onControllerModeChange,
                    gameMenuRequests = gameMenuRequests,
                    onBack = {
                        onStopVideo?.invoke()
                        screen = RavenScreen.LIBRARY
                    },
                )
                RavenScreen.LINK_PLAYER -> LinkPlayerScreen(
                    settings = settings,
                    games = linkedGames,
                    frames = linkFrames,
                    focus = linkFocus,
                    onFocus = { onLinkFocus?.invoke(it) },
                    error = linkError,
                    metrics = linkMetrics,
                    onButtonChange = onButtonChange,
                    touchControlsVisible = touchControlsVisible,
                    onScreenTouch = onScreenTouch,
                    onBack = {
                        onStopLinked?.invoke()
                        screen = RavenScreen.LIBRARY
                    },
                )
            }
        }
        val details = gameDetails
        if (details != null) {
            GameDetailsSheet(
                entry = details,
                cover = libraryCovers[details.id],
                coverBusy = coverBusy,
                importBusy = importBusy,
                saveTransferBusy = saveTransferBusy,
                onPlay = if (onStartVideo != null &&
                    (onLaunchLibraryGame != null || loadedGame != null)
                ) {
                    {
                        if (onLaunchLibraryGame != null) {
                            onLaunchLibraryGame(details.id) {
                                onStartVideo()
                                screen = RavenScreen.PLAYER
                            }
                        } else {
                            onStartVideo()
                            screen = RavenScreen.PLAYER
                        }
                    }
                } else {
                    null
                },
                onPlayLinked = if (onLaunchLinkedGames != null && onStartLinked != null) {
                    { linkPartnerFor = details }
                } else {
                    null
                },
                onChooseCover = onChooseCover,
                onRemoveCover = onRemoveCover,
                onExportGameBackup = onExportGameBackup,
                onExportBatterySave = onExportBatterySave,
                onImportGameSave = onImportGameSave,
                onDismiss = { gameDetails = null },
            )
        }
        val linkGame = linkPartnerFor
        if (linkGame != null) {
            LinkPartnerDialog(
                game = linkGame,
                partners = LinkCable.partners(linkGame, games),
                onPick = { partner ->
                    linkPartnerFor = null
                    onLaunchLinkedGames?.invoke(linkGame.id, partner.id) {
                        onStartLinked?.invoke()
                        screen = RavenScreen.LINK_PLAYER
                    }
                },
                onDismiss = { linkPartnerFor = null },
            )
        }
        val folderSummary = folderImportSummary
        if (folderSummary != null) {
            FolderImportSummaryDialog(folderSummary) { onDismissFolderImportSummary?.invoke() }
        }
        val review = saveImportReview
        if (review != null) {
            SaveImportReviewDialog(
                review = review,
                saveTransferBusy = saveTransferBusy,
                onConfirm = onConfirmSaveImport,
                onCancel = { onCancelSaveImport?.invoke() },
            )
        }
    }
}

