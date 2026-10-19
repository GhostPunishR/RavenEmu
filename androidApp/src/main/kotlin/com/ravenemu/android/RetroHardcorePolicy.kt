package com.ravenemu.android

/** Keep the product/version stable and numeric for RetroAchievements validation. */
internal object RetroHardcorePolicy {
    const val USER_AGENT = "RavenEmu/0.1.0-beta (Android) rcheevos/12.5.0"
    fun enabled(requested: Boolean, approved: Boolean = HardcoreAvailability.APPROVED) = requested && approved
    fun requireStateLoadAllowed(active: Boolean) {
        check(!active) { "Mode Hardcore : le chargement d'un état est désactivé." }
    }
}
