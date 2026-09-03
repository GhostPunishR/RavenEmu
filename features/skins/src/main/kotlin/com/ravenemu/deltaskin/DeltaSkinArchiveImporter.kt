package com.ravenemu.deltaskin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.math.abs

data class DeltaSkinPdfInfo(
    val pageCount: Int,
    val width: Int,
    val height: Int,
)

fun interface DeltaSkinPdfInspector {
    @Throws(IOException::class)
    fun inspect(file: File): DeltaSkinPdfInfo
}

data class DeltaSkinPreparedImport(
    val stagingDirectory: File,
    val metadata: DeltaSkinInstalledMetadata,
)

/**
 * Prépare une installation dans un dossier temporaire privé. Aucun nom issu du
 * manifeste ne devient un chemin de destination : les deux PDF portrait sont
 * copiés sous des noms fixes après validation complète de l'archive.
 */
class DeltaSkinArchiveImporter(
    private val repositoryRoot: File,
    private val pdfInspector: DeltaSkinPdfInspector,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    fun prepare(archiveFile: File, originalFileName: String): DeltaSkinPreparedImport {
        validateExtension(originalFileName)
        validateArchiveFile(archiveFile)
        val centralEntries = ZipCentralDirectory.read(archiveFile)
        val inspection = inspectArchive(archiveFile, centralEntries)
        val manifest = DeltaSkinParser.parse(decodeUtf8(inspection.manifestBytes))
        val validation = DeltaSkinValidator.validate(manifest)
        val iphone = requireNotNull(manifest.representations.iphone)

        val standardRepresentation = iphone.standard?.portrait
        val edgeRepresentation = iphone.edgeToEdge?.portrait
        val standardAsset = standardRepresentation?.assets?.resizable
        val edgeAsset = edgeRepresentation?.assets?.resizable
        val availableEntries = inspection.entries
            .filterNot { isIgnored(it.name) }
            .associateBy { it.name }
        fun isPresent(asset: ReferencedPdf): Boolean =
            availableEntries[asset.name]?.isDirectory == false

        val requiredAssets = listOfNotNull(
            standardRepresentation?.toReferencedPdf(),
            edgeRepresentation?.toReferencedPdf(),
        )
        requiredAssets.forEach { asset ->
            if (!isPresent(asset)) {
                throw DeltaSkinException(
                    DeltaSkinErrorCode.RESIZABLE_ASSET_MISSING,
                    "Un asset PDF référencé est absent de l'archive",
                )
            }
        }

        // Le paysage n'est jamais rendu : son PDF est contrôlé s'il est livré,
        // mais son absence ne condamne pas l'archive. De vrais skins déclarent
        // une représentation paysage sans joindre son image, et refuser tout
        // priverait l'utilisateur d'un portrait parfaitement valide.
        val landscapeAssets = listOfNotNull(
            iphone.standard?.landscape?.toReferencedPdf(),
            iphone.edgeToEdge?.landscape?.toReferencedPdf(),
        ).filter(::isPresent)

        val referencedAssets = requiredAssets + landscapeAssets
        val referencedNames = referencedAssets.map(ReferencedPdf::name).distinct()
        val requiredStorageBytes = archiveFile.length() +
            referencedNames.sumOf { availableEntries.getValue(it).size } +
            MAX_INFO_JSON_BYTES
        if (
            repositoryRoot.usableSpace > 0L &&
            repositoryRoot.usableSpace < requiredStorageBytes
        ) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_INSUFFICIENT,
                "Le stockage disponible est insuffisant",
            )
        }

        repositoryRoot.mkdirs()
        val stagingRoot = File(repositoryRoot, STAGING_DIRECTORY)
        if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Impossible de préparer le stockage des skins",
            )
        }
        val staging = File(stagingRoot, UUID.randomUUID().toString())
        if (!staging.mkdir()) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Impossible de créer le dossier temporaire du skin",
            )
        }

        try {
            val archiveDestination = File(staging, ARCHIVE_FILE_NAME)
            Files.copy(
                archiveFile.toPath(),
                archiveDestination.toPath(),
            )
            val assetsDirectory = File(staging, ASSETS_DIRECTORY)
            if (!assetsDirectory.mkdir()) {
                throw DeltaSkinException(
                    DeltaSkinErrorCode.STORAGE_ERROR,
                    "Impossible de préparer les assets du skin",
                )
            }

            ZipFile(archiveFile, StandardCharsets.UTF_8).use { zip ->
                val inspectedAssets = mutableSetOf<String>()
                val pdfInfos = mutableMapOf<String, DeltaSkinPdfInfo>()
                standardAsset?.let { asset ->
                    pdfInfos[asset] = extractAndInspectPdf(
                        zip = zip,
                        sourceName = asset,
                        destination = File(assetsDirectory, STANDARD_PORTRAIT_FILE),
                    )
                    inspectedAssets += asset
                }
                edgeAsset?.let { asset ->
                    pdfInfos[asset] = extractAndInspectPdf(
                        zip = zip,
                        sourceName = asset,
                        destination = File(assetsDirectory, EDGE_TO_EDGE_PORTRAIT_FILE),
                    )
                    inspectedAssets += asset
                }
                referencedNames
                    .filterNot(inspectedAssets::contains)
                    .forEachIndexed { index, sourceName ->
                        val validationFile = File(staging, "validation-$index.pdf")
                        try {
                            pdfInfos[sourceName] =
                                extractAndInspectPdf(zip, sourceName, validationFile)
                        } finally {
                            validationFile.delete()
                        }
                    }
                referencedAssets.forEach { referenced ->
                    validatePdfAspect(
                        info = requireNotNull(pdfInfos[referenced.name]),
                        mappingSize = referenced.mappingSize,
                    )
                }
            }

            val sha256 = sha256(archiveDestination)
            val metadata = DeltaSkinInstalledMetadata(
                storageKey = storageKey(manifest.identifier),
                manifest = manifest,
                console = validation.console,
                archiveSha256 = sha256,
                importedAtEpochMillis = currentTimeMillis(),
                archiveSizeBytes = archiveDestination.length(),
                standardPortraitAsset =
                    STANDARD_PORTRAIT_FILE.takeIf { standardAsset != null },
                edgeToEdgePortraitAsset =
                    EDGE_TO_EDGE_PORTRAIT_FILE.takeIf { edgeAsset != null },
                ignoredInputs = validation.ignoredInputs.sorted(),
            )
            File(staging, METADATA_FILE_NAME).writeText(
                DeltaSkinParser.encode(metadata),
                StandardCharsets.UTF_8,
            )
            return DeltaSkinPreparedImport(staging, metadata)
        } catch (error: DeltaSkinException) {
            deleteTree(staging)
            throw error
        } catch (error: IOException) {
            deleteTree(staging)
            val code = if (repositoryRoot.usableSpace in 1L until archiveFile.length()) {
                DeltaSkinErrorCode.STORAGE_INSUFFICIENT
            } else {
                DeltaSkinErrorCode.STORAGE_ERROR
            }
            throw DeltaSkinException(code, "Impossible de stocker le skin", error)
        } catch (error: RuntimeException) {
            deleteTree(staging)
            throw DeltaSkinException(
                DeltaSkinErrorCode.INVALID_ARCHIVE,
                "L'archive du skin est illisible",
                error,
            )
        }
    }

    private fun inspectArchive(
        archiveFile: File,
        centralEntries: List<CentralEntry>,
    ): ArchiveInspection {
        if (centralEntries.size > MAX_ENTRIES) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.TOO_MANY_ENTRIES,
                "Le skin contient trop de fichiers",
            )
        }
        centralEntries.forEach { entry ->
            if (entry.encrypted) {
                throw DeltaSkinException(
                    DeltaSkinErrorCode.INVALID_ARCHIVE,
                    "Les archives chiffrées ne sont pas acceptées",
                )
            }
            if (entry.symbolicLink) {
                throw DeltaSkinException(
                    DeltaSkinErrorCode.SYMBOLIC_LINK,
                    "Les liens symboliques ne sont pas acceptés",
                )
            }
        }

        try {
            ZipFile(archiveFile, StandardCharsets.UTF_8).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size != centralEntries.size) {
                    throw DeltaSkinException(
                        DeltaSkinErrorCode.INVALID_ARCHIVE,
                        "Le répertoire ZIP est incohérent",
                    )
                }
                val canonicalNames = mutableSetOf<String>()
                var manifestCount = 0
                var declaredTotal = 0L
                var actualTotal = 0L
                var manifestBytes: ByteArray? = null

                entries.forEachIndexed { index, entry ->
                    if (entry.name != centralEntries[index].name) {
                        throw DeltaSkinException(
                            DeltaSkinErrorCode.INVALID_ARCHIVE,
                            "Le répertoire ZIP est incohérent",
                        )
                    }
                    validateEntryMetadata(entry)
                    declaredTotal = safeAdd(declaredTotal, entry.size)
                    if (declaredTotal > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                        throw DeltaSkinException(
                            DeltaSkinErrorCode.DECOMPRESSED_TOO_LARGE,
                            "Le contenu décompressé du skin est trop volumineux",
                        )
                    }

                    val ignored = isIgnored(entry.name)
                    if (!ignored) {
                        validateEntryPath(entry)
                        val canonical = Normalizer.normalize(entry.name, Normalizer.Form.NFC)
                            .lowercase(Locale.ROOT)
                        if (!canonicalNames.add(canonical)) {
                            throw DeltaSkinException(
                                if (canonical == INFO_JSON) {
                                    DeltaSkinErrorCode.MANIFEST_DUPLICATE
                                } else {
                                    DeltaSkinErrorCode.INVALID_ARCHIVE
                                },
                                "L'archive contient des fichiers en doublon",
                            )
                        }
                        if (entry.name == INFO_JSON) manifestCount++
                        if (isNestedArchiveName(entry.name)) {
                            throw DeltaSkinException(
                                DeltaSkinErrorCode.NESTED_ARCHIVE,
                                "Les archives imbriquées ne sont pas acceptées",
                            )
                        }
                    }

                    if (!entry.isDirectory) {
                        val output = if (!ignored && entry.name == INFO_JSON) {
                            ByteArrayOutputStream(entry.size.toInt().coerceAtLeast(32))
                        } else {
                            null
                        }
                        val actual = zip.getInputStream(entry).use { input ->
                            var entryTotal = 0L
                            val prefix = ByteArray(4)
                            var prefixCount = 0
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                if (prefixCount < prefix.size) {
                                    val copy = minOf(read, prefix.size - prefixCount)
                                    buffer.copyInto(prefix, prefixCount, 0, copy)
                                    prefixCount += copy
                                }
                                entryTotal += read
                                actualTotal += read
                                if (
                                    entryTotal > MAX_ENTRY_UNCOMPRESSED_BYTES ||
                                    actualTotal > MAX_TOTAL_UNCOMPRESSED_BYTES
                                ) {
                                    throw DeltaSkinException(
                                        DeltaSkinErrorCode.DECOMPRESSED_TOO_LARGE,
                                        "Le contenu décompressé du skin est trop volumineux",
                                    )
                                }
                                output?.write(buffer, 0, read)
                            }
                            if (!ignored && isZipSignature(prefix, prefixCount)) {
                                throw DeltaSkinException(
                                    DeltaSkinErrorCode.NESTED_ARCHIVE,
                                    "Les archives imbriquées ne sont pas acceptées",
                                )
                            }
                            entryTotal
                        }
                        if (actual != entry.size) {
                            throw DeltaSkinException(
                                DeltaSkinErrorCode.INVALID_ARCHIVE,
                                "La taille déclarée d'un fichier ZIP est incohérente",
                            )
                        }
                        if (output != null) manifestBytes = output.toByteArray()
                    }
                }

                if (manifestCount == 0) {
                    throw DeltaSkinException(
                        DeltaSkinErrorCode.MANIFEST_MISSING,
                        "Le fichier info.json est absent",
                    )
                }
                if (manifestCount != 1) {
                    throw DeltaSkinException(
                        DeltaSkinErrorCode.MANIFEST_DUPLICATE,
                        "L'archive contient plusieurs info.json",
                    )
                }
                return ArchiveInspection(
                    entries = entries,
                    manifestBytes = manifestBytes
                        ?: throw DeltaSkinException(
                            DeltaSkinErrorCode.MANIFEST_MISSING,
                            "Le fichier info.json est absent",
                        ),
                )
            }
        } catch (error: DeltaSkinException) {
            throw error
        } catch (error: ZipException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.INVALID_ARCHIVE,
                "La structure ZIP est invalide",
                error,
            )
        } catch (error: IOException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.INVALID_ARCHIVE,
                "L'archive ne peut pas être lue",
                error,
            )
        }
    }

    private fun validateEntryMetadata(entry: ZipEntry) {
        if (entry.size < 0L || entry.compressedSize < 0L) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.INVALID_ARCHIVE,
                "Une entrée ZIP ne déclare pas sa taille",
            )
        }
        if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.INVALID_ARCHIVE,
                "Une méthode de compression ZIP n'est pas prise en charge",
            )
        }
        val entryLimit = if (entry.name == INFO_JSON) {
            MAX_INFO_JSON_BYTES
        } else {
            MAX_ENTRY_UNCOMPRESSED_BYTES
        }
        if (entry.size > entryLimit) {
            throw DeltaSkinException(
                if (entry.name == INFO_JSON) {
                    DeltaSkinErrorCode.MANIFEST_TOO_LARGE
                } else {
                    DeltaSkinErrorCode.ENTRY_TOO_LARGE
                },
                "Un fichier du skin est trop volumineux",
            )
        }
        if (
            entry.compressedSize > 0L &&
            entry.size > COMPRESSION_RATIO_MINIMUM_BYTES &&
            entry.size / entry.compressedSize > MAX_COMPRESSION_RATIO
        ) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.DECOMPRESSED_TOO_LARGE,
                "Le taux de compression du skin est excessif",
            )
        }
    }

    private fun validateEntryPath(entry: ZipEntry) {
        if (entry.isDirectory || !DeltaSkinValidator.isSafeRootAssetName(entry.name)) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.UNSAFE_ARCHIVE_PATH,
                "L'archive contient un chemin non sûr",
            )
        }
    }

    private fun extractAndInspectPdf(
        zip: ZipFile,
        sourceName: String,
        destination: File,
    ): DeltaSkinPdfInfo {
        val entry = zip.getEntry(sourceName)
            ?: throw DeltaSkinException(
                DeltaSkinErrorCode.RESIZABLE_ASSET_MISSING,
                "Un asset PDF référencé est absent",
            )
        zip.getInputStream(entry).use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > MAX_ENTRY_UNCOMPRESSED_BYTES) {
                        throw DeltaSkinException(
                            DeltaSkinErrorCode.ENTRY_TOO_LARGE,
                            "L'asset PDF est trop volumineux",
                        )
                    }
                    output.write(buffer, 0, read)
                }
            }
        }
        val info = try {
            pdfInspector.inspect(destination)
        } catch (error: IOException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.PDF_INVALID,
                "L'asset PDF est illisible",
                error,
            )
        } catch (error: RuntimeException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.PDF_INVALID,
                "L'asset PDF est illisible",
                error,
            )
        }
        if (info.pageCount != 1) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.PDF_MULTIPLE_PAGES,
                "Seuls les PDF d'une page sont acceptés",
            )
        }
        if (info.width <= 0 || info.height <= 0) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.PDF_INVALID,
                "L'asset PDF n'a pas de dimensions valides",
            )
        }
        return info
    }

    private fun validatePdfAspect(info: DeltaSkinPdfInfo, mappingSize: DeltaSkinSize) {
        val pdfRatio = info.width.toDouble() / info.height
        val mappingRatio = mappingSize.width / mappingSize.height
        if (abs(pdfRatio / mappingRatio - 1.0) > PDF_ASPECT_RATIO_TOLERANCE) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.PDF_INVALID,
                "Le ratio de l'asset PDF ne correspond pas à mappingSize",
            )
        }
    }

    private fun validateExtension(fileName: String) {
        if (!fileName.lowercase(Locale.ROOT).endsWith(DELTASKIN_EXTENSION)) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.INVALID_EXTENSION,
                "Le fichier doit porter l'extension .deltaskin",
            )
        }
    }

    private fun validateArchiveFile(file: File) {
        if (!file.isFile || file.length() <= 4L) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.NOT_A_ZIP,
                "Le fichier sélectionné n'est pas une archive ZIP",
            )
        }
        if (file.length() > MAX_ARCHIVE_BYTES) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.ARCHIVE_TOO_LARGE,
                "Le skin dépasse la taille maximale autorisée",
            )
        }
        val signature = file.inputStream().use { input ->
            ByteArray(4).also { bytes ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) {
                        throw DeltaSkinException(
                            DeltaSkinErrorCode.NOT_A_ZIP,
                            "Le fichier sélectionné n'est pas une archive ZIP",
                        )
                    }
                    if (read > 0) offset += read
                }
            }
        }
        if (!isZipSignature(signature, signature.size)) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.NOT_A_ZIP,
                "Le fichier sélectionné n'est pas une archive ZIP",
            )
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw DeltaSkinException(
            DeltaSkinErrorCode.MALFORMED_JSON,
            "info.json n'est pas encodé en UTF-8 valide",
            error,
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safeAdd(left: Long, right: Long): Long {
        if (right < 0L || left > Long.MAX_VALUE - right) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.DECOMPRESSED_TOO_LARGE,
                "Les tailles ZIP sont incohérentes",
            )
        }
        return left + right
    }

    private fun isIgnored(name: String): Boolean {
        val baseName = name.substringAfterLast('/')
        return name == "__MACOSX" ||
            name.startsWith("__MACOSX/") ||
            baseName == ".DS_Store" ||
            baseName.startsWith("._")
    }

    private fun isNestedArchiveName(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return NESTED_ARCHIVE_EXTENSIONS.any(lower::endsWith)
    }

    private fun isZipSignature(prefix: ByteArray, count: Int): Boolean {
        if (
            count < 4 ||
            prefix[0] != 0x50.toByte() ||
            prefix[1] != 0x4b.toByte()
        ) {
            return false
        }
        return (prefix[2] == 0x03.toByte() && prefix[3] == 0x04.toByte()) ||
            (prefix[2] == 0x05.toByte() && prefix[3] == 0x06.toByte()) ||
            (prefix[2] == 0x07.toByte() && prefix[3] == 0x08.toByte())
    }

    private data class ArchiveInspection(
        val entries: List<ZipEntry>,
        val manifestBytes: ByteArray,
    )

    private data class ReferencedPdf(
        val name: String,
        val mappingSize: DeltaSkinSize,
    )

    private fun DeltaSkinRepresentation.toReferencedPdf(): ReferencedPdf? =
        assets.resizable?.let { ReferencedPdf(it, mappingSize) }

    companion object {
        const val MAX_ARCHIVE_BYTES = 25L * 1024L * 1024L
        const val MAX_ENTRIES = 64
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 50L * 1024L * 1024L
        const val MAX_INFO_JSON_BYTES = 1L * 1024L * 1024L
        const val MAX_ENTRY_UNCOMPRESSED_BYTES = 25L * 1024L * 1024L

        const val ARCHIVE_FILE_NAME = "skin.deltaskin"
        const val METADATA_FILE_NAME = "metadata.json"
        const val ASSETS_DIRECTORY = "assets"
        const val STANDARD_PORTRAIT_FILE = "standard-portrait.pdf"
        const val EDGE_TO_EDGE_PORTRAIT_FILE = "edge-to-edge-portrait.pdf"
        const val STAGING_DIRECTORY = ".staging"

        private const val INFO_JSON = "info.json"
        private const val DELTASKIN_EXTENSION = ".deltaskin"
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val COMPRESSION_RATIO_MINIMUM_BYTES = 1L * 1024L * 1024L
        private const val MAX_COMPRESSION_RATIO = 200L
        private const val PDF_ASPECT_RATIO_TOLERANCE = 0.01
        private val NESTED_ARCHIVE_EXTENSIONS =
            setOf(".zip", ".deltaskin", ".jar", ".apk", ".tar", ".tgz", ".gz", ".7z", ".rar")

        fun storageKey(identifier: String): String {
            val bytes = MessageDigest.getInstance("SHA-256")
                .digest(identifier.toByteArray(StandardCharsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }

        internal fun deleteTree(root: File) {
            if (!root.exists()) return
            Files.walk(root.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    Files.deleteIfExists(path)
                }
            }
        }
    }
}

