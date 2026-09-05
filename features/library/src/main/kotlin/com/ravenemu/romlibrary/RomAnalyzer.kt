package com.ravenemu.romlibrary

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

/**
 * Analyseur Game Boy : fichiers `.gb`, en-tête de cartouche DMG.
 *
 * Le fournisseur de console lui est **remis**, il ne le construit plus. Ce
 * module n'a donc plus à nommer le fournisseur Game Boy concret : c'est la
 * racine de composition, seul endroit qui connaît les modules de moteur, qui
 * décide lequel servir. Les extensions reconnues et la taille maximale
 * acceptée viennent ainsi du contrat publié par le module de moteur, et non
 * d'une seconde déclaration tenue à jour en parallèle. L'analyseur et le
 * moteur ne partagent pas pour autant la même instance de fournisseur : c'est
 * la règle qui est commune, pas l'objet.
 */
class GameBoyRomAnalyzer(override val provider: ConsoleProvider) : RomAnalyzer {

    init {
        // Le fournisseur est injecté depuis la racine de composition : rien
        // dans le type ne dit qu'il s'agit bien du fournisseur Game Boy. Une
        // interversion donnerait un analyseur qui accepte les mauvaises
        // extensions et écrit une mauvaise console dans l'index — un défaut
        // qui ne se verrait qu'à l'usage, sur la bibliothèque de l'utilisateur.
        require(provider.console == ConsoleType.GAME_BOY) {
            "GameBoyRomAnalyzer requiert un fournisseur ${ConsoleType.GAME_BOY}, " +
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
                fingerprints = fingerprints,
            )
        )
    }
}
