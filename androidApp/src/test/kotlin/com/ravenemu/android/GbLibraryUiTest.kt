package com.ravenemu.android

import android.os.Looper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GbLibraryUiTest {
    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = AndroidGbSession(context.contentResolver, context.filesDir)
    private val linkSession = AndroidLinkSession(context.filesDir)
    private val playback = GbPlayback(context, session)
    private val retroUi = RetroAchievementsUi(context, session, scope)
    private val romLibrary = GbRomLibrary(File(context.filesDir, "gb-library"), context.contentResolver)
    private val operations = LibraryOperationGate()
    private val player = GbPlayerUi(
        session, playback, linkSession, LinkPlayback(context, linkSession),
        GbStateSlotsUi(context, session, playback, scope), retroUi.retro, scope,
    ) { true }
    private val library = GbLibraryUi(
        context, context.contentResolver, romLibrary,
        GbCoversUi(context, context.filesDir, context.contentResolver, romLibrary, scope),
        session, linkSession, retroUi.retro, player, operations, scope,
    )

    @After
    fun close() {
        scope.cancel()
        retroUi.retro.close()
        session.close()
        linkSession.close()
    }

    /** Lets the disk work finish and its result reach the main thread. */
    private fun settle() {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!library.loading) return
            Thread.sleep(5)
        }
        error("La bibliothèque reste occupée")
    }

    @Test
    fun `un jeu sorti de la bibliotheque signale l'erreur et libere la bibliotheque`() {
        var ready = false
        library.launch("absent") { ready = true }
        settle()
        assertFalse(ready)
        assertEquals("Ce jeu n'est plus dans la bibliothèque.", library.importError)
        assertFalse(operations.isBusy())
    }

    @Test
    fun `l'actualisation attend l'operation en cours puis affiche sa progression`() {
        val transfer = operations.tryAcquire()!!
        library.refresh()
        assertFalse(library.loading)
        assertNull(library.folderImportProgress)

        transfer.close()
        library.refresh()
        assertTrue(library.loading)
        assertEquals("Actualisation de la bibliothèque…", library.folderImportProgress)
        settle()
        assertNull(library.folderImportProgress)
        assertNull(library.importError)
        assertFalse(operations.isBusy())
        assertEquals("Bibliothèque actualisée : 0 jeu", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `rien ne demarre dans la bibliotheque pendant une partie reliee`() {
        player.startLinked()
        assertTrue(player.open)
        library.refresh()
        library.launch("absent") {}
        library.launchLinked("a", "b") {}
        assertFalse(library.loading)
        assertNull(library.importError)
        assertFalse(operations.isBusy())
    }
}
