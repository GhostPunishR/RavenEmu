package com.ravenemu.android.player

import android.graphics.Bitmap
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Thread dédié à l'émulation, comme dans RavenEmu 0.8.1.
 *
 * La boucle du lecteur tournait sur le thread principal et confiait chaque
 * trame au pool partagé `Dispatchers.Default`, en priorité normale : Android
 * pouvait alors la placer sur un cœur économe, et chaque trame attendait que
 * le thread de l'interface se libère. Un jeu lourd, comme un jeu GBC en
 * double vitesse, passait sous la cadence de la console et le son se coupait.
 *
 * Ce thread unique porte toute la boucle (émulation, son, image et cadence)
 * avec la priorité d'affichage urgent qu'utilisait la version 0.8.1 de RavenEmu : l'ordonnanceur le
 * garde sur les cœurs rapides.
 */
internal val gbEmulationDispatcher: ExecutorCoroutineDispatcher by lazy {
    // Un seul thread pour tout le processus : il reste en attente, sans
    // consommer, quand aucun jeu ne tourne, et survit aux recréations de
    // l'activité.
    Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            task.run()
        }, "RavenEmu-Emulation").apply { isDaemon = true }
    }.asCoroutineDispatcher()
}

/**
 * Images du lecteur, réutilisées en rotation au lieu d'une Bitmap par trame.
 *
 * Une image publiée reste intacte pendant les [count] - 1 trames suivantes,
 * le temps que Compose la dessine : le lecteur n'écrit jamais dans celle qui
 * vient d'être affichée. Sans allocation par trame, le ramasse-miettes ne
 * vient plus interrompre la boucle.
 */
internal class GbFrameBitmaps(
    private val width: Int,
    private val height: Int,
    count: Int = 3,
) {
    init {
        require(width > 0 && height > 0) { "Dimensions d'image invalides." }
        require(count >= 3) { "Au moins trois images en rotation." }
    }

    private val bitmaps = List(count) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private val images: List<ImageBitmap> = bitmaps.map { it.asImageBitmap() }
    private var next = 0

    /** Copie [pixels] (ARGB) dans l'image suivante et la rend, prête à afficher. */
    fun publish(pixels: IntArray): ImageBitmap {
        val index = next
        next = (next + 1) % bitmaps.size
        bitmaps[index].setPixels(pixels, 0, width, 0, 0, width, height)
        return images[index]
    }
}
