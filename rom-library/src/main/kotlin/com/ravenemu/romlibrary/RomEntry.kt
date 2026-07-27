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
    val fingerprints: Fingerprints,
    /**
     * État déduit des sommes de contrôle de la cartouche. Ce n'est pas un
     * classement mais une mesure : il ne se force pas.
     */
    val status: RomStatus,
    // Champs propres à la cartouche Game Boy : valeurs neutres par défaut pour
    // les consoles dont l'en-tête ne les définit pas (ex. Game Boy Advance).
    val cartridgeTypeCode: Int = 0,
    val mbcType: MbcType = MbcType.NONE,
    val hasBattery: Boolean = false,
    val hasRtc: Boolean = false,
    val romSizeBytes: Int = 0,
    val ramSizeBytes: Int = 0,
    val region: RomRegion = RomRegion.UNKNOWN,
    val supportsCgb: Boolean = false,
    val headerChecksumValid: Boolean = false,
    /** Code jeu (Game Boy Advance : 4 caractères de l'en-tête), sinon vide. */
    val gameCode: String = "",
    /**
     * Type de mémoire de sauvegarde détecté dans la ROM (Game Boy Advance),
     * sous forme du nom d'énumération `GbaSaveType` ; vide si non applicable.
     */
    val saveType: String = "",
    /** URI d'une pochette associée manuellement ou détectée localement. */
    val coverUri: String? = null,
) {
    /** Nom affiché : titre d'en-tête ou nom de fichier sans extension. */
    val displayName: String
        get() = title.ifBlank { fileName.substringBeforeLast('.') }
}
