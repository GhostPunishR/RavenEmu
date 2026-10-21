package com.ravenemu.android

import java.io.File
import java.nio.file.Files
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HardcoreStateLockTest {
    private val filesDir: File = Files.createTempDirectory("ravenemu-hardcore-lock").toFile()
    private val session = AndroidGbSession(RuntimeEnvironment.getApplication().contentResolver, filesDir)

    @After fun clean() {
        session.close()
        filesDir.deleteRecursively()
    }

    @Test fun `la session refuse elle-meme un etat tant que le hardcore est demande`() {
        session.stateLoadLocked = true
        val refused = assertFailsWith<IllegalStateException> { session.loadStateSlot(0) }
        assertTrue("Hardcore" in refused.message.orEmpty(), refused.message)
        // Sans le verrou, la même demande va jusqu'au jeu (absent ici).
        session.stateLoadLocked = false
        val unlocked = assertFailsWith<IllegalStateException> { session.loadStateSlot(0) }
        assertTrue("Hardcore" !in unlocked.message.orEmpty(), unlocked.message)
    }
}
