package com.ravenemu.shared

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Two games joined by a virtual link cable, stacked in portrait and side by
 * side in landscape. Touching a game gives it the controls and the sound:
 * the touch controls and a physical controller drive the [focus] game.
 */
@Composable
internal fun LinkPlayerScreen(
    settings: Settings,
    games: List<LoadedGameInfo>,
    frames: List<ImageBitmap?>,
    focus: Int,
    onFocus: (Int) -> Unit,
    error: String?,
    metrics: GbVideoMetrics?,
    onButtonChange: ((GbButton, Boolean) -> Unit)?,
    touchControlsVisible: Boolean,
    onScreenTouch: (() -> Unit)?,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(RavenBackground),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                RavenIcon(RavenGlyph.BACK, RavenText)
            }
            Column(Modifier.weight(1f)) {
                Text("Câble link", color = RavenText, fontSize = 16.sp, maxLines = 1)
                Text(
                    "Touchez un écran pour le piloter",
                    color = RavenMuted, fontSize = 11.sp, maxLines = 1,
                )
            }
            IconButton(onClick = { onFocus(1 - focus) }, modifier = Modifier.size(48.dp)) {
                Text("⇄", color = RavenAccent, fontSize = 20.sp)
            }
        }
        if (settings.showDiagnostics) Row(
            Modifier.fillMaxWidth().height(28.dp).background(RavenSurface).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "FPS ${metrics?.let { oneDecimal(it.actualFps) } ?: "--"} / ${metrics?.let { oneDecimal(it.targetFps) } ?: "--"}",
                color = RavenAccent, fontSize = 11.sp, maxLines = 1,
            )
            Text(
                "Deux moteurs ${metrics?.let { oneDecimal(it.coreMs) } ?: "--"} ms",
                color = RavenSecondary, fontSize = 10.sp, maxLines = 1,
            )
        }
        val screenTouch by rememberUpdatedState(onScreenTouch)
        BoxWithConstraints(
            Modifier.fillMaxWidth().weight(1f).padding(6.dp)
                // Watches touches without taking them, to bring the touch
                // controls back after a physical controller hid them.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        screenTouch?.invoke()
                    }
                },
        ) {
            val sideBySide = maxWidth > maxHeight
            val screens = @Composable { modifier: Modifier, index: Int ->
                LinkedGameScreen(
                    index = index,
                    game = games.getOrNull(index),
                    frame = frames.getOrNull(index),
                    focused = index == focus,
                    error = error,
                    onFocus = onFocus,
                    modifier = modifier,
                )
            }
            if (sideBySide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    screens(Modifier.weight(1f).fillMaxHeight(), 0)
                    screens(Modifier.weight(1f).fillMaxHeight(), 1)
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    screens(Modifier.weight(1f).fillMaxWidth(), 0)
                    screens(Modifier.weight(1f).fillMaxWidth(), 1)
                }
            }
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
                shoulders = games.getOrNull(focus)?.platform == GamePlatform.GBA,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun LinkedGameScreen(
    index: Int,
    game: LoadedGameInfo?,
    frame: ImageBitmap?,
    focused: Boolean,
    error: String?,
    onFocus: (Int) -> Unit,
    modifier: Modifier,
) {
    val accent = if (focused) RavenAccent else RavenMuted
    BoxWithConstraints(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onFocus(index) },
        contentAlignment = Alignment.Center,
    ) {
        val labelHeight: Dp = 18.dp
        val aspect = if (frame != null && frame.height > 0) frame.width.toFloat() / frame.height
            else if (game?.platform == GamePlatform.GBA) 240f / 160f else 160f / 144f
        val pictureHeight = minOf(maxHeight - labelHeight, maxWidth / aspect).coerceAtLeast(0.dp)
        val pictureWidth = pictureHeight * aspect
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Joueur ${index + 1} · ${game?.filename.orEmpty()}",
                Modifier.width(pictureWidth).height(labelHeight),
                color = accent, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Box(
                Modifier.width(pictureWidth).height(pictureHeight)
                    .border(2.dp, if (focused) RavenAccent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (frame != null) {
                    Image(
                        bitmap = frame,
                        contentDescription = "Image du joueur ${index + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None,
                    )
                } else {
                    Text(
                        error ?: "Préparation de l'image…",
                        color = if (error == null) RavenMuted else RavenDanger,
                        fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}
