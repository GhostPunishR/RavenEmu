package com.ravenemu.storage

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatterySaveReaderTest {
    @Test
    fun `une sauvegarde MBC6 complete est acceptee sans modification`() {
        val mbc6 = ByteArray(32 * 1024 + 1024 * 1024 + 256 + 8) { (it % 251).toByte() }
        assertContentEquals(mbc6, BatterySaveReader.read(ByteArrayInputStream(mbc6)))
    }

    @Test
    fun `un flux sans fin est rejete apres la limite plus un octet`() {
        var consumed = 0
        val source = object : InputStream() {
            override fun read(): Int { consumed++; return 0 }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                consumed += length
                buffer.fill(0, offset, offset + length)
                return length
            }
        }
        assertNull(BatterySaveReader.read(source))
        assertEquals(BatterySaveReader.MAX_SIZE + 1, consumed)
    }

    @Test
    fun `les lectures partielles conservent une sauvegarde classique et le pied RTC`() {
        val original = ByteArray(128 * 1024 + 48) { it.toByte() }
        val source = object : ByteArrayInputStream(original) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 7))
        }
        assertContentEquals(original, BatterySaveReader.read(source))
        assertContentEquals(byteArrayOf(), BatterySaveReader.read(ByteArrayInputStream(byteArrayOf())))
    }
}
