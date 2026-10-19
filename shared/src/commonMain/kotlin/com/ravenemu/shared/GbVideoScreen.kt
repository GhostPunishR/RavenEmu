package com.ravenemu.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Shared GB player. Android supplies native frames and touch input,
 * while the iOS app still needs native session integration.
 * No audio output or file-save behavior is implied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GbVideoScreen(
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
    onRetroLeaderboard: ((Int, Boolean) -> Unit)?,
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
                retroProgress, retroProgress?.let { retroImages[it.badgeUrl] }, settings.retroPopupPosition)
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
                "FPS ${metrics?.let { oneDecimal(it.actualFps) } ?: "--"}",
                color = RavenText, fontSize = 11.sp, maxLines = 1,
            )
            Text(
                "Moteur ${metrics?.let { oneDecimal(it.coreMs) } ?: "--"} ms",
                color = RavenText, fontSize = 10.sp, maxLines = 1,
            )
            Text(
                "Conv. ${metrics?.let { oneDecimal(it.conversionMs) } ?: "--"} ms",
                color = RavenText, fontSize = 10.sp, maxLines = 1,
            )
        }
        if (skinControls != null) {
            // A Delta skin draws the whole controller and places the game
            // picture itself; its menu button opens the game menu.
            Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
                skinControls(frame) { setGameMenu(true) }
                RetroOverlays(retroIndicators, retroNotice, retroNoticeBadge, retroNoticeVisible,
                    retroProgress, retroProgress?.let { retroImages[it.badgeUrl] }, settings.retroPopupPosition)
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
                    retroProgress, retroProgress?.let { retroImages[it.badgeUrl] }, settings.retroPopupPosition)
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
                        onLeaderboard = onRetroLeaderboard,
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

internal fun oneDecimal(value: Double): String =
    ((value * 10.0).roundToInt() / 10.0).toString()
