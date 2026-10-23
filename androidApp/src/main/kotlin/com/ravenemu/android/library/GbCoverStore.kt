package com.ravenemu.android.library

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.math.min

/**
 * Optional user-selected box art. Each game cover lives in app-private files
 * under the same SHA-256 as the ROM; it has no effect on SRAM, states or ROMs.
 * Stored thumbnails are bounded JPEGs, never full-size external photographs.
 *
 * Call disk/decode functions on Dispatchers.IO.
 */
internal class GbCoverStore(
    private val directory: File,
    private val resolver: ContentResolver,
) {
    fun save(id: String, uri: Uri): Bitmap {
        val image = resolver.openInputStream(uri)?.use { input ->
            readBounded(input, MAX_SOURCE_BYTES)
        } ?: error("Impossible d'ouvrir cette image.")
        return saveBytes(id, image)
    }

    /** Auto-downloaded PNGs are subject to the same bounds as manual images. */
    fun saveDownloadedIfMissing(id: String, image: ByteArray): Bitmap? = synchronized(this) {
        // Manual covers have priority, including changes while a network
        // request is in flight.
        if (read(id) != null) return@synchronized null
        saveBytes(id, image)
    }

    private fun saveBytes(id: String, image: ByteArray): Bitmap {
        require(image.isNotEmpty() && image.size <= MAX_SOURCE_BYTES) {
            "Image trop volumineuse."
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        require(bounds.outWidth in 1..MAX_SOURCE_DIMENSION &&
            bounds.outHeight in 1..MAX_SOURCE_DIMENSION) {
            "Image invalide ou dimensions trop importantes."
        }
        var sample = 1
        while (bounds.outWidth / sample > 900 || bounds.outHeight / sample > 900) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            image, 0, image.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: error("Ce format d'image n'est pas pris en charge.")

        try {
            val ratio = min(1f, min(360f / decoded.width, 500f / decoded.height))
            val width = (decoded.width * ratio).toInt().coerceAtLeast(1)
            val height = (decoded.height * ratio).toInt().coerceAtLeast(1)
            val scaled = if (decoded.width == width && decoded.height == height) decoded
                else Bitmap.createScaledBitmap(decoded, width, height, true)
            try {
                val jpeg = ByteArrayOutputStream().use { buffer ->
                    check(scaled.compress(Bitmap.CompressFormat.JPEG, 88, buffer)) {
                        "Impossible de préparer la jaquette."
                    }
                    buffer.toByteArray()
                }
                require(jpeg.isNotEmpty() && jpeg.size <= MAX_COVER_BYTES) {
                    "La jaquette dépasse la limite autorisée."
                }
                check(directory.isDirectory || directory.mkdirs()) {
                    "Impossible de créer le dossier des jaquettes."
                }
                val atomic = AtomicFile(fileFor(id))
                val stream = atomic.startWrite()
                try {
                    stream.write(jpeg)
                    atomic.finishWrite(stream)
                } catch (error: Exception) {
                    atomic.failWrite(stream)
                    throw error
                }
                // Return an independent bitmap: the intermediate can be recycled.
                return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: error("Impossible de lire la jaquette enregistrée.")
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    fun read(id: String): Bitmap? {
        val jpeg = try {
            AtomicFile(fileFor(id)).openRead().use {
                readBounded(it, MAX_COVER_BYTES)
            }
        } catch (_: FileNotFoundException) {
            return null
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    fun delete(id: String) {
        AtomicFile(fileFor(id)).delete()
    }

    internal fun fileFor(id: String): File = coverFileFor(directory, id)

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val size = input.read(chunk)
            if (size < 0) break
            if (size == 0) continue
            require(size <= limit - total) {
                "Image trop volumineuse."
            }
            result.write(chunk, 0, size)
            total += size
        }
        require(total > 0) { "Image vide." }
        return result.toByteArray()
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 8 * 1024 * 1024
        const val MAX_COVER_BYTES = 1024 * 1024
        const val MAX_SOURCE_DIMENSION = 16384
    }
}

/** Shared by disk writes and path-traversal unit tests. */
internal fun coverFileFor(directory: File, id: String): File {
    require(id.length == 64 && id.all { it in '0'..'9' || it in 'a'..'f' }) {
        "Identifiant de ROM invalide."
    }
    return File(directory, "$id.jpg")
}
