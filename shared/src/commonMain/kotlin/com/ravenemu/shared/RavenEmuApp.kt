package com.ravenemu.shared

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/*
 * RavenEmu 0.8.1 visual reference:
 * app/android/src/main/res/layout/activity_main.xml
 * app/android/src/main/res/layout/item_rom_grid.xml
 * app/android/src/main/res/values/colors.xml
 *
 * Reimplemented with shared Compose so Android and iOS show the same screens.
 */
private val RavenBackground = Color(0xFF0B0C12)
private val RavenSurface = Color(0xFF191B28)
private val RavenAccent = Color(0xFFA78BFA)
private val RavenText = Color(0xFFF5F2FF)
private val RavenSecondary = Color(0xFFCBC4DC)
private val RavenMuted = Color(0xFFAAA2BC)
private val RavenOutline = Color(0xFF3C4052)
private val RavenDanger = Color(0xFFFF928B)

private enum class RavenScreen { LIBRARY, SETTINGS, ABOUT, PLAYER }
private enum class RavenGlyph { SETTINGS, ADD, MORE, SEARCH, BACK }

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
                        importEnabled = onImportRom != null && !importBusy,
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
            }
        }
        if (gameDetails != null) {
            ModalBottomSheet(
                onDismissRequest = { gameDetails = null },
                containerColor = RavenSurface,
                contentColor = RavenText,
            ) {
                val entry = gameDetails!!
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(entry.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(platformLabel(entry.platform), color = RavenSecondary, fontSize = 13.sp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        CoverArtwork(
                            entry.platform, libraryCovers[entry.id],
                            Modifier.width(90.dp).height(105.dp),
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                when {
                                    libraryCovers.containsKey(entry.id) ->
                                        "Jaquette enregistrée dans RavenEmu"
                                    coverBusy -> "Recherche de jaquette…"
                                    else -> "Jaquette introuvable automatiquement"
                                },
                                color = RavenMuted, fontSize = 13.sp,
                            )
                            if (onChooseCover != null) {
                                OutlinedButton(
                                    onClick = { onChooseCover(entry.id) },
                                    enabled = !coverBusy && !importBusy,
                                ) {
                                    Text(
                                        if (libraryCovers.containsKey(entry.id))
                                            "Changer l'image"
                                        else "Image manuelle (facultatif)",
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }
                    if (libraryCovers.containsKey(entry.id) && onRemoveCover != null) {
                        TextButton(
                            onClick = { onRemoveCover(entry.id) },
                            enabled = !coverBusy,
                        ) { Text("Retirer la jaquette", color = RavenAccent) }
                    }
                    Text(
                        "Vidéo, commandes et audio Game Boy, Game Boy Color et Game Boy Advance disponibles sur Android. La sauvegarde interne du jeu est automatique et un save state manuel est disponible dans le menu ⋮.",
                        color = RavenMuted,
                        fontSize = 13.sp,
                    )
                    if (onStartVideo != null &&
                        (onLaunchLibraryGame != null || loadedGame != null)
                    ) {
                        Button(
                            onClick = {
                                gameDetails = null
                                if (onLaunchLibraryGame != null) {
                                    onLaunchLibraryGame(entry.id) {
                                        onStartVideo()
                                        screen = RavenScreen.PLAYER
                                    }
                                } else {
                                    onStartVideo()
                                    screen = RavenScreen.PLAYER
                                }
                            },
                            enabled = !importBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RavenAccent,
                                contentColor = RavenBackground,
                            ),
                        ) {
                            Text("Jouer")
                        }
                    }
                    if (onExportGameBackup != null || onImportGameSave != null) {
                        Text("Sauvegardes", color = RavenText,
                            fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Transférez votre progression sans exporter la ROM.",
                            color = RavenMuted, fontSize = 12.sp,
                        )
                        if (onExportGameBackup != null) {
                            FilledTonalButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !importBusy && !saveTransferBusy,
                                onClick = { onExportGameBackup(entry.id) },
                            ) { Text("Exporter toutes les sauvegardes (.zip)") }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (onExportBatterySave != null) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = !importBusy && !saveTransferBusy,
                                    onClick = { onExportBatterySave(entry.id) },
                                ) { Text("Exporter .sav", fontSize = 12.sp) }
                            }
                            if (onImportGameSave != null) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = !importBusy && !saveTransferBusy,
                                    onClick = { onImportGameSave(entry.id) },
                                ) { Text("Importer", fontSize = 12.sp) }
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { gameDetails = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Fermer")
                    }
                }
            }
        }
        val folderSummary = folderImportSummary
        if (folderSummary != null) {
            AlertDialog(
                onDismissRequest = { onDismissFolderImportSummary?.invoke() },
                title = { Text("Importation du dossier terminée") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Jeux ajoutés : ${folderSummary.added}")
                        Text("Doublons ignorés : ${folderSummary.duplicate}")
                        Text("ROM invalides : ${folderSummary.invalid}")
                        if (folderSummary.unreadable > 0) {
                            Text("Fichiers ou dossiers illisibles : ${folderSummary.unreadable}")
                        }
                        Text("Autres fichiers ignorés : ${folderSummary.otherFiles}",
                            color = RavenMuted, fontSize = 13.sp)
                        Text("Dossiers parcourus : ${folderSummary.scannedFolders}",
                            color = RavenMuted, fontSize = 13.sp)
                        if (folderSummary.limited) {
                            Text("Parcours arrêté par une limite de sécurité ou de stockage. Les jeux déjà ajoutés sont conservés.",
                                color = RavenDanger, fontSize = 13.sp)
                        }
                        Text("Les jeux existants, leurs sauvegardes et la partie en cours n'ont pas été modifiés.",
                            color = RavenSecondary, fontSize = 12.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = { onDismissFolderImportSummary?.invoke() }) {
                        Text("Terminer", color = RavenAccent)
                    }
                },
                containerColor = RavenSurface,
                titleContentColor = RavenText,
                textContentColor = RavenText,
            )
        }
        val review = saveImportReview
        if (review != null) {
            AlertDialog(
                onDismissRequest = { onCancelSaveImport?.invoke() },
                title = { Text("Importer les sauvegardes ?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Jeu : ${review.gameTitle}")
                        Text(
                            "Contenu : " +
                                (if (review.hasBattery) "sauvegarde .sav" else "pas de .sav") +
                                (if (review.slots.isEmpty()) "" else
                                    ", états ${review.slots.joinToString(", ")}"),
                        )
                        if (review.rawSav) {
                            Text(
                                "Un fichier .sav seul n'identifie pas sa ROM. Vérifiez que vous avez choisi le bon jeu.",
                                color = RavenDanger, fontSize = 13.sp,
                            )
                        }
                        if (review.conflicts.isNotEmpty()) {
                            Text(
                                "Des données existantes seront remplacées : " +
                                    review.conflicts.joinToString(", ") + ".",
                                color = RavenDanger, fontSize = 13.sp,
                            )
                        } else {
                            Text("Les autres sauvegardes resteront inchangées.",
                                color = RavenSecondary, fontSize = 13.sp)
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !saveTransferBusy && onConfirmSaveImport != null,
                        onClick = { onConfirmSaveImport?.invoke() },
                    ) { Text("Confirmer l'import", color = RavenAccent) }
                },
                dismissButton = {
                    TextButton(onClick = { onCancelSaveImport?.invoke() }) {
                        Text("Annuler", color = RavenSecondary)
                    }
                },
                containerColor = RavenSurface,
                titleContentColor = RavenText,
                textContentColor = RavenText,
            )
        }
    }
}