private data class CentralEntry(
    val name: String,
    val encrypted: Boolean,
    val symbolicLink: Boolean,
)

/** Lecture minimale du répertoire central, notamment des modes Unix absents de ZipEntry. */
private object ZipCentralDirectory {
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val MAX_EOCD_SEARCH = 65_557L

    fun read(file: File): List<CentralEntry> {
        try {
            RandomAccessFile(file, "r").use { archive ->
                val eocdOffset = findEndOfCentralDirectory(archive)
                archive.seek(eocdOffset + 4)
                val diskNumber = archive.readUnsignedShortLE()
                val centralDisk = archive.readUnsignedShortLE()
                val entriesOnDisk = archive.readUnsignedShortLE()
                val entryCount = archive.readUnsignedShortLE()
                val centralSize = archive.readUnsignedIntLE()
                val centralOffset = archive.readUnsignedIntLE()
                val commentLength = archive.readUnsignedShortLE()
                if (
                    diskNumber != 0 ||
                    centralDisk != 0 ||
                    entriesOnDisk != entryCount ||
                    entryCount == 0xffff ||
                    centralSize == 0xffff_ffffL ||
                    centralOffset == 0xffff_ffffL ||
                    eocdOffset + 22L + commentLength != archive.length()
                ) {
                    throw DeltaSkinException(
                        DeltaSkinErrorCode.INVALID_ARCHIVE,
                        "Les archives multi-disques ou ZIP64 ne sont pas acceptées",
                    )
                }
                if (centralOffset + centralSize > eocdOffset) {
                    throw DeltaSkinException(
                        DeltaSkinErrorCode.INVALID_ARCHIVE,
                        "Le répertoire central ZIP est hors limites",
                    )
                }

                archive.seek(centralOffset)
                val entries = ArrayList<CentralEntry>(entryCount)
                repeat(entryCount) {
                    if (archive.readIntLE() != CENTRAL_SIGNATURE) {
                        throw DeltaSkinException(
                            DeltaSkinErrorCode.INVALID_ARCHIVE,
                            "Le répertoire central ZIP est invalide",
                        )
                    }
                    val versionMadeBy = archive.readUnsignedShortLE()
                    archive.skipBytes(2)
                    val flags = archive.readUnsignedShortLE()
                    archive.skipBytes(18)
                    val nameLength = archive.readUnsignedShortLE()
                    val extraLength = archive.readUnsignedShortLE()
                    val entryCommentLength = archive.readUnsignedShortLE()
                    archive.skipBytes(4)
                    val externalAttributes = archive.readUnsignedIntLE()
                    archive.skipBytes(4)
                    val nameBytes = ByteArray(nameLength)
                    archive.readFully(nameBytes)
                    val charset = if (flags and 0x0800 != 0) {
                        StandardCharsets.UTF_8
                    } else {
                        CharsetHolder.CP437
                    }
                    val name = String(nameBytes, charset)
                    archive.skipBytes(extraLength + entryCommentLength)
                    val hostSystem = versionMadeBy ushr 8
                    val unixMode = (externalAttributes ushr 16).toInt()
                    entries += CentralEntry(
                        name = name,
                        encrypted = flags and 0x0001 != 0,
                        symbolicLink =
                            hostSystem == 3 && unixMode and 0xf000 == 0xa000,
                    )
                }
                if (archive.filePointer != centralOffset + centralSize) {
                    throw DeltaSkinException(
                        DeltaSkinErrorCode.INVALID_ARCHIVE,
                        "La taille du répertoire central ZIP est incohérente",
                    )
                }
                return entries
            }
        } catch (error: DeltaSkinException) {
            throw error
        } catch (error: IOException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.INVALID_ARCHIVE,
                "Le répertoire central ZIP est illisible",
                error,
            )
        }
    }

    private fun findEndOfCentralDirectory(archive: RandomAccessFile): Long {
        val minimum = maxOf(0L, archive.length() - MAX_EOCD_SEARCH)
        var position = archive.length() - 22L
        while (position >= minimum) {
            archive.seek(position)
            if (archive.readIntLE() == EOCD_SIGNATURE) return position
            position--
        }
        throw DeltaSkinException(
            DeltaSkinErrorCode.INVALID_ARCHIVE,
            "La fin du répertoire ZIP est absente",
        )
    }

    private fun RandomAccessFile.readUnsignedShortLE(): Int =
        java.lang.Short.toUnsignedInt(java.lang.Short.reverseBytes(readShort()))

    private fun RandomAccessFile.readIntLE(): Int = Integer.reverseBytes(readInt())

    private fun RandomAccessFile.readUnsignedIntLE(): Long =
        Integer.toUnsignedLong(readIntLE())

    private object CharsetHolder {
        val CP437 = java.nio.charset.Charset.forName("Cp437")
    }
}
