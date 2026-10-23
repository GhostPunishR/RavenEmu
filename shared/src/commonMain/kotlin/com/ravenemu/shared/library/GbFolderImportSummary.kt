package com.ravenemu.shared.library

/** One completed Android SAF folder import; used only for UI presentation. */
data class GbFolderImportSummary(
    val added: Int,
    val duplicate: Int,
    val invalid: Int,
    val unreadable: Int,
    val otherFiles: Int,
    val scannedFolders: Int,
    val limited: Boolean,
)
