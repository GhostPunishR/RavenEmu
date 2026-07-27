package com.ravenemu.romlibrary

import com.ravenemu.core.gb.GameBoyConsoleProvider
import com.ravenemu.core.gb.cartridge.CartridgeHeader
import com.ravenemu.emulation.api.ConsoleProvider
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.RomLoadException

/** Résultat d'analyse : une entrée valide ou un rejet motivé. */
sealed class AnalysisResult {
    data class Success(val entry: RomEntry) : AnalysisResult()
    data class Invalid(val reason: String) : AnalysisResult()
}

/**
 * Analyseur de ROM d'une console. Chaque console future fournit le sien ;
 * la bibliothèque les interroge via [canAnalyze] puis [analyze].
 *
 * Ce que l'analyseur sait de la console — extensions reconnues, taille
 * maximale acceptée — vient du [ConsoleProvider] publié par le module de
 * moteur, et non d'une seconde déclaration tenue à jour en parallèle.
 */
interface RomAnalyzer {

    /** Console servie, telle que déclarée par son module de moteur. */
    val provider: ConsoleProvider

    val console: ConsoleType get() = provider.console

    /** Taille maximale acceptée par le moteur de cette console. */
    val maxRomSizeBytes: Int get() = provider.maxRomSizeBytes

    fun canAnalyze(fileName: String): Boolean = provider.handles(fileName)

    /**
     * Analyse le contenu complet d'un fichier ROM : validation de taille et
     * de format, extraction d'en-tête, calcul des empreintes, et état déduit
     * des sommes de contrôle que porte la cartouche.
     */
    fun analyze(
        uri: String,
        fileName: String,
        lastModified: Long,
        data: ByteArray,
    ): AnalysisResult
}

/** Analyseur Game Boy : fichiers `.gb`, en-tête de cartouche DMG. */
class GameBoyRomAnalyzer : RomAnalyzer {

    override val provider: ConsoleProvider = GameBoyConsoleProvider()

    override fun analyze(
        uri: String,
        fileName: String,
        lastModified: Long,
        data: ByteArray,
    ): AnalysisResult {
        val header = try {
            CartridgeHeader.parse(data)
        } catch (e: RomLoadException) {
            return AnalysisResult.Invalid(e.message ?: "ROM invalide")
        }
        val fingerprints = Fingerprints.of(data)
        return AnalysisResult.Success(
            RomEntry(
                uri = uri,
                fileName = fileName,
                sizeBytes = data.size.toLong(),
                lastModified = lastModified,
                console = console,
                title = header.title,
                cartridgeTypeCode = header.cartridgeTypeCode,
                mbcType = header.mbcType,
                hasBattery = header.hasBattery,
                hasRtc = header.hasRtc,
                romSizeBytes = header.romSizeBytes,
                ramSizeBytes = header.ramSizeBytes,
                region = header.region,
                cartridgeMode = header.cartridgeMode,
                headerChecksumValid = header.headerChecksumValid,
                fingerprints = fingerprints,
                status = header.romStatus(),
            )
        )
    }
}
