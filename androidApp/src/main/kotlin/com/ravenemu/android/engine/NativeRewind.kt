package com.ravenemu.android.engine

/**
 * Retour arrière : les états des dernières images d'un jeu, gardés dans la
 * mémoire native en différences, quelques Ko par image pour un état GBA de
 * 640 Ko. Au-delà de [budgetBytes], les plus anciennes partent d'abord.
 *
 * Appelé sous le verrou de la session, comme [NativeCore].
 */
class NativeRewind(budgetBytes: Long) : AutoCloseable {
    private var pointer: Long = nativeCreate(budgetBytes).also {
        check(it != 0L) { "Retour arrière indisponible." }
    }

    /** Garde l'état de [core] après une image ; faux si la mémoire manque. */
    fun keep(core: NativeCore): Boolean = nativeKeep(requireOpen(), core.nativeHandle)

    /** Remet [core] une image en arrière ; faux quand plus rien n'est gardé. */
    fun stepBack(core: NativeCore): Boolean = nativeStepBack(requireOpen(), core.nativeHandle)

    fun clear() = nativeClear(requireOpen())

    /** Images vers lesquelles revenir. */
    val frames: Int get() = nativeFrames(requireOpen())

    private fun requireOpen(): Long = pointer.also {
        check(it != 0L) { "Retour arrière fermé." }
    }

    override fun close() {
        if (pointer != 0L) {
            nativeDestroy(pointer)
            pointer = 0L
        }
    }

    private external fun nativeCreate(budget: Long): Long
    private external fun nativeDestroy(pointer: Long)
    private external fun nativeKeep(pointer: Long, core: Long): Boolean
    private external fun nativeStepBack(pointer: Long, core: Long): Boolean
    private external fun nativeClear(pointer: Long)
    private external fun nativeFrames(pointer: Long): Int

    companion object {
        /** 32 Mo : environ deux minutes de jeu GBA, plus en GB et GBC. */
        const val BUDGET_BYTES = 32L shl 20

        init { System.loadLibrary("ravenemu_android") }
    }
}
