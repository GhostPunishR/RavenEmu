package com.ravenemu.shared

/** An installed Delta skin, as the settings screen lists it. */
data class DeltaSkinEntry(
    val id: String,
    val name: String,
    /** "Game Boy / Game Boy Color" or "Game Boy Advance". */
    val console: String,
    /** In use for its console instead of the classic controls. */
    val selected: Boolean,
)