/**
 * Shared GB player. Android supplies native frames and touch input,
 * while the iOS app still needs native session integration.
 * No audio output or file-save behavior is implied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GbVideoScreen(
    settings: Settings,
    title: String,
    platform: GamePlatform,
    frame: ImageBitmap?,
    error: String?,
    metrics: GbVideoMetrics?,
    onButtonChange: ((GbButton, Boolean) -> Unit)?,
    touchControlsVisible: Boolean,
    onScreenTouch: (() -> Unit)?,
    onExportAudioDiagnostics: (() -> Unit)?,
    onSaveState: ((Int) -> Unit)?,
    onLoadState: ((Int) -> Unit)?,
    onDeleteState: ((Int) -> Unit)?,
    stateSlots: List<GbStateSlot>,
    stateSlotsLoading: Boolean,
    stateOperationBusy: Boolean,
    onGameMenuVisibilityChange: ((Boolean) -> Unit)?,
    retroSnapshot: RetroSnapshot,
    retroImages: Map<String, ImageBitmap>,
    retroBusy: Boolean,
    onRetroLogin: ((String, String) -> Unit)?,
    onRetroDisconnect: (() -> Unit)?,
    onRetroRetry: (() -> Unit)?,
    onOpenLink: ((String) -> Unit)?,
    retroNotice: String?,
    retroNoticeBadge: ImageBitmap?,
    retroNoticeVisible: Boolean,
    retroIndicators: List<String>,
    retroProgress: RetroProgress?,
    stateLoadAllowed: Boolean,
    onRequestGameMenu: (() -> Boolean)?,
    skinControls: (@Composable (frame: ImageBitmap?, openMenu: () -> Unit) -> Unit)?,
    controllerMode: Boolean,
    onControllerModeChange: ((Boolean) -> Unit)?,
    gameMenuRequests: Int,
    onBack: () -> Unit,
) {
    var gameMenuOpen by remember { mutableStateOf(false) }
    var retroPanelOpen by remember { mutableStateOf(false) }
    var selectedSlot by remember { mutableStateOf(0) }
    var confirmation by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun setGameMenu(open: Boolean) {
        if (gameMenuOpen == open) return
        // Hardcore may refuse a pause (the host says why).
        if (open && onRequestGameMenu?.invoke() == false) return
        gameMenuOpen = open
        onGameMenuVisibilityChange?.invoke(open)
    }
    // Only requests made while this screen is shown open the menu.
    var seenMenuRequests by remember { mutableStateOf(gameMenuRequests) }
    LaunchedEffect(gameMenuRequests) {
        if (gameMenuRequests != seenMenuRequests) {
            seenMenuRequests = gameMenuRequests
            setGameMenu(true)
        }
    }
    if (controllerMode) {
        // Controller mode: the picture alone, as large as the screen allows.
        // No bar and no touch controls; a touch opens the game menu.
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { setGameMenu(true) },
            contentAlignment = Alignment.Center,
        ) {
            val aspect = if (frame != null && frame.height > 0) {
                frame.width.toFloat() / frame.height
            } else if (platform == GamePlatform.GBA) 240f / 160f else 160f / 144f
            val pictureHeight = minOf(maxHeight, maxWidth / aspect)
            if (frame != null) {
                Image(
                    bitmap = frame,
                    contentDescription = "Image du jeu ${platformLabel(platform)}",
                    modifier = Modifier.width(pictureHeight * aspect).height(pictureHeight),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.None,
                )
            } else {
                Text(
                    if (error == null) "Préparation de l'image du jeu..." else error,
                    color = if (error == null) RavenMuted else RavenDanger,
                    modifier = Modifier.padding(20.dp),
                    textAlign = TextAlign.Center,
                )
            }
            RetroOverlays(retroIndicators, retroNotice, retroNoticeBadge, retroNoticeVisible,
                retroProgress, retroProgress?.let { retroImages[it.badgeUrl] })
        }
    } else Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(RavenBackground),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                RavenIcon(RavenGlyph.BACK, RavenText)
            }
            Text(
                title,
                color = RavenText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f),
            )
            if (onExportAudioDiagnostics != null || onSaveState != null || onLoadState != null) {
                IconButton(
                    onClick = { setGameMenu(true) },
                    modifier = Modifier.size(48.dp),
                ) {
                    RavenIcon(RavenGlyph.MORE, RavenText)
                }
            }
        }
        // The debug bar is optional; enabled by default for the RavenEmu preview.
        if (settings.showDiagnostics) Row(
            modifier = Modifier.fillMaxWidth().height(32.dp)
                .background(RavenSurface).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "FPS ${metrics?.let { oneDecimal(it.actualFps) } ?: "--"} / ${metrics?.let { oneDecimal(it.targetFps) } ?: "--"}",
                color = RavenAccent, fontSize = 11.sp, maxLines = 1,
            )
            Text(
                "Moteur ${metrics?.let { oneDecimal(it.coreMs) } ?: "--"} ms",
                color = RavenSecondary, fontSize = 10.sp, maxLines = 1,
            )
            Text(
                "Conv. ${metrics?.let { oneDecimal(it.conversionMs) } ?: "--"} ms",
                color = RavenMuted, fontSize = 10.sp, maxLines = 1,
            )
        }
        if (skinControls != null) {
            // A Delta skin draws the whole controller and places the game
            // picture itself; its menu button opens the game menu.
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                skinControls(frame) { setGameMenu(true) }
                RetroOverlays(retroIndicators, retroNotice, retroNoticeBadge, retroNoticeVisible,
                    retroProgress, retroProgress?.let { retroImages[it.badgeUrl] })
            }
        } else {
            // Pin the picture to the top of its available area rather than
            // vertically centering it. The remaining space sits below the video,
            // allowing the thumb controls to remain anchored near the safe bottom.
            val screenTouch by rememberUpdatedState(onScreenTouch)
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black)
                    // Watches touches without taking them, to bring the touch
                    // controls back after a physical controller hid them.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            screenTouch?.invoke()
                        }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                // 160x144 on GB/GBC, 240x160 on GBA: follow the core's frame.
                val aspect = if (frame != null && frame.height > 0) {
                    frame.width.toFloat() / frame.height
                } else if (platform == GamePlatform.GBA) 240f / 160f else 160f / 144f
                val pictureHeight = minOf(maxHeight, maxWidth / aspect)
                val pictureWidth = pictureHeight * aspect
                if (frame != null) {
                    Image(
                        bitmap = frame,
                        contentDescription = "Image du jeu ${platformLabel(platform)}",
                        modifier = Modifier.width(pictureWidth).height(pictureHeight),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Text(
                        if (error == null) "Préparation de l'image du jeu..." else error,
                        color = if (error == null) RavenMuted else RavenDanger,
                        modifier = Modifier.padding(20.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                RetroOverlays(retroIndicators, retroNotice, retroNoticeBadge, retroNoticeVisible,
                    retroProgress, retroProgress?.let { retroImages[it.badgeUrl] })
            }
            if (onButtonChange != null && !touchControlsVisible) {
                Text(
                    "Manette connectée · touchez l'écran pour afficher les commandes",
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    color = RavenMuted, fontSize = 12.sp, textAlign = TextAlign.Center,
                )
            } else if (onButtonChange != null) {
                GbTouchControls(
                    onButton = onButtonChange,
                    haptics = settings.haptics,
                    scalePercent = settings.controlsScale,
                    verticalGapDp = settings.controlsGap,
                    highContrast = settings.controlsStyle == "contrast",
                    shoulders = platform == GamePlatform.GBA,
                    // Keep the lower pills slightly above the system navigation bar,
                    // matching the requested default control placement.
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            }
        }

    }

    if (gameMenuOpen) {
        ModalBottomSheet(
            onDismissRequest = { setGameMenu(false) },
            containerColor = RavenSurface,
            contentColor = RavenText,
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 18.dp, end = 18.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (retroPanelOpen) {
                    TextButton(onClick = { retroPanelOpen = false }) {
                        Text("‹ Retour au menu du jeu", color = RavenAccent)
                    }
                    RetroAchievementsContent(
                        snapshot = retroSnapshot,
                        images = retroImages,
                        loginBusy = retroBusy,
                        onLogin = onRetroLogin,
                        onDisconnect = onRetroDisconnect,
                        onRetry = onRetroRetry,
                        onOpenLink = onOpenLink,
                    )
                } else {
                Text("Menu du jeu", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                if (onRetroLogin != null) {
                    OutlinedButton(
                        onClick = { retroPanelOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("RetroAchievements  ·  ${retroSnapshot.status}") }
                }
                Text(title, color = RavenSecondary, fontSize = 13.sp)
                if (onControllerModeChange != null) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Mode manette", color = RavenText, fontSize = 15.sp)
                            Text("Paysage plein écran, sans commandes tactiles. Touchez l'écran " +
                                "ou le bouton Home de la manette pour revenir à ce menu.",
                                color = RavenMuted, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = controllerMode, onCheckedChange = onControllerModeChange)
                    }
                }
                Text("Partie en pause · 4 emplacements de sauvegarde",
                    color = RavenMuted, fontSize = 12.sp)
                // Compact switcher: only the selected slot is expanded.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (index in 0 until 4) {
                        val occupied = stateSlots.firstOrNull { it.index == index }?.occupied == true
                        val active = selectedSlot == index
                        Box(
                            Modifier.weight(1f).height(44.dp)
                                .background(
                                    if (active) RavenAccent else Color(0xFF292A38),
                                    RoundedCornerShape(12.dp),
                                )
                                .clickable { selectedSlot = index },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("${index + 1}",
                                    color = if (active) RavenBackground else RavenText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold)
                                if (occupied) {
                                    Box(Modifier.size(5.dp).background(
                                        if (active) RavenBackground else RavenAccent, CircleShape))
                                }
                            }
                        }
                    }
                }
                if (stateSlotsLoading) {
                    Row(
                        Modifier.fillMaxWidth().height(96.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Chargement des emplacements…", color = RavenMuted, fontSize = 12.sp)
                    }
                } else {
                    val entry = stateSlots.firstOrNull { it.index == selectedSlot }
                    val occupied = entry?.occupied == true
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF202230)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    Modifier.width(94.dp).height(84.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(Color(0xFF10111B))
                                        .border(1.dp, RavenOutline, RoundedCornerShape(9.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (entry?.thumbnail != null) {
                                        Image(entry.thumbnail,
                                            "Aperçu de l'emplacement ${selectedSlot + 1}",
                                            Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Fit,
                                            filterQuality = FilterQuality.None)
                                    } else {
                                        Text("GB", color = RavenMuted, fontSize = 16.sp)
                                    }
                                }
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Text("Emplacement ${selectedSlot + 1}",
                                        color = RavenText, fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp)
                                    Text(entry?.savedAt ?: "Vide",
                                        color = RavenMuted, fontSize = 12.sp)
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FilledTonalButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = !stateOperationBusy && onSaveState != null,
                                    onClick = {
                                        if (occupied) confirmation = selectedSlot to "overwrite"
                                        else onSaveState?.invoke(selectedSlot)
                                    },
                                ) { Text("Sauver", fontSize = 12.sp) }
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = occupied && stateLoadAllowed && !stateOperationBusy &&
                                        onLoadState != null,
                                    onClick = {
                                        onLoadState?.invoke(selectedSlot)
                                        setGameMenu(false)
                                    },
                                ) { Text("Charger", fontSize = 12.sp) }
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = occupied && !stateOperationBusy && onDeleteState != null,
                                    onClick = { confirmation = selectedSlot to "delete" },
                                ) { Text("Supprimer", fontSize = 11.sp) }
                            }
                        }
                    }
                }
                if (onExportAudioDiagnostics != null) {
                    OutlinedButton(
                        onClick = onExportAudioDiagnostics,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Exporter le diagnostic audio") }
                }
                Button(
                    onClick = { setGameMenu(false) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = RavenAccent, contentColor = RavenBackground,
                    ),
                ) { Text("Reprendre le jeu") }
                }
            }
        }
    }
    val action = confirmation
    if (action != null) {
        val index = action.first
        val deleting = action.second == "delete"
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (deleting) "Supprimer la sauvegarde ?" else "Écraser la sauvegarde ?") },
            text = {
                Text(
                    if (deleting)
                        "Supprimer définitivement l'emplacement ${index + 1} ? La sauvegarde classique du jeu restera intacte."
                    else
                        "Remplacer l'état de l'emplacement ${index + 1} par la partie actuelle ? Cette action est irréversible."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !stateOperationBusy,
                    onClick = {
                        confirmation = null
                        if (deleting) onDeleteState?.invoke(index)
                        else onSaveState?.invoke(index)
                    },
                ) { Text(if (deleting) "Supprimer" else "Écraser") }
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text("Annuler") }
            },
            containerColor = RavenSurface,
            titleContentColor = RavenText,
            textContentColor = RavenSecondary,
        )
    }
}


@Composable
private fun LibraryToolbar(
    title: String,
    importEnabled: Boolean,
    listMode: Boolean,
    onSettings: () -> Unit,
    onAdd: () -> Unit,
    onToggleView: () -> Unit,
    onImportFolder: (() -> Unit)?,
    onAbout: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        Text(
            title,
            Modifier.align(Alignment.Center).padding(horizontal = 104.dp),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = RavenText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
            // Exact RavenEmu 0.8.1 toolbar icon, white and borderless; + and ⋮ unchanged.
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                RavenIcon(RavenIconType.SETTINGS, RavenText)
            }
        }
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
            IconButton(
                onClick = onAdd,
                enabled = importEnabled,
                modifier = Modifier.size(48.dp),
            ) {
                RavenIcon(RavenGlyph.ADD, if (importEnabled) RavenAccent else RavenMuted)
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(48.dp),
                ) { RavenIcon(RavenGlyph.MORE, RavenText) }
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.background(RavenSurface),
                ) {
                    DropdownMenuItem(
                        text = { Text(if (listMode) "Vue grille" else "Vue liste", color = RavenText) },
                        onClick = { menuOpen = false; onToggleView() },
                    )
                    if (onImportFolder != null) {
                        DropdownMenuItem(
                            text = { Text("Importer un dossier", color = RavenText) },
                            enabled = importEnabled,
                            onClick = { menuOpen = false; onImportFolder() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("À propos", color = RavenText) },
                        onClick = { menuOpen = false; onAbout() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = RavenText, fontSize = 15.sp),
        cursorBrush = SolidColor(RavenAccent),
        modifier = Modifier.fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .height(44.dp)
            .border(1.dp, RavenOutline.copy(alpha = 0.8f),
                RoundedCornerShape(14.dp))
            .background(RavenSurface, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp),
        decorationBox = { field ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RavenIcon(RavenGlyph.SEARCH, RavenMuted)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text("Rechercher un jeu", color = RavenMuted, fontSize = 15.sp)
                    }
                    field()
                }
            }
        },
    )
}

@Composable
private fun LibraryPageContent(
    entries: List<GameEntry>,
    covers: Map<String, ImageBitmap>,
    emptyMessage: String,
    listMode: Boolean,
    onSelect: (GameEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                emptyMessage,
                Modifier.padding(32.dp),
                textAlign = TextAlign.Center,
                color = RavenMuted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
            )
        }
    } else if (listMode) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(1),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                Row(
                    Modifier.fillMaxWidth()
                        .background(RavenSurface.copy(alpha = 0.75f),
                            RoundedCornerShape(10.dp))
                        .clickable { onSelect(entry) }
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverArtwork(
                        entry.platform, covers[entry.id],
                        Modifier.width(44.dp).height(52.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(entry.title, color = RavenText, fontSize = 15.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(platformLabel(entry.platform), color = RavenMuted, fontSize = 12.sp)
                    }
                }
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 106.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 8.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                Column(
                    modifier = Modifier.clickable { onSelect(entry) }
                        .padding(start = 4.dp, top = 6.dp, end = 4.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CoverArtwork(entry.platform, covers[entry.id],
                        Modifier.fillMaxWidth().aspectRatio(6f / 7f))
                    Spacer(Modifier.height(6.dp))
                    Text(entry.title, color = RavenSecondary, fontSize = 11.sp,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        lineHeight = 14.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Display imported artwork; never substitute invented or downloaded game covers. */
