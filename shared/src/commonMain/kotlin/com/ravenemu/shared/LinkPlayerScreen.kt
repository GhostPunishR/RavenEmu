package com.ravenemu.shared

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Two games joined by a virtual link cable. Only the [focus] game is shown,
 * as large as a game played alone; the switch button in the bar shows the
 * other one, which keeps running behind. The shown game gets the touch
 * controls, a physical controller and the sound.
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
    val other = 1 - focus
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).background(RavenBackground),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                RavenIcon(RavenGlyph.BACK, RavenText)
            }
            Column(Modifier.weight(1f)) {
                Text("Câble link · Joueur ${focus + 1}", color = RavenText, fontSize = 16.sp, maxLines = 1)
                Text(
                    games.getOrNull(focus)?.filename.orEmpty(),
                    color = RavenMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = { onFocus(other) },
                modifier = Modifier.padding(horizontal = 8.dp).height(36.dp)
                    .semantics { contentDescription = "Passer au joueur ${other + 1}" },
                contentPadding = PaddingValues(horizontal = 12.dp),
                border = BorderStroke(1.dp, RavenAccent),
            ) {
                Text("⇄ Joueur ${other + 1}", color = RavenAccent, fontSize = 13.sp, maxLines = 1)
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
        LinkedGameScreen(
            index = focus,
            game = games.getOrNull(focus),
            frame = frames.getOrNull(focus),
            error = error,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(6.dp)
                // Watches touches without taking them, to bring the touch
                // controls back after a physical controller hid them.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        screenTouch?.invoke()
                    }
                },
        )
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
    error: String?,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val aspect = if (frame != null && frame.height > 0) frame.width.toFloat() / frame.height
            else if (game?.platform == GamePlatform.GBA) 240f / 160f else 160f / 144f
        val pictureHeight = minOf(maxHeight, maxWidth / aspect).coerceAtLeast(0.dp)
        Box(
            Modifier.width(pictureHeight * aspect).height(pictureHeight),
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
