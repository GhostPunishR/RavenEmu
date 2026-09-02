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
    ): AnalysisResult = describe(
        uri = uri,
        fileName = fileName,
        lastModified = lastModified,
        header = data,
        sizeBytes = data.size.toLong(),
        fingerprints = Fingerprints.of(data),
    )

    /**
     * Tout ce que cet analyseur lit se trouve dans l'en-tête.
     *
     * Le reste de l'image ne lui sert qu'à calculer des empreintes et une
     * taille, que l'appelant sait obtenir au fil de sa lecture. Une cartouche
     * Nintendo DS pèse jusqu'à un demi-gigaoctet, et ranger son titre dans une
     * liste ne doit pas demander de la tenir en mémoire.
     */
    override val headerBytes: Int = NdsHeader.HEADER_SIZE

    /**
     * La bibliothèque indexe jusqu'à la plus grosse cartouche produite.
     *
     * Un demi-gigaoctet : ce n'est pas ce que la console adresse, qui va bien
     * au-delà, mais ce qui a réellement existé sur une cartouche. Ce plafond
     * n'a plus rien à voir avec la mémoire, l'analyse ne lisant que l'en-tête
     * et faisant défiler le reste ; il reste une borne de bon sens, et il borne
     * aussi la taille rapportée, qui tient sur trente-deux bits signés.
     *
     * **Le moteur, lui, s'arrête plus tôt.** Le charger demande de tenir
     * l'image en mémoire deux fois, et une cartouche que la bibliothèque montre
     * n'est donc pas forcément une cartouche qu'elle sait lancer. Le refus au
     * lancement est motivé, là où l'absence de la liste ne l'était pas.
     */
    override val maxIndexableBytes: Long = 512L * 1024L * 1024L

    override fun analyzeHeader(
        uri: String,
        fileName: String,
        lastModified: Long,
        header: ByteArray,
        sizeBytes: Long,
        fingerprints: Fingerprints,
    ): AnalysisResult = describe(uri, fileName, lastModified, header, sizeBytes, fingerprints)

    /**
     * Décrit une cartouche à partir de son en-tête et de ce que l'appelant a
     * mesuré.
     *
     * Les deux chemins passent par ici : celui qui a l'image entière et celui
     * qui n'a que sa tête décrivent la même cartouche, et deux descriptions
     * distinctes finiraient par diverger sur ce que la bibliothèque affiche.
     */
    private fun describe(
        uri: String,
        fileName: String,
        lastModified: Long,
        header: ByteArray,
        sizeBytes: Long,
        fingerprints: Fingerprints,
    ): AnalysisResult {
        val parsed = try {
            NdsHeader.parse(header, sizeBytes)
        } catch (e: RomLoadException) {
            return AnalysisResult.Invalid(e.message ?: "ROM Nintendo DS invalide")
        }
        return AnalysisResult.Success(
            RomEntry(
                uri = uri,
                fileName = fileName,
                sizeBytes = sizeBytes,
                lastModified = lastModified,
                console = console,
                title = parsed.title,
                fingerprints = fingerprints,
                status = parsed.romStatus(),
                gameCode = parsed.gameCode,
                romSizeBytes = sizeBytes.toInt(),
                headerChecksumValid = parsed.headerChecksumValid,
            )
        )
    }
}
