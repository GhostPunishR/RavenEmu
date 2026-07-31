package com.ravenemu.romlibrary

import com.ravenemu.core.gba.cartridge.GbaHeader
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.emulation.api.ConsoleProvider
import com.ravenemu.emulation.api.RomLoadException

/**
 * Analyseur Game Boy Advance : fichiers `.gba`, en-tête GBA (192 octets).
 *
 * Comme l'analyseur Game Boy, il reçoit son fournisseur de console au lieu de
 * le construire, calcule les empreintes et déduit l'état de la ROM de son seul
 * en-tête. Une ROM dépourvue du marqueur `0x96` est rejetée :
 * ce n'est pas une cartouche Game Boy Advance.
 */
class GbaRomAnalyzer(override val provider: ConsoleProvider) : RomAnalyzer {

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
                status = header.romStatus(),
                gameCode = header.gameCode,
                saveType = GbaSaveType.detect(data).name,
                romSizeBytes = data.size,
                headerChecksumValid = header.headerChecksumValid,
            )
        )
    }
}
