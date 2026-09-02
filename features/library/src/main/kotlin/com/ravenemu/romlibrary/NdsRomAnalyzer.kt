package com.ravenemu.romlibrary

import com.ravenemu.core.nds.cartridge.NdsHeader
import com.ravenemu.emulation.api.ConsoleProvider
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.RomLoadException

/**
 * Analyseur Nintendo DS : fichiers `.nds`, en-tête de cartouche (512 octets).
 *
 * Comme les deux autres, il reçoit son fournisseur de console au lieu de le
 * construire. Une image dont l'en-tête ne décrit pas une cartouche est rejetée
 * avec la raison lue : trop courte, code unité inconnu, cartouche exclusivement
 * DSi, ou blocs de code processeur qui sortent du fichier.
 */
class NdsRomAnalyzer(override val provider: ConsoleProvider) : RomAnalyzer {

    init {
        // Même précaution que pour les deux autres analyseurs : le type du
        // paramètre n'exclut pas qu'on lui remette le fournisseur d'une autre
        // console, ce qui produirait un index faux plutôt qu'une erreur.
        require(provider.console == ConsoleType.NINTENDO_DS) {
            "NdsRomAnalyzer requiert un fournisseur ${ConsoleType.NINTENDO_DS}, " +
                "reçu ${provider.console}"
        }
    }

    override fun analyze(
        uri: String,
        fileName: String,
        lastModified: Long,
        data: ByteArray,
    ): AnalysisResult {
        val header = try {
            NdsHeader.parse(data)
        } catch (e: RomLoadException) {
            return AnalysisResult.Invalid(e.message ?: "ROM Nintendo DS invalide")
        }
        return AnalysisResult.Success(
            RomEntry(
                uri = uri,
                fileName = fileName,
                sizeBytes = data.size.toLong(),
                lastModified = lastModified,
                console = console,
                title = header.title,
                fingerprints = Fingerprints.of(data),
                status = header.romStatus(),
                gameCode = header.gameCode,
                romSizeBytes = data.size,
                headerChecksumValid = header.headerChecksumValid,
            )
        )
    }
}
