package com.ravenemu.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GbCoverStoreTest {
    @Test
    fun coverFilenameUsesCartridgeShaAndIsIndependentPerRom() {
        val first = GbBatteryStore.keyForRom(byteArrayOf(1, 2, 3))
        val second = GbBatteryStore.keyForRom(byteArrayOf(1, 2, 4))
        val folder = File("test-covers")
        assertEquals("$first.jpg", coverFileFor(folder, first).name)
        assertNotEquals(coverFileFor(folder, first), coverFileFor(folder, second))
    }

    @Test
    fun invalidKeysCannotEscapeCoverDirectory() {
        val folder = File("test-covers")
        for (key in listOf("../escape", "a".repeat(63) + "/", "A".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) {
                coverFileFor(folder, key)
            }
        }
    }
}
