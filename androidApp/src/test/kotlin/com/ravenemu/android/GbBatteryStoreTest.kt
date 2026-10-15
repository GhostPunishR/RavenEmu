package com.ravenemu.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GbBatteryStoreTest {
    @Test
    fun sameRomBytesKeepTheSameSaveIdentity() {
        val rom = byteArrayOf(0x01, 0x02, 0x03, 0x7f)
        val a = GbBatteryStore.keyForRom(rom)
        val b = GbBatteryStore.keyForRom(rom.clone())
        assertEquals(a, b)
        assertEquals(64, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun distinctRomContentCannotShareAFileNameBasedSave() {
        val romA = byteArrayOf(0x00, 0x01, 0x02)
        val romB = byteArrayOf(0x00, 0x01, 0x03)
        assertNotEquals(GbBatteryStore.keyForRom(romA), GbBatteryStore.keyForRom(romB))
    }
}
