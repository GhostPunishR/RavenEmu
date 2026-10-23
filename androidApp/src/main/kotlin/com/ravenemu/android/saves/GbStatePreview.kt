package com.ravenemu.android.saves

import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Validate PNG dimensions before a decoder can allocate its pixel buffer. */
internal object GbStatePreview {
    data class Size(val width: Int, val height: Int)

    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private const val MAX_DIMENSION = 1024
    private const val MAX_PIXELS = 1024L * 1024L

    fun size(bytes: ByteArray): Size? {
        if (bytes.size < 33 || !bytes.copyOfRange(0, 8).contentEquals(signature)) return null
        val header = ByteBuffer.wrap(bytes)
        if (header.getInt(8) != 13 || header.getInt(12) != 0x49484452) return null // IHDR
        val crc = CRC32().apply { update(bytes, 12, 17) }.value
        if (crc != (header.getInt(29).toLong() and 0xffff_ffffL)) return null
        val width = header.getInt(16)
        val height = header.getInt(20)
        if (width !in 1..MAX_DIMENSION || height !in 1..MAX_DIMENSION ||
            width.toLong() * height > MAX_PIXELS) return null
        return Size(width, height)
    }

    fun sampleSize(size: Size): Int {
        var sample = 1
        while (size.width / sample > 240 || size.height / sample > 160) sample *= 2
        return sample
    }
}
