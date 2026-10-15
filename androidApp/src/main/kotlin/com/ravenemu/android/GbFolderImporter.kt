package com.ravenemu.android

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.ravenemu.shared.RomImportPolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * One-shot Storage Access Framework tree import. The picker grants access to
 * the chosen tree for this operation; ROMs are copied into the already used
 * app-private GB/GBC/GBA library and do not depend on persistent folder permissions.
 *
 * Never touches AndroidGbSession: a temporary core of the right console validates each ROM, so
 * the currently active game, SRAM, audio and save states stay untouched.
 * Call from Dispatchers.IO, not the main thread.
 */
internal class GbFolderImporter(
    private val resolver: ContentResolver,
    private val library: GbRomLibrary,
) {
    data class Result(
        val added: Int,
        val duplicate: Int,
        val invalid: Int,
        val unreadable: Int,
        val otherFiles: Int,
        val folders: Int,
        val limited: Boolean,
    ) {
        val romsProcessed: Int get() = added + duplicate + invalid + unreadable
    }

    private data class PendingDirectory(val documentId: String, val depth: Int)
    fun importTree(treeUri: Uri, onProgress: (Int) -> Unit = {}): Result {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        require(rootId.isNotEmpty()) { "Dossier Android invalide." }
        val seenDirectories = hashSetOf(rootId)
        val existing = library.list().mapTo(hashSetOf()) { it.id }
        val pending = ArrayDeque<PendingDirectory>()
        pending.add(PendingDirectory(rootId, 0))

        var added = 0
        var duplicate = 0
        var invalid = 0
        var unreadable = 0
        var otherFiles = 0
        var folders = 0
        var entriesSeen = 0
        var candidatesSeen = 0
        var bytesProcessed = 0L
        var limited = false

        mainLoop@ while (pending.isNotEmpty()) {
            val dir = pending.removeFirst()
            folders++
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, dir.documentId,
            )
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val cursor = try {
                resolver.query(childrenUri, columns, null, null, null)
                    ?: throw IllegalStateException("Le dossier n'est pas accessible.")
            } catch (error: Exception) {
                if (dir.depth == 0) throw error
                unreadable++
                Log.w(TAG, "Failed to inspect a subdirectory", error)
                continue
            }
            try {
                while (cursor.moveToNext()) {
                    entriesSeen++
                    if (entriesSeen > MAX_ENTRIES) {
                        limited = true
                        break@mainLoop
                    }
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    val size = if (cursor.isNull(3)) null else cursor.getLong(3)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (id in seenDirectories) continue
                        if (!GbFolderImportPolicy.mayDescend(dir.depth, seenDirectories.size)) {
                            limited = true
                            continue
                        }
                        seenDirectories.add(id)
                        pending.add(PendingDirectory(id, dir.depth + 1))
                        continue
                    }
                    if (!GbFolderImportPolicy.isRomFilename(name)) {
                        otherFiles++
                        continue
                    }
                    candidatesSeen++
                    if (candidatesSeen > MAX_ROM_CANDIDATES ||
                        existing.size >= MAX_GAMES ||
                        bytesProcessed >= MAX_TOTAL_ROM_BYTES) {
                        limited = true
                        break@mainLoop
                    }
                    if (!GbFolderImportPolicy.isSupportedSize(size)) {
                        invalid++
                        onProgress(candidatesSeen)
                        continue
                    }
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    val rom = try {
                        resolver.openInputStream(uri)?.use { input ->
                            readBounded(input, RomImportPolicy.maxRomBytes)
                        } ?: error("Impossible de lire la ROM.")
                    } catch (error: Exception) {
                        unreadable++
                        Log.w(TAG, "Unable to read candidate ROM", error)
                        onProgress(candidatesSeen)
                        continue
                    }
                    if (rom.isEmpty()) {
                        invalid++
                        onProgress(candidatesSeen)
                        continue
                    }
                    bytesProcessed += rom.size
                    if (bytesProcessed > MAX_TOTAL_ROM_BYTES) {
                        limited = true
                        break@mainLoop
                    }
                    val key = GbBatteryStore.keyForRom(rom)
                    if (key in existing) {
                        duplicate++
                        onProgress(candidatesSeen)
                        continue
                    }
                    try {
                        // The validation core is intentionally separate from the
                        // active game session. No battery/save state is loaded.
                        val system = NativeCore.systemFor(RomImportPolicy.platformOf(name, rom))
                        NativeCore(system).use { native ->
                            native.load(rom)
                        }
                    } catch (error: Exception) {
                        invalid++
                        Log.w(TAG, "Native core rejected a ROM", error)
                        onProgress(candidatesSeen)
                        continue
                    }
                    try {
                        library.add(name, rom)
                        existing.add(key)
                        added++
                    } catch (error: Exception) {
                        unreadable++
                        Log.e(TAG, "Unable to store validated ROM", error)
                        // Insufficient private storage should not repeatedly
                        // attempt further additions to the same full device.
                        limited = true
                        break@mainLoop
                    }
                    onProgress(candidatesSeen)
                }
            } finally {
                cursor.close()
            }
        }
        return Result(
            added = added,
            duplicate = duplicate,
            invalid = invalid,
            unreadable = unreadable,
            otherFiles = otherFiles,
            folders = folders,
            limited = limited,
        )
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val length = input.read(chunk)
            if (length < 0) break
            if (length == 0) continue
            require(length <= maxBytes - total) { "ROM supérieure à 32 Mio." }
            output.write(chunk, 0, length)
            total += length
        }
        return output.toByteArray()
    }

    companion object {
        private const val TAG = "RavenEmuFolder"
        const val MAX_ENTRIES = 4000
        const val MAX_DIRECTORIES = 128
        const val MAX_DEPTH = 8
        const val MAX_ROM_CANDIDATES = 256
        const val MAX_GAMES = 256
        const val MAX_TOTAL_ROM_BYTES = 256L * 1024L * 1024L
    }
}

/** Pure SAF candidate rules; covered by local JVM tests. */
internal object GbFolderImportPolicy {
    fun isRomFilename(name: String): Boolean = RomImportPolicy.acceptsRomFile(name)

    fun isSupportedSize(reportedBytes: Long?): Boolean =
        reportedBytes == null || reportedBytes in 1..RomImportPolicy.maxRomBytes.toLong()

    /** Current level starts at zero; accept children only up to depth eight. */
    fun mayDescend(currentDepth: Int, knownDirectories: Int): Boolean =
        currentDepth < GbFolderImporter.MAX_DEPTH &&
            knownDirectories < GbFolderImporter.MAX_DIRECTORIES
}
