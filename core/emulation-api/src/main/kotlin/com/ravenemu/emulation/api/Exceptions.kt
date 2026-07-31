package com.ravenemu.emulation.api

/** Erreur générique remontée par un moteur d'émulation. */
open class EmulationException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** ROM invalide, corrompue ou non prise en charge par le moteur. */
class RomLoadException(message: String, cause: Throwable? = null) :
    EmulationException(message, cause)

/** État instantané illisible, d'une version inconnue ou d'une autre ROM. */
class SaveStateException(message: String, cause: Throwable? = null) :
    EmulationException(message, cause)
