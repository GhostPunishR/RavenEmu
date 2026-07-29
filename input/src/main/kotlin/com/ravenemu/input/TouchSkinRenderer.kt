package com.ravenemu.input

import android.graphics.Canvas

/** Skins disponibles pour la superposition tactile. */
enum class TouchSkin {
    /** Rendu historique, conservé notamment pour les profils paysage. */
    CLASSIC,

    /** Portrait RavenEmu pour le cœur GB/GBC. */
    RAVEN_GB,

    /** Portrait RavenEmu pour le cœur GBA. */
    RAVEN_GBA,
}

/**
 * Contrat de rendu uniquement : ni hit-testing ni envoi d'entrée.
 *
 * Une instance est conservée par la vue et réutilise ses objets de dessin.
 */
internal interface TouchSkinRenderer {
    fun onViewportChanged(
        width: Int,
        height: Int,
        density: Float,
        screenTopInsetPx: Int,
    )

    fun draw(
        canvas: Canvas,
        layout: ControlLayout,
        editMode: Boolean,
        selectedElement: ControlId?,
        animationState: ControlAnimationState,
        drawBackground: Boolean,
    )
}
