package com.ravenemu.deltaskin

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeltaSkinRepositoryTest {
    @Test
    fun `import stockage hash et suppression`() = withRepository { temp, repository, importer ->
        val archive = DeltaSkinTestFixtures.createArchive(temp)
        val prepared = importer.prepare(archive, archive.name)
        val result = repository.install(prepared)
        val installed = assertIs<DeltaSkinInstallResult.Installed>(result).skin

        assertEquals(64, installed.metadata.archiveSha256.length)
        assertNotEquals(
            installed.metadata.manifest.identifier,
            installed.directory.name,
        )
        assertEquals(installed, repository.findByIdentifier(installed.metadata.manifest.identifier))
        assertTrue(installed.occupiedBytes > installed.metadata.archiveSizeBytes)

        val deleted = repository.delete(installed.metadata.manifest.identifier)
        assertEquals(installed.metadata.archiveSha256, deleted?.metadata?.archiveSha256)
        assertFalse(installed.directory.exists())
        assertNull(repository.findByIdentifier(installed.metadata.manifest.identifier))
    }

    @Test
    fun `meme hash est deduplique`() = withRepository { temp, repository, importer ->
        val archive = DeltaSkinTestFixtures.createArchive(temp)
        val first = importer.prepare(archive, archive.name)
        val installed = assertIs<DeltaSkinInstallResult.Installed>(
            repository.install(first)
        ).skin
        val second = importer.prepare(archive, archive.name)
        val duplicate = assertIs<DeltaSkinInstallResult.Duplicate>(
            repository.install(second)
        )
        assertEquals(installed.metadata.archiveSha256, duplicate.skin.metadata.archiveSha256)
        assertFalse(second.stagingDirectory.exists())
    }

    @Test
    fun `identifiant existant demande confirmation puis remplacement transactionnel`() =
        withRepository { temp, repository, importer ->
            val originalArchive = DeltaSkinTestFixtures.createArchive(
                temp,
                fileName = "original.deltaskin",
            )
            val original = assertIs<DeltaSkinInstallResult.Installed>(
                repository.install(importer.prepare(originalArchive, originalArchive.name))
            ).skin
            val changedManifest = DeltaSkinTestFixtures.manifest().copy(name = "Synthetic revised")
            val changedArchive = DeltaSkinTestFixtures.createArchive(
                temp,
                manifest = changedManifest,
                fileName = "changed.deltaskin",
            )
            val candidate = importer.prepare(changedArchive, changedArchive.name)

            val conflict = assertIs<DeltaSkinInstallResult.Conflict>(
                repository.install(candidate, replaceExisting = false)
            )
            assertEquals(
                original.metadata.archiveSha256,
                conflict.existing?.metadata?.archiveSha256,
            )
            assertTrue(candidate.stagingDirectory.exists())

            val replacement = assertIs<DeltaSkinInstallResult.Replaced>(
                repository.install(candidate, replaceExisting = true)
            )
            assertEquals("Synthetic revised", replacement.skin.metadata.manifest.name)
            assertEquals(original.metadata.archiveSha256, replacement.previousArchiveSha256)
            assertFalse(candidate.stagingDirectory.exists())
        }

    @Test
    fun `metadata ou cache PDF corrompu rend le skin indisponible`() =
        withRepository { temp, repository, importer ->
            val archive = DeltaSkinTestFixtures.createArchive(temp)
            val installed = assertIs<DeltaSkinInstallResult.Installed>(
                repository.install(importer.prepare(archive, archive.name))
            ).skin
            installed.assetFile(DeltaSkinRepresentationKind.STANDARD)!!.writeBytes(byteArrayOf())
            assertNull(repository.findByIdentifier(installed.metadata.manifest.identifier))
            assertTrue(repository.list().isEmpty())
        }

    @Test
    fun `installation invalide est retiree si la relecture echoue`() =
        withRepository { temp, repository, importer ->
            val archive = DeltaSkinTestFixtures.createArchive(temp)
            val prepared = importer.prepare(archive, archive.name)
            File(
                prepared.stagingDirectory,
                DeltaSkinArchiveImporter.METADATA_FILE_NAME,
            ).writeText("{}")

            val error = assertFailsWith<DeltaSkinException> {
                repository.install(prepared)
            }
            assertEquals(DeltaSkinErrorCode.STORAGE_ERROR, error.code)
            assertTrue(repository.list().isEmpty())
            assertFalse(
                File(
                    File(temp, "repository"),
                    prepared.metadata.storageKey,
                ).exists()
            )
        }

    private fun withRepository(
        block: (
            temp: File,
            repository: DeltaSkinRepository,
            importer: DeltaSkinArchiveImporter,
        ) -> Unit,
    ) {
        val temp = Files.createTempDirectory("ravenemu-deltaskin-repository").toFile()
        val root = File(temp, "repository")
        val repository = DeltaSkinRepository(root)
        val importer = DeltaSkinArchiveImporter(
            root,
            DeltaSkinTestFixtures.pdfInspector,
            currentTimeMillis = { 42L },
        )
        try {
            block(temp, repository, importer)
        } finally {
            DeltaSkinArchiveImporter.deleteTree(temp)
        }
    }
}
