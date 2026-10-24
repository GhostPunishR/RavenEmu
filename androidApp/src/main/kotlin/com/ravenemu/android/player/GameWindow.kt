package com.ravenemu.android.player

import android.os.Build
import android.view.Display
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlin.math.abs

/**
 * La fenêtre pendant une partie.
 *
 * - Tant que le jeu tourne ([running]), l'écran reste allumé : une partie
 *   jouée à la manette, ou une scène qu'on regarde, ne doit pas s'éteindre au
 *   bout du délai de veille. Menu du jeu ouvert, l'écran se met en veille
 *   comme d'habitude.
 * - Tant qu'un jeu est à l'écran ([playing]), l'écran bat à une fréquence que
 *   la cadence du jeu divise juste, 60 ou 120 Hz plutôt que 90 Hz : le lecteur
 *   peut alors s'y caler (voir [DisplayCadence]). Hors du lecteur, le système
 *   reprend la main.
 */
@Composable
internal fun GameWindow(window: Window, playing: Boolean, running: Boolean, gameFps: Double?) {
    LaunchedEffect(running) {
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(playing, gameFps) {
        val display = window.decorView.display
        val mode = if (playing && gameFps != null && display != null) displayModeFor(display, gameFps) else 0
        if (window.attributes.preferredDisplayModeId != mode) {
            window.attributes = window.attributes.also { it.preferredDisplayModeId = mode }
        }
    }
}

/**
 * Le mode d'affichage à demander pour un jeu à [gameFps], ou 0 pour laisser
 * faire le système. Seuls comptent les modes de même définition, et, depuis
 * Android 12, ceux vers lesquels l'écran passe sans couper l'image.
 */
private fun displayModeFor(display: Display, gameFps: Double): Int {
    val current = display.mode
    val seamless: List<Float>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        current.alternativeRefreshRates.toList() + current.refreshRate
    } else null
    val modes = display.supportedModes.filter { mode ->
        mode.physicalWidth == current.physicalWidth && mode.physicalHeight == current.physicalHeight &&
            (seamless == null || seamless.any { abs(it - mode.refreshRate) < 0.1f })
    }
    val rate = DisplayCadence.preferredRefreshRate(modes.map { it.refreshRate }, current.refreshRate, gameFps)
        ?: return 0
    return if (abs(rate - current.refreshRate) < 0.1f) current.modeId
    else modes.firstOrNull { abs(it.refreshRate - rate) < 0.1f }?.modeId ?: 0
}