@Composable
private fun CoverArtwork(
    platform: GamePlatform,
    bitmap: ImageBitmap?,
    modifier: Modifier,
) {
    if (bitmap == null) {
        CoverPlaceholder(platform, modifier)
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "Jaquette personnalisée",
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, RavenOutline.copy(alpha = 0.75f),
                    RoundedCornerShape(10.dp)),
            contentScale = ContentScale.Crop,
        )
    }
}

/* No invented artwork. A neutral placeholder is used until covers are migrated. */
@Composable
private fun CoverPlaceholder(platform: GamePlatform, modifier: Modifier) {
    Box(
        modifier.border(1.dp, RavenOutline.copy(alpha = 0.75f),
            RoundedCornerShape(10.dp))
            .background(RavenSurface, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.width(24.dp).height(3.dp)
                .background(RavenAccent.copy(alpha = 0.65f), CircleShape))
            Text(
                platform.name,
                color = RavenSecondary.copy(alpha = 0.85f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** One console pictogram per library page, instead of dots; tap to switch. */
@Composable
private fun PageIndicator(pages: List<LibraryPage>, currentPage: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(40.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pages.size > 1) {
            pages.forEachIndexed { index, page ->
                val active = index == currentPage
                Box(
                    Modifier.padding(horizontal = 4.dp).size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (active) RavenAccent.copy(alpha = 0.16f) else Color.Transparent)
                        .selectable(selected = active, role = Role.Tab) { onSelect(index) }
                        .semantics { contentDescription = page.title },
                    contentAlignment = Alignment.Center,
                ) {
                    ConsoleIcon(
                        page = page,
                        tint = if (active) RavenAccent else RavenMuted,
                        active = active,
                        modifier = Modifier.size(if (active) 24.dp else 21.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Subscreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            RavenIcon(RavenGlyph.BACK, RavenText)
        }
        Text(title, fontSize = 20.sp, color = RavenText, fontWeight = FontWeight.Medium)
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

private fun platformLabel(platform: GamePlatform): String = when (platform) {
    GamePlatform.GB -> "Game Boy"
    GamePlatform.GBC -> "Game Boy Color"
    GamePlatform.GBA -> "Game Boy Advance"
}

/* Vector icons render consistently on Android and iOS without system glyphs. */
@Composable
private fun RavenIcon(icon: RavenGlyph, tint: Color) {
    Canvas(Modifier.size(24.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val line = 2.dp.toPx()
        when (icon) {
            RavenGlyph.ADD -> {
                drawLine(tint, Offset(cx, size.height * .16f),
                    Offset(cx, size.height * .84f), strokeWidth = line)
                drawLine(tint, Offset(size.width * .16f, cy),
                    Offset(size.width * .84f, cy), strokeWidth = line)
            }
            RavenGlyph.MORE -> {
                for (fraction in listOf(.24f, .5f, .76f)) {
                    drawCircle(tint, radius = 1.8.dp.toPx(),
                        center = Offset(cx, size.height * fraction))
                }
            }
            RavenGlyph.SEARCH -> {
                drawCircle(tint, radius = size.minDimension * .27f,
                    center = Offset(cx * .88f, cy * .86f),
                    style = Stroke(width = line))
                drawLine(tint, Offset(size.width * .63f, size.height * .63f),
                    Offset(size.width * .85f, size.height * .85f), strokeWidth = line)
            }
            RavenGlyph.BACK -> {
                drawLine(tint, Offset(size.width * .6f, size.height * .18f),
                    Offset(size.width * .28f, cy), strokeWidth = line)
                drawLine(tint, Offset(size.width * .28f, cy),
                    Offset(size.width * .6f, size.height * .82f), strokeWidth = line)
            }
            RavenGlyph.SETTINGS -> {
                drawCircle(tint, radius = size.minDimension * .28f,
                    center = Offset(cx, cy), style = Stroke(width = line))
                drawCircle(tint, radius = size.minDimension * .09f,
                    center = Offset(cx, cy), style = Stroke(width = line))
                for (i in 0..7) {
                    val angle = i.toDouble() * PI / 4
                    val x = cos(angle).toFloat()
                    val y = sin(angle).toFloat()
                    drawLine(tint,
                        Offset(cx + x * size.minDimension * .32f,
                            cy + y * size.minDimension * .32f),
                        Offset(cx + x * size.minDimension * .44f,
                            cy + y * size.minDimension * .44f),
                        strokeWidth = line)
                }
            }
        }
    }
}

private fun oneDecimal(value: Double): String =
    ((value * 10.0).roundToInt() / 10.0).toString()

/**
 * RetroAchievements overlays of the player: challenge and tracker lines at
 * the bottom; at the top, the one-at-a-time popup and, under it, the
 * measured progress of an achievement, both with the achievement's badge.
 * Shared by the classic layout and Delta skins.
 */
@Composable
private fun BoxScope.RetroOverlays(
    retroIndicators: List<String>,
    retroNotice: String?,
    retroNoticeBadge: ImageBitmap?,
    retroNoticeVisible: Boolean,
    retroProgress: RetroProgress?,
    retroProgressBadge: ImageBitmap?,
) {
    if (retroIndicators.isNotEmpty()) {
        // Persistent, non-interactive: challenges and trackers.
        Column(
            Modifier.align(Alignment.BottomCenter)
                .padding(bottom = 10.dp, start = 12.dp, end = 12.dp)
                .background(RavenSurface.copy(alpha = 0.88f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (label in retroIndicators.take(4)) {
                Text(label, color = RavenText, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    val noticeAlpha by animateFloatAsState(
        if (retroNotice != null && retroNoticeVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "retroNotice",
    )
    // Kept while fading out, so the card leaves with its last content.
    // Plain holder, not state: it only follows the parameters.
    val held = remember { HeldProgress() }
    if (retroProgress != null) {
        held.progress = retroProgress
        held.badge = retroProgressBadge
    }
    val progressAlpha by animateFloatAsState(
        if (retroProgress != null) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "retroProgress",
    )
    val showNotice = retroNotice != null && noticeAlpha > 0f
    val progress = held.progress
    val showProgress = progress != null && progressAlpha > 0f
    if (!showNotice && !showProgress) return
    Column(
        Modifier.align(Alignment.TopCenter).padding(top = 10.dp, start = 12.dp, end = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showNotice) {
            RetroPopupCard(retroNotice.orEmpty(), retroNoticeBadge, noticeAlpha)
        }
        if (showProgress && progress != null) {
            val text = listOf("Progression · ${progress.title}", progress.detail)
                .filter { it.isNotBlank() }.joinToString("\n")
            RetroPopupCard(text, held.badge, progressAlpha)
        }
    }
}

private class HeldProgress {
    var progress: RetroProgress? = null
    var badge: ImageBitmap? = null
}

/**
 * One RetroAchievements popup: the achievement's badge, or the star when it
 * is not available (offline, or a notice without achievement), then the text.
 * Fixed badge slot: the popup keeps the same size either way.
 */
@Composable
private fun RetroPopupCard(text: String, badge: ImageBitmap?, alpha: Float) {
    Row(
        Modifier.alpha(alpha)
            .background(RavenSurface, RoundedCornerShape(12.dp))
            .border(1.dp, RavenAccent, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (badge != null) {
                Image(
                    badge, "Badge du succès",
                    Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text("★", color = RavenAccent, fontSize = 21.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(text, color = RavenText, fontSize = 12.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
