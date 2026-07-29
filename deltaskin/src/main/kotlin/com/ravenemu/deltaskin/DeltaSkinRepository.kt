package com.ravenemu.deltaskin

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class DeltaSkinInstalledSkin(
    val directory: File,
    val metadata: DeltaSkinInstalledMetadata,
    val occupiedBytes: Long,
) {
    val archiveFile: File
        get() = File(directory, DeltaSkinArchiveImporter.ARCHIVE_FILE_NAME)

    fun assetFile(kind: DeltaSkinRepresentationKind): File? =
        metadata.storedAssetName(kind)?.let { name ->
            File(File(directory, DeltaSkinArchiveImporter.ASSETS_DIRECTORY), name)
        }
}

sealed interface DeltaSkinInstallResult {
    data class Installed(val skin: DeltaSkinInstalledSkin) : DeltaSkinInstallResult
    data class Replaced(
        val skin: DeltaSkinInstalledSkin,
        val previousArchiveSha256: String,
    ) : DeltaSkinInstallResult
    data class Duplicate(val skin: DeltaSkinInstalledSkin) : DeltaSkinInstallResult
    data class Conflict(val existing: DeltaSkinInstalledSkin?) : DeltaSkinInstallResult
}

/**
 * Dépôt privé transactionnel. Le nom du dossier est toujours le SHA-256 de
 * l'identifiant déclaré ; ni le nom du skin ni son identifiant ne deviennent
 * directement un chemin.
 */
