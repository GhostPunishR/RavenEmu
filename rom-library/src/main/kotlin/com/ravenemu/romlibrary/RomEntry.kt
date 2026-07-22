package com.ravenemu.romlibrary

import com.ravenemu.core.gb.cartridge.MbcType
import com.ravenemu.core.gb.cartridge.RomRegion
import com.ravenemu.emulation.api.ConsoleType
import kotlinx.serialization.Serializable

/**
 * Entrée de la bibliothèque : métadonnées d'une ROM indexée. L'URI référence
 * le document choisi par l'utilisateur (SAF) ; aucune donnée de ROM n'est
 * stockée dans l'index.
 */
@Serializable
data class RomEntry(
    /** URI du document (SAF) ou chemin, identifiant unique dans l'index. */
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val console: ConsoleType,
    /** Titre lu dans l'en-tête de cartouche (peut être vide). */
    val title: String,
    val cartridgeTypeCode: Int,
    val mbcType: MbcType,
    val hasBattery: Boolean,
    val hasRtc: Boolean,
    val romSizeBytes: Int,
    val ramSizeBytes: Int,
    val region: RomRegion,
    val supportsCgb: Boolean,
    val headerChecksumValid: Boolean,
    val fingerprints: Fingerprints,
    val status: RomStatus,
    /** Statut forcé par l'utilisateur (ex. Homebrew déclaré), prioritaire. */
    val userStatusOverride: RomStatus? = null,
    /** URI d'une pochette associée manuellement ou détectée localement. */
    val coverUri: String? = null,
) {
    /** Statut effectif affiché : le forçage utilisateur prime. */
    val effectiveStatus: RomStatus get() = userStatusOverride ?: status

    /** Nom affiché : titre d'en-tête ou nom de fichier sans extension. */
    val displayName: String
        get() = title.ifBlank { fileName.substringBeforeLast('.') }
}
