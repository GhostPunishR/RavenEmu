package com.ravenemu.android.engine

import java.util.concurrent.atomic.AtomicInteger

/**
 * Boutons voulus par l'interface, un bit par bouton, que le fil d'émulation
 * applique avant chaque image.
 *
 * Un appui plus bref qu'une image reste vu pendant une image : relâché avant
 * le début de l'image suivante, il n'aurait sinon jamais compté pour le jeu,
 * qui ne lit ses boutons que pendant une image.
 */
internal class GbButtonLatch {
    private val held = AtomicInteger(0)
    // Appuyés depuis la dernière image, même déjà relâchés.
    private val pressedSinceFrame = AtomicInteger(0)

    /** Fil de l'interface. */
    fun set(bit: Int, pressed: Boolean) {
        if (pressed) {
            held.updateAndGet { it or bit }
            pressedSinceFrame.updateAndGet { it or bit }
        } else {
            held.updateAndGet { it and bit.inv() }
        }
    }

    /** Pause, sortie du lecteur, nouvelle partie : plus rien d'enfoncé, ni d'appui en attente. */
    fun releaseAll() {
        held.set(0)
        pressedSinceFrame.set(0)
    }

    /** Les boutons de l'image qui commence : tenus, ou appuyés depuis la précédente. */
    fun takeFrame(): Int = held.get() or pressedSinceFrame.getAndSet(0)

    /** Les mêmes, sans consommer les appuis brefs (état instantané). */
    fun current(): Int = held.get() or pressedSinceFrame.get()
}
