package com.ravenemu.shared

/** Confirmation information for an imported GB save, without its file bytes. */
data class GbSaveImportReview(
    val gameTitle: String,
    val hasBattery: Boolean,
    val slots: List<Int>, // one-based, e.g. [1, 3]
    val conflicts: List<String>,
    val rawSav: Boolean,
)
