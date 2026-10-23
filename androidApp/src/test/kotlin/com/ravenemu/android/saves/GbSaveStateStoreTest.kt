package com.ravenemu.android.saves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class GbSaveStateStoreTest {
    private val store = GbSaveStateStore(File("test-states"))

    @Test
    fun sameRomUsesSameSlotAndDifferentRomsStaySeparate() {
        val first = GbBatteryStore.keyForRom(byteArrayOf(1, 2, 3))
        val other = GbBatteryStore.keyForRom(byteArrayOf(1, 2, 4))
        assertEquals(store.fileFor(first), store.fileFor(first))
        assertNotEquals(store.fileFor(first), store.fileFor(other))
        assertEquals("$first.slot0.state", store.fileFor(first).name)
    }

    @Test
    fun slotZeroKeepsLegacyPathAndOtherSlotsAreIndependent() {
        val rom = GbBatteryStore.keyForRom(byteArrayOf(12, 13))
        val paths = (0 until GbSaveStateStore.SLOT_COUNT)
            .map { store.fileFor(rom, it) }
        assertEquals(4, paths.distinct().size)
        assertEquals("$rom.slot0.state", paths[0].name)
        assertEquals("$rom.slot1.state", paths[1].name)
        assertEquals("$rom.slot3.state", paths[3].name)
        assertEquals("$rom.slot0.png", store.previewFileFor(rom, 0).name)
        assertEquals("$rom.slot3.png", store.previewFileFor(rom, 3).name)
    }

    @Test
    fun slotOutsideBoundsIsRejectedForStateAndThumbnail() {
        val key = GbBatteryStore.keyForRom(byteArrayOf(88))
        for (slot in listOf(-1, 4, 99)) {
            assertThrows(IllegalArgumentException::class.java) {
                store.fileFor(key, slot)
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.previewFileFor(key, slot)
            }
        }
    }

    @Test
    fun invalidRomKeysCannotEscapeSaveDirectory() {
        assertThrows(IllegalArgumentException::class.java) {
            store.fileFor("../escape")
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.fileFor("a".repeat(63) + "/")
        }
    }
}
