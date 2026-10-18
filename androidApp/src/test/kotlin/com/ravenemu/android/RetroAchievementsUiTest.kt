package com.ravenemu.android

import android.os.Looper
import com.ravenemu.shared.RetroProgress
import com.ravenemu.shared.RetroSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RetroAchievementsUiTest {
    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = AndroidGbSession(context.contentResolver, context.filesDir)
    private val ui = RetroAchievementsUi(context, session, scope)

    @After
    fun close() {
        scope.cancel()
        ui.retro.close()
        session.close()
    }

    @Test
    fun `la deconnexion efface la progression meme sans signal hide du SDK`() {
        ui.updateSnapshot(RetroSnapshot(username = "joueur", gameId = 1))
        ui.handleEvent(AchievementClient.Event("challenge_show", "Défi", "", "1", ""))
        ui.handleEvent(AchievementClient.Event("progress_show", "Collection", "4/10", "2", ""))
        assertTrue(ui.retroIndicators.isNotEmpty())
        assertTrue(ui.retroProgress != null)
        // No native client is needed: disconnect must reset the UI even if
        // destroying a client emits no more SDK events.
        runBlocking { ui.retro.disconnect() }
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ui.retroProgress)
        assertTrue(ui.retroIndicators.isEmpty())
    }

    @Test
    fun `un rafraichissement conserve la progression mais un autre jeu la supprime`() {
        val snapshot = RetroSnapshot(username = "joueur", gameId = 1)
        ui.updateSnapshot(snapshot)
        ui.handleEvent(AchievementClient.Event("progress_show", "Collection", "4/10", "2", ""))
        ui.updateSnapshot(snapshot.copy(unlockedCount = 1))
        assertEquals(RetroProgress("Collection", "4/10", ""), ui.retroProgress)
        ui.updateSnapshot(snapshot.copy(gameId = 2))
        assertNull(ui.retroProgress)
    }

    @Test
    fun `la reconnexion efface les indicateurs avant la reponse du serveur`() {
        ui.updateSnapshot(RetroSnapshot(username = "joueur", gameId = 1))
        ui.handleEvent(AchievementClient.Event("tracker_show", "Classement", "0:12", "3", ""))
        ui.handleEvent(AchievementClient.Event("progress_show", "Collection", "4/10", "2", ""))
        ui.updateSnapshot(RetroSnapshot(status = "Connexion en cours…", busy = true))
        assertNull(ui.retroProgress)
        assertTrue(ui.retroIndicators.isEmpty())
    }
}
