package com.ravenemu.shared.saves

/** Confirmation information for an imported GB save, without its file bytes. */
data class GbSaveImportReview(
    val gameTitle: String,
    val hasBattery: Boolean,
    val slots: List<Int>, // one-based, e.g. [1, 3]
    val conflicts: List<String>,
    val rawSav: Boolean,
)

/**
 * File type of an exported game save. Both hold the same bytes, the
 * cartridge memory: only the name tells other emulators what to load.
 */
enum class BatteryFileFormat(val extension: String, val label: String) {
    SAV(".sav", "Fichier .sav (la plupart des émulateurs)"),
    SRM(".srm", "Fichier .srm (RetroArch)"),
}
