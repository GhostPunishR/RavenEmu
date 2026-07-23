package com.ravenemu.romlibrary

import com.ravenemu.core.gb.cartridge.CartridgeHeader
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
 */
interface RomAnalyzer {
    val console: ConsoleType

    /** Taille maximale acceptée par le moteur de cette console. */
    val maxRomSizeBytes: Int

    fun canAnalyze(fileName: String): Boolean

    /**
     * Analyse le contenu complet d'un fichier ROM : validation de taille et
     * de format, extraction d'en-tête, calcul des empreintes, classification
     * contre la base de références.
     */
    fun analyze(
        uri: String,
        fileName: String,
        lastModified: Long,
        data: ByteArray,
    ): AnalysisResult
}

/** Analyseur Game Boy : fichiers `.gb`, en-tête de cartouche DMG. */
class GameBoyRomAnalyzer(
    private val references: ReferenceDatabase = ReferenceDatabase.empty(),
) : RomAnalyzer {

    override val console: ConsoleType = ConsoleType.GAME_BOY
    override val maxRomSizeBytes: Int = CartridgeHeader.MAX_ROM_SIZE

    override fun canAnalyze(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in console.romExtensions

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
                supportsCgb = header.supportsCgb,
                headerChecksumValid = header.headerChecksumValid,
                fingerprints = fingerprints,
                status = references.classify(fingerprints, header.title),
            )
        )
    }
}
