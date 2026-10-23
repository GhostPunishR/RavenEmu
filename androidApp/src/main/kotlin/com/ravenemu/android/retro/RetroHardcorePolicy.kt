package com.ravenemu.android.retro

/** Hardcore needs RetroAchievements' approval, and forbids loading a save state. */
internal object RetroHardcorePolicy {
    fun enabled(requested: Boolean, approved: Boolean = HardcoreAvailability.APPROVED) = requested && approved
    fun requireStateLoadAllowed(active: Boolean) {
        check(!active) { "Mode Hardcore : le chargement d'un état est désactivé." }
    }
}
