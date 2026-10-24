package com.ravenemu.android.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Display

/**
 * Synchronisations de l'écran, relevées par Choreographer sur le thread
 * principal et lues par le thread d'émulation (voir [DisplayCadence]).
 *
 * Tant qu'il tourne, chaque rafraîchissement enregistre l'instant de sa
 * synchronisation, dans la base de temps de `System.nanoTime()`, et la période
 * de l'écran à ce moment : elle change quand le lecteur demande 60 ou 120 Hz.
 * Un relevé de retard, quand le thread principal a manqué un rafraîchissement,
 * reste juste : la grille des synchronisations ne bouge pas.
 */
internal class DisplayVsync(context: Context) : Choreographer.FrameCallback {
    private val display: Display? =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
    private val main = Handler(Looper.getMainLooper())
    // Lu et écrit sur le thread principal seulement.
    private var running = false

    /** Dernière synchronisation connue ; 0 tant qu'aucune n'est relevée. */
    @Volatile var timeNanos = 0L
        private set

    /** Période de l'écran ; 0 si elle est inconnue. */
    @Volatile var periodNanos = 0L
        private set

    fun start() {
        main.post {
            if (running) return@post
            running = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        main.post {
            running = false
            Choreographer.getInstance().removeFrameCallback(this)
            timeNanos = 0L
            periodNanos = 0L
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        periodNanos = DisplayCadence.periodNanos(display?.refreshRate?.toDouble() ?: 0.0)
        timeNanos = frameTimeNanos
        Choreographer.getInstance().postFrameCallback(this)
    }
}
