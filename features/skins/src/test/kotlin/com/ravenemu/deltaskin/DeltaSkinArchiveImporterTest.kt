package com.ravenemu.deltaskin

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeltaSkinArchiveImporterTest {
    @Test
    fun `archive synthetique valide et PDF une page sont prepares`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(temp)
        val prepared = importer(temp).prepare(archive, archive.name)
        assertEquals(DeltaSkinConsole.GB_GBC, prepared.metadata.console)
        assertEquals(64, prepared.metadata.archiveSha256.length)
        assertTrue(
            File(
                File(prepared.stagingDirectory, DeltaSkinArchiveImporter.ASSETS_DIRECTORY),
                DeltaSkinArchiveImporter.STANDARD_PORTRAIT_FILE,
            ).isFile
        )
    }

    @Test
    fun `extension deltaskin est obligatoire`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(temp, fileName = "synthetic.zip")
        assertCode(DeltaSkinErrorCode.INVALID_EXTENSION) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `faux fichier deltaskin est refuse`() = withTempDirectory { temp ->
        val file = File(temp, "fake.deltaskin").apply { writeText("not a zip") }
        assertCode(DeltaSkinErrorCode.NOT_A_ZIP) {
            importer(temp).prepare(file, file.name)
        }
    }

    @Test
    fun `info json manquant est refuse`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(temp, includeManifest = false)
        assertCode(DeltaSkinErrorCode.MANIFEST_MISSING) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `plusieurs variantes de info json sont refusees`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(
            temp,
            extraEntries = mapOf("INFO.JSON" to "{}".toByteArray()),
        )
        assertCode(DeltaSkinErrorCode.MANIFEST_DUPLICATE) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `asset PDF manquant est refuse`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(
            temp,
            omitAssets = setOf("iphone_portrait.pdf"),
        )
        assertCode(DeltaSkinErrorCode.RESIZABLE_ASSET_MISSING) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `un asset paysage absent nempeche pas limport`() = withTempDirectory { temp ->
        // Cas rencontré sur de vrais skins : les deux représentations paysage
        // sont déclarées, leurs PDF ne sont pas joints. Le paysage n'étant
        // jamais rendu, l'archive reste utilisable telle quelle.
        val archive = DeltaSkinTestFixtures.createArchive(
            temp,
            manifest = DeltaSkinTestFixtures.manifest(landscape = true),
            omitAssets = setOf("iphone_landscape.pdf", "iphone_edgetoedge_landscape.pdf"),
        )
        val prepared = importer(temp).prepare(archive, archive.name)
        assertEquals(
            DeltaSkinArchiveImporter.STANDARD_PORTRAIT_FILE,
            prepared.metadata.standardPortraitAsset,
        )
        assertEquals(
            DeltaSkinArchiveImporter.EDGE_TO_EDGE_PORTRAIT_FILE,
            prepared.metadata.edgeToEdgePortraitAsset,
        )
    }

    @Test
    fun `un asset paysage livre est tout de meme controle`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(
            temp,
            manifest = DeltaSkinTestFixtures.manifest(landscape = true),
            extraEntries = mapOf(
                "iphone_landscape.pdf" to DeltaSkinTestFixtures.syntheticPdf(
                    width = 10,
                    height = 500,
                )
            ),
        )
        assertCode(DeltaSkinErrorCode.PDF_INVALID) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `JSON malforme dans archive est refuse`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(
            temp,
            extraEntries = mapOf("info.json" to """{"name":""".toByteArray()),
        )
        assertCode(DeltaSkinErrorCode.MALFORMED_JSON) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `console inconnue est refusee`() = withTempDirectory { temp ->
        val manifest = DeltaSkinTestFixtures.manifest().copy(
            gameTypeIdentifier = "unsupported"
        )
        val archive = DeltaSkinTestFixtures.createArchive(temp, manifest)
        assertCode(DeltaSkinErrorCode.UNSUPPORTED_CONSOLE) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `zip slip et chemin absolu sont refuses`() = withTempDirectory { temp ->
        for (
            path in listOf(
                "../evil",
                "/absolute.pdf",
                "folder\\evil.pdf",
                "folder\u2215evil.pdf",
                "folder\u29f8evil.pdf",
                "folder\uff3cevil.pdf",
                "C:\\evil.pdf",
            )
        ) {
            val archive = DeltaSkinTestFixtures.createArchive(
                temp,
                extraEntries = mapOf(path to "evil".toByteArray()),
                fileName = "unsafe-${path.hashCode()}.deltaskin",
            )
            assertCode(DeltaSkinErrorCode.UNSAFE_ARCHIVE_PATH) {
                importer(temp).prepare(archive, archive.name)
            }
        }
    }

    @Test
    fun `entrees macOS sont ignorees sans remplacer le manifeste`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(
            temp,
            extraEntries = mapOf(
                "__MACOSX/._info.json" to "parasite".toByteArray(),
                "._info.json" to "parasite".toByteArray(),
                ".DS_Store" to "parasite".toByteArray(),
            ),
        )
        val prepared = importer(temp).prepare(archive, archive.name)
        assertEquals("Synthetic GBC", prepared.metadata.manifest.name)
    }

    @Test
    fun `une entree macOS ignoree ne peut pas servir dasset`() = withTempDirectory { temp ->
        val base = DeltaSkinTestFixtures.manifest(edgeToEdge = false)
        val iphone = requireNotNull(base.representations.iphone)
        val standard = requireNotNull(iphone.standard)
        val portrait = requireNotNull(standard.portrait)
        val manifest = base.copy(
            representations = base.representations.copy(
                iphone = iphone.copy(
                    standard = standard.copy(
                        portrait = portrait.copy(
                            assets = DeltaSkinAssets(resizable = "._portrait.pdf")
                        )
                    )
                )
            )
        )
        val archive = DeltaSkinTestFixtures.createArchive(temp, manifest)
        assertCode(DeltaSkinErrorCode.RESIZABLE_ASSET_MISSING) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `trop dentrees est refuse`() = withTempDirectory { temp ->
        val extras = (0..64).associate { "extra-$it.txt" to byteArrayOf(it.toByte()) }
        val archive = DeltaSkinTestFixtures.createArchive(temp, extraEntries = extras)
        assertCode(DeltaSkinErrorCode.TOO_MANY_ENTRIES) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `archive de plus de 25 Mio est refusee avant lecture`() = withTempDirectory { temp ->
        val archive = File(temp, "large.deltaskin")
        RandomAccessFile(archive, "rw").use {
            it.setLength(DeltaSkinArchiveImporter.MAX_ARCHIVE_BYTES + 1)
        }
        assertCode(DeltaSkinErrorCode.ARCHIVE_TOO_LARGE) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `taux de compression excessif est refuse`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(
            temp,
            extraEntries = mapOf("bomb.bin" to ByteArray(2 * 1024 * 1024)),
        )
        assertCode(DeltaSkinErrorCode.DECOMPRESSED_TOO_LARGE) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `archive imbriquee est refusee par nom ou signature`() = withTempDirectory { temp ->
        val byName = DeltaSkinTestFixtures.createArchive(
            temp,
            extraEntries = mapOf("nested.zip" to "x".toByteArray()),
            fileName = "nested-name.deltaskin",
        )
        assertCode(DeltaSkinErrorCode.NESTED_ARCHIVE) {
            importer(temp).prepare(byName, byName.name)
        }
        val bySignature = DeltaSkinTestFixtures.createArchive(
            temp,
            extraEntries = mapOf("nested.bin" to byteArrayOf(0x50, 0x4b, 0x03, 0x04)),
            fileName = "nested-signature.deltaskin",
        )
        assertCode(DeltaSkinErrorCode.NESTED_ARCHIVE) {
            importer(temp).prepare(bySignature, bySignature.name)
        }
    }

    @Test
    fun `lien symbolique Unix est refuse`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(temp)
        DeltaSkinTestFixtures.markFirstFileAsUnixSymlink(archive)
        assertCode(DeltaSkinErrorCode.SYMBOLIC_LINK) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `PDF multi pages est refuse`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(
            temp,
            pdfPages = mapOf("iphone_portrait.pdf" to 2),
        )
        assertCode(DeltaSkinErrorCode.PDF_MULTIPLE_PAGES) {
            importer(temp).prepare(archive, archive.name)
        }
    }

    @Test
    fun `PDF dont le ratio differe de mappingSize est refuse`() = withTempDirectory { temp ->
        val archive = DeltaSkinTestFixtures.createArchive(temp)
        val importer = DeltaSkinArchiveImporter(
            repositoryRoot = File(temp, "repository"),
            pdfInspector = DeltaSkinPdfInspector {
                DeltaSkinPdfInfo(pageCount = 1, width = 100, height = 100)
            },
        )
        assertCode(DeltaSkinErrorCode.PDF_INVALID) {
            importer.prepare(archive, archive.name)
        }
    }

    private fun importer(temp: File): DeltaSkinArchiveImporter =
        DeltaSkinArchiveImporter(
            repositoryRoot = File(temp, "repository"),
            pdfInspector = DeltaSkinTestFixtures.pdfInspector,
            currentTimeMillis = { 1234L },
        )

    private fun assertCode(code: DeltaSkinErrorCode, block: () -> Unit) {
        val error = assertFailsWith<DeltaSkinException>(block = block)
        assertEquals(code, error.code)
    }

    private fun withTempDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("ravenemu-deltaskin-test").toFile()
        try {
            block(directory)
        } finally {
            DeltaSkinArchiveImporter.deleteTree(directory)
        }
    }
}
