package com.ravenemu.emulation.api.timing

/**
 * Saute au plus une composition vidéo après une trame qui dépasse son budget.
 *
 * L'émulation matérielle continue pendant le saut. Seule la fabrication du
 * framebuffer est omise, ce qui rend du temps au CPU et à l'audio sans modifier
 * les timers, les interruptions ou le nombre de trames émulées.
 */
class AdaptiveFrameSkipper(
    private val frameBudgetNanos: Long,
) {
    init {
        require(frameBudgetNanos > 0L) { "Budget de trame invalide" }
    }

    private var skipNext = false

    fun shouldRender(): Boolean = !skipNext

    /**
     * Enregistre le coût de la trame terminée. Une trame rendue trop lente
     * provoque un seul saut. Une trame déjà sautée réactive toujours le rendu.
     */
    fun onFrameCompleted(rendered: Boolean, workNanos: Long) {
        skipNext = rendered && workNanos.coerceAtLeast(0L) > frameBudgetNanos
    }

    fun reset() {
        skipNext = false
    }
}
