package com.ravenemu.romlibrary

import com.ravenemu.core.gb.GameBoyConsoleProvider
import com.ravenemu.core.nds.NdsConsoleProvider
import com.ravenemu.core.nds.cartridge.NdsHeader
import com.ravenemu.emulation.api.ConsoleType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NdsRomAnalyzerTest {

    private val analyzer = NdsRomAnalyzer(NdsConsoleProvider())

    /**
     * Cartouche Nintendo DS de synthèse.
     *
     * Aucune ROM n'est embarquée dans ce dépôt : ce qui est éprouvé est la
     * lecture d'un en-tête, et un en-tête se fabrique. Le constructeur est
     * refait ici plutôt qu'emprunté aux tests du moteur : les jeux de tests de
     * deux modules ne se voient pas, et c'est aussi ce que fait déjà la
     * vérification de l'analyseur Game Boy Advance.
     */
    private fun ndsRom(
        title: String = "RAVENEMU",
        gameCode: String = "ARVE",
        unitCode: Int = 0x00,
        arm9Size: Int = 0x400,
        sizeBytes: Int = 0x8000,
        validChecksum: Boolean = true,
    ): ByteArray {
        val rom = ByteArray(sizeBytes)
        fun text(value: String, offset: Int, length: Int) {
            value.take(length).forEachIndexed { i, c -> rom[offset + i] = c.code.toByte() }
        }
        fun u32(offset: Int, value: Int) {
            for (b in 0 until 4) rom[offset + b] = ((value ushr (b * 8)) and 0xFF).toByte()
        }
        text(title, 0x000, 12)
        text(gameCode, 0x00C, 4)
        text("01", 0x010, 2)
        rom[0x012] = unitCode.toByte()
        u32(0x020, 0x4000)
        u32(0x02C, arm9Size)
        u32(0x030, 0x6000)
        u32(0x03C, 0x400)
        u32(0x080, sizeBytes)
        u32(0x084, NdsHeader.HEADER_SIZE)
        val crc = NdsHeader.crc16(rom, NdsHeader.CRC_COVERED_BYTES)
        val written = if (validChecksum) crc else crc xor 0xFFFF
        rom[NdsHeader.HEADER_CRC_OFFSET] = (written and 0xFF).toByte()
        rom[NdsHeader.HEADER_CRC_OFFSET + 1] = ((written ushr 8) and 0xFF).toByte()
        return rom
    }

    /**
     * Les deux chemins décrivent la même cartouche.
     *
     * Celui qui a l'image entière et celui qui n'a que sa tête doivent rendre
     * exactement la même entrée : la bibliothèque range les jeux d'après ces
     * valeurs, et deux descriptions distinctes feraient apparaître une même
     * cartouche différemment selon la façon dont elle a été lue.
     */
    @Test
    fun `l'analyse par l'en-tete decrit la meme cartouche que l'image entiere`() {
        val image = ndsRom()
        val parImage = assertIs<AnalysisResult.Success>(
            analyzer.analyze("u", "jeu.nds", 7L, image)
        ).entry
        val parEnTete = assertIs<AnalysisResult.Success>(
            analyzer.analyzeHeader(
                uri = "u",
                fileName = "jeu.nds",
                lastModified = 7L,
                header = image.copyOf(analyzer.headerBytes),
                sizeBytes = image.size.toLong(),
                fingerprints = Fingerprints.of(image),
            )
        ).entry
        assertEquals(parImage, parEnTete)
    }

    /**
     * Une cartouche plus grosse que la mémoire disponible s'indexe quand même.
     *
     * C'est tout l'objet du chemin par l'en-tête : les blocs de code d'une
     * cartouche commencent bien après sa tête, et les contrôler contre la
     * longueur du tampon reçu plutôt que contre celle du fichier ferait refuser
     * toute cartouche lue de cette façon.
     */
    @Test
    fun `une cartouche de deux cent cinquante-six Mio s'indexe par son en-tete`() {
        val taille = 256L * 1024L * 1024L
        val entree = assertIs<AnalysisResult.Success>(
            analyzer.analyzeHeader(
                uri = "u",
                fileName = "grosse.nds",
                lastModified = 0L,
                header = ndsRom(sizeBytes = 0x8000).copyOf(analyzer.headerBytes),
                sizeBytes = taille,
                fingerprints = Fingerprints.of(ByteArray(4)),
            )
        ).entry
        assertEquals(taille, entree.sizeBytes)
        assertEquals("RAVENEMU", entree.title)
        assertTrue(taille <= analyzer.maxIndexableBytes, "et elle tient sous le plafond d'index")
        assertTrue(
            taille > analyzer.maxRomSizeBytes.toLong(),
            "tout en dépassant ce que le moteur sait charger",
        )
    }

    /** L'en-tête reste contrôlé : un bloc hors du fichier est toujours refusé. */
    @Test
    fun `l'analyse par l'en-tete refuse encore un bloc hors du fichier`() {
        val refus = assertIs<AnalysisResult.Invalid>(
            analyzer.analyzeHeader(
                uri = "u",
                fileName = "jeu.nds",
                lastModified = 0L,
                header = ndsRom().copyOf(analyzer.headerBytes),
                // Le bloc ARM9 commence à 0x4000 : un fichier plus court ne peut
                // pas le contenir.
                sizeBytes = 0x1000L,
                fingerprints = Fingerprints.of(ByteArray(4)),
            )
        )
        assertTrue("ARM9" in refus.reason, refus.reason)
    }

    @Test
    fun `reconnait l'extension nds`() {
        assertTrue(analyzer.canAnalyze("jeu.nds"))
        assertTrue(analyzer.canAnalyze("JEU.NDS"))
        assertFalse(analyzer.canAnalyze("jeu.gba"))
        assertFalse(analyzer.canAnalyze("jeu.gb"))
        assertEquals(NdsConsoleProvider.MAX_ROM_SIZE, analyzer.maxRomSizeBytes)
    }

    @Test
    fun `analyse une ROM Nintendo DS valide`() {
        val result = analyzer.analyze(
            "content://jeu.nds",
            "jeu.nds",
            0L,
            ndsRom(title = "RAVENEMU", gameCode = "ARVE"),
        )
        val entry = assertIs<AnalysisResult.Success>(result).entry
        assertEquals(ConsoleType.NINTENDO_DS, entry.console)
        assertEquals("RAVENEMU", entry.title)
        assertEquals("ARVE", entry.gameCode)
        assertTrue(entry.headerChecksumValid)
        // Les champs propres à la cartouche Game Boy gardent leurs valeurs
        // neutres : l'index les porte pour toutes les consoles, la Nintendo DS
        // n'en définit aucun.
        assertEquals(0, entry.cartridgeTypeCode)
        assertEquals("", entry.saveType)
        assertFalse(entry.supportsCgb)
        assertFalse(entry.needsReanalysis)
    }

    @Test
    fun `une ROM Nintendo DS valide n'engage que son en-tete`() {
        // La somme de seize bits ne couvre que l'en-tête : rien ne permet
        // d'affirmer que le contenu est intact.
        val result = analyzer.analyze("u", "jeu.nds", 0L, ndsRom())
        assertEquals(RomStatus.HEADER_ONLY, assertIs<AnalysisResult.Success>(result).entry.status)
    }

    @Test
    fun `une somme d'en-tete fausse est signalee sans refuser la ROM`() {
        val result = analyzer.analyze("u", "jeu.nds", 0L, ndsRom(validChecksum = false))
        val entry = assertIs<AnalysisResult.Success>(result).entry
        assertEquals(RomStatus.INVALID_HEADER, entry.status)
        assertFalse(entry.headerChecksumValid)
    }

    @Test
    fun `une image inexploitable est rejetee avec sa raison`() {
        for (image in listOf(
            ByteArray(0x100),
            ndsRom(unitCode = 0x03),
            ndsRom(arm9Size = 0),
        )) {
            val rejet = assertIs<AnalysisResult.Invalid>(
                analyzer.analyze("u", "jeu.nds", 0L, image)
            )
            assertTrue(rejet.reason.isNotBlank())
        }
    }

    @Test
    fun `l'analyseur refuse le fournisseur d'une autre console`() {
        val erreur = assertFailsWith<IllegalArgumentException> {
            NdsRomAnalyzer(GameBoyConsoleProvider())
        }
        assertTrue(ConsoleType.GAME_BOY.name in (erreur.message ?: ""), erreur.message ?: "")
    }

    @Test
    fun `les empreintes portent sur le fichier entier`() {
        // Deux cartouches dont seuls des octets hors en-tête diffèrent doivent
        // se distinguer : l'identité d'une entrée vient du fichier, pas du
        // titre lu dans son en-tête.
        val premiere = ndsRom()
        val seconde = premiere.copyOf().also { it[0x7000] = 0x42 }
        val a = assertIs<AnalysisResult.Success>(analyzer.analyze("a", "a.nds", 0L, premiere)).entry
        val b = assertIs<AnalysisResult.Success>(analyzer.analyze("b", "b.nds", 0L, seconde)).entry
        assertEquals(a.title, b.title)
        assertTrue(a.fingerprints.sha256 != b.fingerprints.sha256)
    }
}
