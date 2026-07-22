package com.ravenemu.romlibrary

import com.ravenemu.core.gb.cartridge.MbcType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROM synthétique minimale avec en-tête valide (aucun contenu tiers). */
private fun testRom(
    title: String = "RAVENTEST",
    type: Int = 0x00,
    fill: (ByteArray) -> Unit = {},
): ByteArray {
    val rom = ByteArray(0x8000)
    for ((i, c) in title.take(15).withIndex()) rom[0x0134 + i] = c.code.toByte()
    rom[0x0147] = type.toByte()
    rom[0x014A] = 0x01
    fill(rom)
    var chk = 0
    for (i in 0x0134..0x014C) chk = (chk - (rom[i].toInt() and 0xFF) - 1) and 0xFF
    rom[0x014D] = chk.toByte()
    return rom
}

class FingerprintsTest {

    @Test
    fun `vecteurs connus sur abc`() {
        val data = "abc".toByteArray()
        val fp = Fingerprints.of(data)
        assertEquals("352441C2", fp.crc32)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", fp.sha1)
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            fp.sha256,
        )
    }

    @Test
    fun `deterministes pour un meme contenu`() {
        val rom = testRom()
        assertEquals(Fingerprints.of(rom), Fingerprints.of(rom.copyOf()))
    }

    @Test
    fun `sensibles au moindre octet`() {
        val rom = testRom()
        val altered = rom.copyOf().also { it[0x2000] = 0x01 }
        val a = Fingerprints.of(rom)
        val b = Fingerprints.of(altered)
        assertFalse(a.sha256 == b.sha256)
        assertFalse(a.sha1 == b.sha1)
        assertFalse(a.crc32 == b.crc32)
    }
}

class ReferenceDatabaseTest {

    private val officialRom = testRom(title = "RAVENQUEST")
    private val officialFp = Fingerprints.of(officialRom)

    private val db = ReferenceDatabase(
        listOf(
            ReferenceEntry("RAVENQUEST", ReferenceKind.OFFICIAL, officialFp.sha256),
            ReferenceEntry("RAVENQUEST FR", ReferenceKind.HACK, "11".repeat(32)),
            ReferenceEntry("RAVENDEMO", ReferenceKind.HOMEBREW, "22".repeat(32)),
        )
    )

    @Test
    fun `empreinte officielle verifiee`() {
        assertEquals(
            RomStatus.VERIFIED_OFFICIAL,
            db.classify(officialFp, "RAVENQUEST"),
        )
    }

    @Test
    fun `titre connu mais empreinte differente = modifiee`() {
        val altered = officialRom.copyOf().also { it[0x3000] = 0x42 }
        assertEquals(
            RomStatus.MODIFIED_OR_UNRECOGNIZED,
            db.classify(Fingerprints.of(altered), "RAVENQUEST"),
        )
    }

    @Test
    fun `titre inconnu et empreinte inconnue = inconnue`() {
        val other = testRom(title = "AUTREJEU")
        assertEquals(
            RomStatus.UNKNOWN,
            db.classify(Fingerprints.of(other), "AUTREJEU"),
        )
    }

    @Test
    fun `jamais officielle sans correspondance d'empreinte`() {
        // Même titre, contenu différent : le statut ne peut être « officielle ».
        val fake = testRom(title = "RAVENQUEST") { it[0x4000] = 0x01 }
        val status = db.classify(Fingerprints.of(fake), "RAVENQUEST")
        assertFalse(status == RomStatus.VERIFIED_OFFICIAL)
    }

    @Test
    fun `base vide classe tout inconnu`() {
        val db = ReferenceDatabase.empty()
        assertEquals(RomStatus.UNKNOWN, db.classify(officialFp, "RAVENQUEST"))
    }
}

class GameBoyRomAnalyzerTest {

    private val analyzer = GameBoyRomAnalyzer()

    @Test
    fun `extension gb reconnue`() {
        assertTrue(analyzer.canAnalyze("jeu.gb"))
        assertTrue(analyzer.canAnalyze("JEU.GB"))
        assertFalse(analyzer.canAnalyze("jeu.gba"))
        assertFalse(analyzer.canAnalyze("jeu"))
    }

    @Test
    fun `analyse remplit l'entree depuis l'en-tete`() {
        val rom = testRom(title = "RAVENQUEST", type = 0x03)
        val result = analyzer.analyze("uri://rom1", "ravenquest.gb", 123L, rom)
        val entry = assertIs<AnalysisResult.Success>(result).entry
        assertEquals("RAVENQUEST", entry.title)
        assertEquals(MbcType.MBC1, entry.mbcType)
        assertTrue(entry.hasBattery)
        assertEquals(rom.size.toLong(), entry.sizeBytes)
        assertEquals(RomStatus.UNKNOWN, entry.status)
        assertTrue(entry.headerChecksumValid)
        assertEquals("RAVENQUEST", entry.displayName)
    }

    @Test
    fun `fichier trop petit rejete proprement`() {
        val result = analyzer.analyze("uri://x", "petit.gb", 0L, ByteArray(16))
        assertIs<AnalysisResult.Invalid>(result)
    }

    @Test
    fun `statut force par l'utilisateur prioritaire`() {
        val rom = testRom()
        val entry =
            (analyzer.analyze("u", "f.gb", 0, rom) as AnalysisResult.Success).entry
        val forced = entry.copy(userStatusOverride = RomStatus.HOMEBREW)
        assertEquals(RomStatus.HOMEBREW, forced.effectiveStatus)
        assertEquals(RomStatus.UNKNOWN, forced.status)
    }
}

class RomIndexTest {

    private fun entry(uri: String, lastModified: Long = 0L): RomEntry {
        val rom = testRom()
        val result = GameBoyRomAnalyzer().analyze(uri, "$uri.gb", lastModified, rom)
        return (result as AnalysisResult.Success).entry
    }

    @Test
    fun `upsert remplace par uri`() {
        var index = RomIndex()
        index = index.upsert(entry("a"))
        index = index.upsert(entry("b"))
        index = index.upsert(entry("a", lastModified = 9))
        assertEquals(2, index.entries.size)
        assertEquals(9L, index.byUri("a")?.lastModified)
    }

    @Test
    fun `retainAll retire les fichiers disparus`() {
        val index = RomIndex().upsert(entry("a")).upsert(entry("b"))
        val cleaned = index.retainAll(setOf("b"))
        assertNull(cleaned.byUri("a"))
        assertEquals(1, cleaned.entries.size)
    }

    @Test
    fun `needsRefresh sur nouveaute ou modification`() {
        val e = entry("a", lastModified = 10)
        val index = RomIndex().upsert(e)
        assertFalse(index.needsRefresh("a", e.sizeBytes, 10))
        assertTrue(index.needsRefresh("a", e.sizeBytes, 11)) // modifié
        assertTrue(index.needsRefresh("a", e.sizeBytes + 1, 10)) // taille
        assertTrue(index.needsRefresh("c", 0, 0)) // nouveau
    }

    @Test
    fun `json aller-retour`() {
        val index = RomIndex().upsert(entry("a")).upsert(entry("b"))
        val restored = RomIndex.fromJson(index.toJson())
        assertEquals(index, restored)
    }

    @Test
    fun `json corrompu donne un index vide`() {
        assertEquals(RomIndex(), RomIndex.fromJson("{pas du json"))
        assertEquals(RomIndex(), RomIndex.fromJson("""{"version":99}"""))
    }
}
