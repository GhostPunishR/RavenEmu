package com.ravenemu.storage

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.ravenemu.deltaskin.DeltaSkinArchiveImporter
import com.ravenemu.deltaskin.DeltaSkinErrorCode
import com.ravenemu.deltaskin.DeltaSkinException
import com.ravenemu.deltaskin.DeltaSkinPdfInspector
import com.ravenemu.deltaskin.DeltaSkinPreparedImport
import com.ravenemu.deltaskin.DeltaSkinRepository
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Adaptateur Storage Access Framework. L'URI externe n'est utilisée que le
 * temps d'une copie bornée vers le cache privé ; l'installation conserve
 * ensuite sa propre archive.
 */
class AndroidDeltaSkinArchiveImporter(
    private val context: Context,
    pdfInspector: DeltaSkinPdfInspector,
) {
    val repositoryRoot: File =
        File(context.filesDir, "controller-skins/delta")

    val repository: DeltaSkinRepository =
        DeltaSkinRepository(repositoryRoot)

    private val importer = DeltaSkinArchiveImporter(repositoryRoot, pdfInspector)

    fun displayName(uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        val cursor: Cursor = context.contentResolver.query(
            uri,
            projection,
            null,
            null,
            null,
        ) ?: return uri.lastPathSegment
        cursor.use {
            if (!it.moveToFirst()) return uri.lastPathSegment
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            return if (index >= 0) it.getString(index) else uri.lastPathSegment
        }
    }

    fun prepare(uri: Uri): DeltaSkinPreparedImport {
        val name = try {
            displayName(uri).orEmpty()
        } catch (error: SecurityException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Le document sélectionné n'est pas accessible",
                error,
            )
        }
        if (!name.lowercase(Locale.ROOT).endsWith(".deltaskin")) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.INVALID_EXTENSION,
                "Le fichier doit porter l'extension .deltaskin",
            )
        }
        val sourceSize = try {
            querySize(uri)
        } catch (error: SecurityException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Le document sélectionné n'est pas accessible",
                error,
            )
        }
        sourceSize?.let { size ->
            if (size > DeltaSkinArchiveImporter.MAX_ARCHIVE_BYTES) {
                throw DeltaSkinException(
                    DeltaSkinErrorCode.ARCHIVE_TOO_LARGE,
                    "Le skin dépasse 25 Mio",
                )
            }
        }
        ensureAvailableSpace(DeltaSkinArchiveImporter.MAX_ARCHIVE_BYTES)

        val importCache = File(context.cacheDir, "delta-skin-import")
        if (!importCache.exists() && !importCache.mkdirs()) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Impossible de préparer le cache d'importation",
            )
        }
        val temporary = File(importCache, "${UUID.randomUUID()}.deltaskin")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw FileNotFoundException("URI sans flux")
            input.use { source ->
                temporary.outputStream().use { destination ->
                    val buffer = ByteArray(32 * 1024)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > DeltaSkinArchiveImporter.MAX_ARCHIVE_BYTES) {
                            throw DeltaSkinException(
                                DeltaSkinErrorCode.ARCHIVE_TOO_LARGE,
                                "Le skin dépasse 25 Mio",
                            )
                        }
                        destination.write(buffer, 0, read)
                    }
                }
            }
            return importer.prepare(temporary, name)
        } catch (error: DeltaSkinException) {
            throw error
        } catch (error: SecurityException) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_ERROR,
                "Le document sélectionné n'est pas accessible",
                error,
            )
        } catch (error: IOException) {
            val code = if (
                StatFs(context.filesDir.absolutePath).availableBytes <
                DeltaSkinArchiveImporter.MAX_ARCHIVE_BYTES
            ) {
                DeltaSkinErrorCode.STORAGE_INSUFFICIENT
            } else {
                DeltaSkinErrorCode.STORAGE_ERROR
            }
            throw DeltaSkinException(code, "Impossible de copier le skin", error)
        } finally {
            temporary.delete()
        }
    }

    private fun querySize(uri: Uri): Long? {
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        ) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val index = it.getColumnIndex(OpenableColumns.SIZE)
            return if (index >= 0 && !it.isNull(index)) it.getLong(index) else null
        }
    }

    private fun ensureAvailableSpace(requiredBytes: Long) {
        if (StatFs(context.filesDir.absolutePath).availableBytes < requiredBytes) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.STORAGE_INSUFFICIENT,
                "Le stockage disponible est insuffisant",
            )
        }
    }
}
