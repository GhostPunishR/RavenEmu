package com.ravenemu.core.gba.memory

/**
 * Temps d'attente d'une région mémoire, exprimés en **cycles supplémentaires**
 * au-delà du cycle de base que coûte tout accès.
 *
 * Le matériel distingue deux natures d'accès :
 * - [nonSequential] (`N`) : première adresse d'une rafale, ou adresse sans
 *   rapport avec l'accès précédent (saut, changement de région) ;
 * - [sequential] (`S`) : adresse immédiatement consécutive à l'accès précédent
 *   dans la même région, que le contrôleur sert plus vite.
 *
 * Exemples : l'IWRAM répond en un cycle dans les deux cas (`0, 0`) ; l'EWRAM
 * ajoute deux cycles par accès 16 bits (`2, 2`) ; la ROM cartouche dépend de
 * `WAITCNT` et distingue réellement les deux natures.
 */
data class MemoryAccessTiming(val sequential: Int, val nonSequential: Int) {

    /** Temps d'attente correspondant à la nature de l'accès. */
    fun waitStates(sequential: Boolean): Int =
        if (sequential) this.sequential else nonSequential

    companion object {
        /** Région servie par le bus interne 32 bits : aucun temps d'attente. */
        val NONE = MemoryAccessTiming(0, 0)
    }
}
