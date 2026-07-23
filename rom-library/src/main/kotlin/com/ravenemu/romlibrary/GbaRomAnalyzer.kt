package com.ravenemu.romlibrary

import com.ravenemu.core.gba.cartridge.GbaCartridge
import com.ravenemu.core.gba.cartridge.GbaHeader
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.RomLoadException

/**
 * Analyseur Game Boy Advance : fichiers `.gba`, en-tête GBA (192 octets).
 *
 * Comme l'analyseur Game Boy, il calcule les empreintes et classe la ROM contre
 * la base de références (métadonnées uniquement). Une ROM dépourvue du marqueur
 * `0x96` est rejetée : ce n'est pas une cartouche Game Boy Advance.
 */
class GbaRomAnalyzer(
    private val references: ReferenceDatabase = ReferenceDatabase.empty(),
) : RomAnalyzer {

    override val console: ConsoleType = ConsoleType.GAME_BOY_ADVANCE
    override val maxRomSizeBytes: Int = GbaCartridge.MAX_ROM_SIZE

    override fun canAnalyze(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in console.romExtensions

    override fun analyze(
        uri: String,
        fileName: String,
        lastModified: Long,
        data: ByteArray,
    ): AnalysisResult {
        val header = try {
            GbaHeader.parse(data)
        } catch (e: RomLoadException) {
            return AnalysisResult.Invalid(e.message ?: "ROM GBA invalide")
        }
        if (!header.fixedMarkerValid) {
            return AnalysisResult.Invalid("Marqueur Game Boy Advance (0x96) absent")
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
                fingerprints = fingerprints,
                status = references.classify(fingerprints, header.title),
                gameCode = header.gameCode,
                romSizeBytes = data.size,
                headerChecksumValid = header.headerChecksumValid,
            )
        )
    }
}
