package com.ravenemu.romlibrary

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun rom(title: String = "RAVENGAME", seed: Int = 0): ByteArray {
    val data = ByteArray(0x8000)
    for ((i, c) in title.take(15).withIndex()) data[0x0134 + i] = c.code.toByte()
    data[0x2000] = seed.toByte()
    return data
}

class ReferenceDatabaseMatchTest {

    @Test
    fun `correspondance par SHA-256`() {
        val fp = Fingerprints.of(rom())
        val db = ReferenceDatabase(
            listOf(ReferenceEntry("RavenGame", ReferenceKind.OFFICIAL, sha256 = fp.sha256))
        )
        assertEquals(RomStatus.VERIFIED_OFFICIAL, db.classify(fp, "RAVENGAME"))
    }

    @Test
    fun `correspondance par SHA-1 seul`() {
        val fp = Fingerprints.of(rom())
        val db = ReferenceDatabase(
            listOf(ReferenceEntry("RavenGame", ReferenceKind.OFFICIAL, sha1 = fp.sha1))
        )
        assertEquals(RomStatus.VERIFIED_OFFICIAL, db.classify(fp, "RAVENGAME"))
    }

    @Test
    fun `correspondance par CRC32 seul insensible a la casse`() {
        val fp = Fingerprints.of(rom())
        val db = ReferenceDatabase(
            listOf(ReferenceEntry("RavenGame", ReferenceKind.HACK, crc32 = fp.crc32.lowercase()))
        )
        assertEquals(RomStatus.KNOWN_HACK, db.classify(fp, "RAVENGAME"))
    }

    @Test
    fun `sans correspondance mais titre officiel connu = modifiee`() {
        val official = Fingerprints.of(rom())
        val db = ReferenceDatabase(
            listOf(ReferenceEntry("RAVENGAME", ReferenceKind.OFFICIAL, sha256 = official.sha256))
        )
        val altered = Fingerprints.of(rom(seed = 42))
        assertEquals(RomStatus.MODIFIED_OR_UNRECOGNIZED, db.classify(altered, "RAVENGAME"))
    }

    @Test
    fun `entree sans aucune empreinte refusee`() {
        assertFailsWith<IllegalArgumentException> {
            ReferenceEntry("X", ReferenceKind.OFFICIAL)
        }
    }

    @Test
    fun `base vide classe inconnu`() {
        val db = ReferenceDatabase.empty()
        assertTrue(db.isEmpty)
        assertEquals(RomStatus.UNKNOWN, db.classify(Fingerprints.of(rom()), "RAVENGAME"))
    }
}

class ReferenceDatasetTest {

    @Test
    fun `json aller-retour`() {
        val dataset = ReferenceDataset(
            source = "test",
            entries = listOf(
                ReferenceEntry("A", ReferenceKind.OFFICIAL, sha256 = "ab".repeat(32)),
                ReferenceEntry("B", ReferenceKind.HOMEBREW, crc32 = "1234ABCD"),
            ),
        )
        val restored = assertNotNull(ReferenceDataset.fromJson(dataset.toJson()))
        assertEquals(dataset, restored)
    }

    @Test
    fun `json invalide renvoie null`() {
        assertNull(ReferenceDataset.fromJson("pas du json"))
    }

    @Test
    fun `fusion de plusieurs jeux de donnees`() {
        val fp = Fingerprints.of(rom())
        val db = ReferenceDataset.merge(
            listOf(
                ReferenceDataset(entries = listOf(ReferenceEntry("Off", ReferenceKind.OFFICIAL, sha1 = fp.sha1))),
                ReferenceDataset(entries = listOf(ReferenceEntry("Home", ReferenceKind.HOMEBREW, crc32 = "AAAA0000"))),
            )
        )
        assertEquals(2, db.size)
        assertEquals(RomStatus.VERIFIED_OFFICIAL, db.classify(fp, "RAVENGAME"))
    }
}

class NoIntroDatParserTest {

    private fun datFor(fp: Fingerprints, title: String): String = """
        <?xml version="1.0"?>
        <datafile>
          <header><name>Test</name></header>
          <game name="$title">
            <description>$title</description>
            <rom name="$title.gb" size="32768" crc="${fp.crc32}" sha1="${fp.sha1}"/>
          </game>
        </datafile>
    """.trimIndent()

    @Test
    fun `parse un DAT et classe une ROM correspondante`() {
        val fp = Fingerprints.of(rom())
        val entries = NoIntroDatParser.parse(datFor(fp, "Raven Game (Europe)"))
        assertEquals(1, entries.size)
        assertEquals("Raven Game (Europe)", entries.first().title)
        val db = ReferenceDatabase(entries)
        assertEquals(RomStatus.VERIFIED_OFFICIAL, db.classify(fp, "RAVENGAME"))
    }

    @Test
    fun `type impose pour une base de hacks`() {
        val fp = Fingerprints.of(rom())
        val entries = NoIntroDatParser.parse(datFor(fp, "Raven Hack"), ReferenceKind.HACK)
        assertEquals(RomStatus.KNOWN_HACK, ReferenceDatabase(entries).classify(fp, "RAVENGAME"))
    }

    @Test
    fun `fichier vide ou invalide rejete`() {
        assertFailsWith<ReferenceImportException> { NoIntroDatParser.parse("") }
        assertFailsWith<ReferenceImportException> {
            NoIntroDatParser.parse("<datafile></datafile>")
        }
    }

    @Test
    fun `entite externe neutralisee (anti-XXE)`() {
        // Un fichier tentant de lire /etc/passwd via une entité externe ne
        // doit ni planter ni divulguer de contenu : l'entité reste vide.
        val malicious = """
            <?xml version="1.0"?>
            <!DOCTYPE datafile [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <datafile>
              <game name="&xxe;Jeu">
                <rom name="x.gb" crc="12345678"/>
              </game>
            </datafile>
        """.trimIndent()
        // Comportement sûr : soit le fichier est rejeté (entité non résolue),
        // soit il est lu sans jamais divulguer le contenu du fichier externe.
        val result = runCatching { NoIntroDatParser.parse(malicious) }
        result.onSuccess { entries ->
            assertTrue(entries.none { it.title.contains("root:") })
        }
        result.onFailure { assertTrue(it is ReferenceImportException) }
    }
}