class DeltaSkinRepository(private val rootDirectory: File) {
    fun list(): List<DeltaSkinInstalledSkin> {
        val children = rootDirectory.listFiles().orEmpty()
        return children.asSequence()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .mapNotNull(::readInstalled)
            .sortedWith(
                compareBy<DeltaSkinInstalledSkin> { it.metadata.console.ordinal }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.metadata.manifest.name }
                    .thenBy { it.metadata.manifest.identifier }
            )
            .toList()
    }

    fun findByIdentifier(identifier: String): DeltaSkinInstalledSkin? {
        val target = File(rootDirectory, DeltaSkinArchiveImporter.storageKey(identifier))
        return readInstalled(target)?.takeIf { it.metadata.manifest.identifier == identifier }
    }

    fun findByHash(sha256: String): DeltaSkinInstalledSkin? =
        list().firstOrNull { it.metadata.archiveSha256 == sha256 }

    fun install(
        prepared: DeltaSkinPreparedImport,
        replaceExisting: Boolean = false,
    ): DeltaSkinInstallResult {
        requirePreparedDirectory(prepared)
        rootDirectory.mkdirs()
        val duplicate = findByHash(prepared.metadata.archiveSha256)
        if (duplicate != null) {
            discard(prepared)
            return DeltaSkinInstallResult.Duplicate(duplicate)
        }
        val target = File(rootDirectory, prepared.metadata.storageKey)
        val existing = readInstalled(target)
        if (existing != null && !replaceExisting) {
            return DeltaSkinInstallResult.Conflict(existing)
        }
        if (target.exists() && existing == null && !replaceExisting) {
            return DeltaSkinInstallResult.Conflict(null)
        }

        if (existing == null && !target.exists()) {
            move(prepared.stagingDirectory, target)
            val installed = readInstalled(target)
            if (installed == null) {
                DeltaSkinArchiveImporter.deleteTree(target)
                throw DeltaSkinException(
                    DeltaSkinErrorCode.STORAGE_ERROR,
                    "Le skin installé ne peut pas être relu",
                )
            }
            return DeltaSkinInstallResult.Installed(installed)
        }

        val replacementRoot = File(rootDirectory, REPLACED_DIRECTORY)
        replacementRoot.mkdirs()
        val backup = File(replacementRoot, "${target.name}-${UUID.randomUUID()}")
        try {
            move(target, backup)
            try {
                move(prepared.stagingDirectory, target)
            } catch (error: Exception) {
                move(backup, target)
                throw error
            }
            val installed = readInstalled(target)
            if (installed == null) {
                DeltaSkinArchiveImporter.deleteTree(target)
                move(backup, target)
                throw DeltaSkinException(
                    DeltaSkinErrorCode.STORAGE_ERROR,
                    "Le skin remplacé ne peut pas être relu",
                )
            }
            DeltaSkinArchiveImporter.deleteTree(backup)
            return DeltaSkinInstallResult.Replaced(
                skin = installed,
                previousArchiveSha256 = existing?.metadata?.archiveSha256.orEmpty(),
            )
        } catch (error: DeltaSkinException) {
            throw error
        } catch (error: IOException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Le remplacement du skin a échoué",
                error,
            )
        }
    }

    fun delete(identifier: String): DeltaSkinInstalledSkin? {
        val installed = findByIdentifier(identifier) ?: return null
        val canonicalRoot = rootDirectory.canonicalFile
        val canonicalTarget = installed.directory.canonicalFile
        if (canonicalTarget.parentFile != canonicalRoot) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Le dossier du skin sort du stockage privé",
            )
        }
        try {
            DeltaSkinArchiveImporter.deleteTree(canonicalTarget)
        } catch (error: IOException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Le skin n'a pas pu être supprimé",
                error,
            )
        }
        return installed
    }

    fun discard(prepared: DeltaSkinPreparedImport) {
        requirePreparedDirectory(prepared)
        DeltaSkinArchiveImporter.deleteTree(prepared.stagingDirectory)
    }

    private fun readInstalled(directory: File): DeltaSkinInstalledSkin? {
        if (!directory.isDirectory) return null
        val canonicalRoot = runCatching { rootDirectory.canonicalFile }.getOrNull() ?: return null
        val canonicalDirectory = runCatching { directory.canonicalFile }.getOrNull() ?: return null
        if (canonicalDirectory.parentFile != canonicalRoot) return null
        val metadataFile = File(directory, DeltaSkinArchiveImporter.METADATA_FILE_NAME)
        if (!metadataFile.isFile || metadataFile.length() !in 1..MAX_METADATA_BYTES) return null
        return try {
            val metadata = DeltaSkinParser.decode<DeltaSkinInstalledMetadata>(
                metadataFile.readText(StandardCharsets.UTF_8)
            )
            if (
                metadata.schemaVersion != DeltaSkinInstalledMetadata.CURRENT_SCHEMA_VERSION ||
                metadata.storageKey != directory.name ||
                metadata.storageKey !=
                    DeltaSkinArchiveImporter.storageKey(metadata.manifest.identifier)
            ) {
                return null
            }
            val validation = DeltaSkinValidator.validate(metadata.manifest)
            if (validation.console != metadata.console) return null
            val archive = File(directory, DeltaSkinArchiveImporter.ARCHIVE_FILE_NAME)
            if (!archive.isFile || archive.length() != metadata.archiveSizeBytes) return null
            for (kind in validation.portraitKinds) {
                val assetName = metadata.storedAssetName(kind) ?: return null
                if (
                    assetName !in setOf(
                        DeltaSkinArchiveImporter.STANDARD_PORTRAIT_FILE,
                        DeltaSkinArchiveImporter.EDGE_TO_EDGE_PORTRAIT_FILE,
                    )
                ) {
                    return null
                }
                val asset = File(
                    File(directory, DeltaSkinArchiveImporter.ASSETS_DIRECTORY),
                    assetName,
                )
                if (!asset.isFile || asset.length() !in 1..DeltaSkinArchiveImporter.MAX_ENTRY_UNCOMPRESSED_BYTES) {
                    return null
                }
            }
            DeltaSkinInstalledSkin(
                directory = directory,
                metadata = metadata,
                occupiedBytes = directorySize(directory),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun requirePreparedDirectory(prepared: DeltaSkinPreparedImport) {
        val stagingRoot = File(rootDirectory, DeltaSkinArchiveImporter.STAGING_DIRECTORY)
            .canonicalFile
        val preparedDirectory = prepared.stagingDirectory.canonicalFile
        if (
            preparedDirectory.parentFile != stagingRoot ||
            prepared.metadata.storageKey !=
                DeltaSkinArchiveImporter.storageKey(prepared.metadata.manifest.identifier)
        ) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Le dossier temporaire du skin est invalide",
            )
        }
    }

    private fun move(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath())
        } catch (error: IOException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Impossible de déplacer le skin dans le stockage privé",
                error,
            )
        }
    }

    private fun directorySize(directory: File): Long =
        Files.walk(directory.toPath()).use { paths ->
            paths
                .filter { path -> Files.isRegularFile(path) }
                .mapToLong { path -> Files.size(path) }
                .sum()
        }

    companion object {
        private const val MAX_METADATA_BYTES = 2L * 1024L * 1024L
        private const val REPLACED_DIRECTORY = ".replaced"
    }
}
