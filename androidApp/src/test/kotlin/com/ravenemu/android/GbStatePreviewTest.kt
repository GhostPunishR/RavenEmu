package com.ravenemu.android

import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Assert.*
import org.junit.Test

class GbStatePreviewTest {
    @Test fun nativePreviewKeepsItsOriginalResolution() {
        val size = requireNotNull(GbStatePreview.size(header(240, 160)))
        assertEquals(GbStatePreview.Size(240, 160), size)
        assertEquals(1, GbStatePreview.sampleSize(size))
        assertNotNull(GbStatePreview.size(png))
    }

    @Test fun oversizedOrOverflowingDimensionsNeverReachTheDecoder() {
        for ((width, height) in listOf(10000 to 10000, Int.MAX_VALUE to Int.MAX_VALUE,
            0 to 160, 240 to -1, 1025 to 1)) {
            assertNull("$width x $height", GbStatePreview.size(header(width, height)))
        }
    }

    @Test fun invalidOrTruncatedHeadersAreIgnored() {
        assertNull(GbStatePreview.size(byteArrayOf(1, 2)))
        assertNull(GbStatePreview.size(png.copyOf(32)))
        assertNull(GbStatePreview.size(header(240, 160).also { it[29] = (it[29].toInt() xor 1).toByte() }))
        assertNull(GbStatePreview.size(header(240, 160).also { it[0] = 0 }))
    }

    @Test fun largerAllowedImagesAreSampledDownToThumbnailSize() {
        val size = requireNotNull(GbStatePreview.size(header(1024, 1024)))
        val sample = GbStatePreview.sampleSize(size)
        assertTrue(size.width / sample <= 240)
        assertTrue(size.height / sample <= 160)
    }

    companion object {
        val png: ByteArray get() = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2ioAAAAASUVORK5CYII=")

        fun header(width: Int, height: Int): ByteArray {
            val bytes = png.copyOf(33)
            ByteBuffer.wrap(bytes).apply { putInt(16, width); putInt(20, height) }
            val crc = CRC32().apply { update(bytes, 12, 17) }.value
            ByteBuffer.wrap(bytes).putInt(29, crc.toInt())
            return bytes
        }
    }
}
