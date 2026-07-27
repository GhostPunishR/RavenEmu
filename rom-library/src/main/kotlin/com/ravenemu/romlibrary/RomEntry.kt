package com.ravenemu.romlibrary

import com.ravenemu.core.gb.cartridge.GameBoyCartridgeMode
import com.ravenemu.core.gb.cartridge.MbcType
import com.ravenemu.core.gb.cartridge.RomRegion
import com.ravenemu.emulation.api.ConsoleType
import kotlinx.serialization.Serializable

/**
 * Entrée de la bibliothèque : métadonnées d'une ROM indexée. L'URI référence
 * le document choisi par l'utilisateur (SAF) ; aucune donnée de ROM n'est
 * stockée dans l'index.
 *
 * **Couplage connu.** Ce modèle nomme encore des types propres à une console
 * ([MbcType], [RomRegion], [GameBoyCartridgeMode]) : ajouter une console y
 * ajouterait ses propres champs, et `rom-library` continue pour cette raison de
 * dépendre des modules de moteur. Les rendre neutres suppose de changer le
 * format de l'index et la manière dont l'application affiche le détail d'une
 * ROM ; c'est une étape à part entière, volontairement séparée de
 * l'introduction du contrat `ConsoleProvider`.
 */
@Serializable
data class RomEntry(
    /** URI du document (SAF) ou chemin, identifiant unique dans l'index. */
    val uri: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    @Serializable(with = ConsoleTypeSerializer::class)
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
    /**
     * Compatibilité déclarée par la cartouche Game Boy, lue dans l'en-tête.
     *
     * `null` a deux sens, distingués par [console] : la console n'a pas cette
     * notion (Game Boy Advance), ou l'entrée provient d'un index antérieur à
     * ce champ et doit être ré-analysée. L'ancien index ne portait qu'un
     * booléen `supportsCgb`, incapable de distinguer une cartouche compatible
     * couleur d'une cartouche qui l'exige : la valeur ne peut pas être déduite,
     * elle doit être relue.
     */
    val cartridgeMode: GameBoyCartridgeMode? = null,
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

    /** `true` si la cartouche annonce des fonctions Game Boy Color. */
    val supportsCgb: Boolean
        get() = cartridgeMode?.usesColor == true

    /**
     * `true` si l'entrée doit être relue avant d'être fiable. Le cas se présente
     * pour une entrée Game Boy issue d'un index antérieur au champ
     * [cartridgeMode] : l'information n'existe pas et ne se déduit pas.
     */
    val needsReanalysis: Boolean
        get() = console == ConsoleType.GAME_BOY && cartridgeMode == null

    /**
     * Console et compatibilité de cartouche en une ligne, pour l'affichage :
     * « Game Boy Color » pour une cartouche couleur, « Game Boy » sinon.
     */
    val platformLabel: String
        get() = cartridgeMode?.displayName ?: console.displayName
}
