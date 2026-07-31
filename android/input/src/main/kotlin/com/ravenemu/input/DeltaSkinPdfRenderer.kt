package com.ravenemu.input

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.ravenemu.deltaskin.DeltaSkinErrorCode
import com.ravenemu.deltaskin.DeltaSkinException
import com.ravenemu.deltaskin.DeltaSkinPdfInfo
import com.ravenemu.deltaskin.DeltaSkinPdfInspector
import com.ravenemu.deltaskin.DeltaSkinRepresentationKind
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AndroidDeltaSkinPdfInspector : DeltaSkinPdfInspector {
    override fun inspect(file: File): DeltaSkinPdfInfo {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount <= 0) {
                        throw IOException("PDF sans page")
                    }
                    renderer.openPage(0).use { page ->
                        return DeltaSkinPdfInfo(
                            pageCount = renderer.pageCount,
                            width = page.width,
                            height = page.height,
                        )
                    }
                }
            }
        } catch (error: SecurityException) {
            throw IOException("PDF inaccessible", error)
        } catch (error: IllegalStateException) {
            throw IOException("PDF illisible", error)
        }
    }
}

/**
 * Cache mémoire borné partagé entre l'émulation et les miniatures. Un PDF
 * n'est ouvert et rasterisé qu'en cas de cache miss, toujours sur Dispatchers.IO.
 */
object DeltaSkinPdfRenderer {
    private val cache = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun render(
        file: File,
        skinSha256: String,
        kind: DeltaSkinRepresentationKind,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): Bitmap = withContext(Dispatchers.IO) {
        require(width > 0 && height > 0)
        val key = cacheKey(skinSha256, kind, width, height, densityDpi)
        cache.get(key)?.takeUnless { it.isRecycled }?.let { return@withContext it }

        val bitmap = renderPdf(file, width, height)
        cache.put(key, bitmap)
        bitmap
    }

    fun invalidate(skinSha256: String) {
        cache.snapshot().keys
            .filter { it.startsWith("$skinSha256|") }
            .forEach(cache::remove)
    }

    fun clear() {
        cache.evictAll()
    }

    private fun renderPdf(file: File, width: Int, height: Int): Bitmap {
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    if (renderer.pageCount != 1) {
                        throw DeltaSkinException(
                            DeltaSkinErrorCode.PDF_MULTIPLE_PAGES,
                            "Le PDF doit contenir exactement une page",
                        )
                    }
                    renderer.openPage(0).use { page ->
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.TRANSPARENT)
                        page.render(
                            bitmap,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        return bitmap
                    }
                }
            }
        } catch (error: DeltaSkinException) {
            throw error
        } catch (error: Exception) {
            throw DeltaSkinException(
                DeltaSkinErrorCode.PDF_INVALID,
                "Le PDF du skin est illisible",
                error,
            )
        }
    }

    private fun cacheKey(
        sha256: String,
        kind: DeltaSkinRepresentationKind,
        width: Int,
        height: Int,
        densityDpi: Int,
    ): String = "$sha256|${kind.name}|portrait|$width|$height|$densityDpi"

    private fun cacheSizeBytes(): Int {
        val suggested = (Runtime.getRuntime().maxMemory() / 16L)
            .coerceIn(8L * 1024L * 1024L, 32L * 1024L * 1024L)
        return suggested.toInt()
    }
}
