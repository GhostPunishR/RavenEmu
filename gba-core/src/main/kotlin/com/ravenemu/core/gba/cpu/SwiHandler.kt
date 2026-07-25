package com.ravenemu.core.gba.cpu

/**
 * Traite un appel logiciel `SWI` en haut niveau (émulation du BIOS). Implémenté
 * par le BIOS HLE de RavenEmu ; branché sur [Arm7Tdmi.swiHandler].
 */
fun interface SwiHandler {
    /** Exécute la fonction BIOS numéro [number] (registres via le CPU). */
    fun handleSwi(number: Int)
}
