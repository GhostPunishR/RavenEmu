package com.ravenemu.android.deltaskin

import android.graphics.Rect
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView

/**
 * A Delta skin in the player: the game picture at the place the skin gives
 * it, and the skin's PDF drawn on top (its screen area is transparent), which
 * also receives the touches. Same layering as RavenEmu 0.8.1's emulation screen.
 */
@Composable
internal fun DeltaSkinPlayer(
    configuration: DeltaSkinControllerConfiguration,
    frame: ImageBitmap?,
    onButtons: (DeltaSkinButtonChanges) -> Unit,
    onMenu: () -> Unit,
    onError: (DeltaSkinErrorCode) -> Unit,
) {
    var gameArea by remember(configuration) { mutableStateOf<Rect?>(null) }
    val buttons by rememberUpdatedState(onButtons)
    val menu by rememberUpdatedState(onMenu)
    val error by rememberUpdatedState(onError)
    Box(Modifier.fillMaxSize()) {
        val area = gameArea
        if (frame != null && area != null) {
            val density = LocalDensity.current
            Image(
                bitmap = frame,
                contentDescription = "Image du jeu",
                modifier = Modifier
                    .offset { IntOffset(area.left, area.top) }
                    .size(with(density) { area.width().toDp() }, with(density) { area.height().toDp() }),
                contentScale = ContentScale.Fit,
                filterQuality = FilterQuality.None,
            )
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                DeltaSkinControllerView(context).apply {
                    listener = object : DeltaSkinControllerView.Listener {
                        override fun onButtonChanges(changes: DeltaSkinButtonChanges) = buttons(changes)
                        override fun onMenu() = menu()
                        override fun onSkinReady(gameArea: Rect) { gameAreaChanged(gameArea) }
                        override fun onSkinLayoutChanged(gameArea: Rect) { gameAreaChanged(gameArea) }
                        override fun onSkinError(code: DeltaSkinErrorCode) = error(code)

                        private fun gameAreaChanged(rect: Rect) {
                            gameArea = Rect(rect)
                        }
                    }
                }
            },
            // The player recomposes at every game frame: hand the skin over
            // only when it changes, or the view would reload it each frame.
            update = { view ->
                if (view.tag !== configuration) {
                    view.tag = configuration
                    view.setConfiguration(configuration)
                }
            },
            onRelease = { view ->
                view.tag = null
                view.setConfiguration(null)
            },
        )
    }
}
