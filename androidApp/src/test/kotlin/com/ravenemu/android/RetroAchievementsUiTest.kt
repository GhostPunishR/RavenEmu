package com.ravenemu.android

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import com.ravenemu.shared.RetroProgress
import com.ravenemu.shared.RetroSnapshot
import com.ravenemu.shared.Settings
import com.ravenemu.shared.AchievementEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import java.time.Duration
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
    private var nowMillis = 0L
    private val ui = RetroAchievementsUi(context, session, scope) { nowMillis }

    @After
    fun close() {
        scope.cancel()
        ui.retro.close()
        session.close()
    }

    @Test
    fun `la deconnexion efface la progression meme sans signal hide du SDK`() {
        showMeasuredProgress()
        ui.handleEvent(AchievementClient.Event("challenge_show", "Défi", "", "1", ""))
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
        val snapshot = showMeasuredProgress()
        ui.updateSnapshot(snapshot.copy(unlockedCount = 1))
        assertEquals(RetroProgress("Collection", "4/10", "2.png"), ui.retroProgress)
        ui.updateSnapshot(snapshot.copy(gameId = 2))
        assertNull(ui.retroProgress)
    }

    @Test
    fun `la reconnexion efface les indicateurs avant la reponse du serveur`() {
        showMeasuredProgress()
        ui.handleEvent(AchievementClient.Event("tracker_show", "Classement", "0:12", "3", ""))
        ui.updateSnapshot(RetroSnapshot(status = "Connexion en cours…", busy = true))
        assertNull(ui.retroProgress)
        assertTrue(ui.retroIndicators.isEmpty())
    }

    @Test
    fun `le succes prioritaire du SDK ne masque pas les autres progressions`() {
        val baseline = RetroSnapshot(username = "joueur", gameId = 1, entries = listOf(
            achievement("1", "Collection", "7/10", 70f),
            achievement("2", "Exploration", "1/10", 10f),
        ))
        publishBaseline(baseline)
        // As documented by rcheevos, only the achievement closest to completion
        // emits a popup event when both measurements change in the same frame.
        ui.handleEvent(AchievementClient.Event("progress_show", "Collection", "8/10", "1", "1.png"))
        val improved = baseline.copy(entries = listOf(
            achievement("1", "Collection", "8/10", 80f),
            achievement("2", "Exploration", "2/10", 20f),
        ))
        ui.updateSnapshot(improved)
        assertEquals("Collection", ui.retroProgress?.title)
        nowMillis = 2_000L
        ui.handleEvent(AchievementClient.Event("progress_show", "Collection", "9/10", "1", "1.png"))
        ui.updateSnapshot(improved.copy(entries = listOf(
            achievement("1", "Collection", "9/10", 90f),
            improved.entries[1],
        )))
        assertEquals(RetroProgress("Exploration", "2/10", "2.png"), ui.retroProgress)
    }

    @Test
    fun `le timer donne le tour au second succes puis ferme sans nouvel evenement`() {
        publishBaseline(RetroSnapshot(username = "joueur", gameId = 1, entries = listOf(
            achievement("1", "Collection", "7/10", 70f), achievement("2", "Exploration", "1/10", 10f),
        )))
        ui.updateSnapshot(ui.retroSnapshot.copy(entries = listOf(
            achievement("1", "Collection", "8/10", 80f), achievement("2", "Exploration", "2/10", 20f),
        )))
        val job = scope.launch { ui.showProgress() }
        ui.handleEvent(AchievementClient.Event("progress_hide", "", "", "", ""))
        nowMillis = 2_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertEquals("Exploration", ui.retroProgress?.title)
        nowMillis = 4_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertNull(ui.retroProgress)
        job.cancel()
    }

    @Test
    fun `quitter le lecteur et charger un etat ne rejouent pas une ancienne progression`() {
        val snapshot = showMeasuredProgress()
        ui.hideProgress()
        ui.updateSnapshot(snapshot)
        assertNull(ui.retroProgress)
        ui.handleEvent(AchievementClient.Event("progress_reset", "", "", "", ""))
        publishBaseline(snapshot)
        assertNull(ui.retroProgress)
        ui.updateSnapshot(snapshot.copy(entries = listOf(achievement("2", "Collection", "5/10", 50f))))
        assertEquals("5/10", ui.retroProgress?.detail)
    }

    @Test
    fun `desactiver la progression ne la rejoue pas a la reactivation`() {
        val snapshot = showMeasuredProgress()
        ui.configure(Settings(retroProgressPopups = false))
        assertNull(ui.retroProgress)
        val improved = snapshot.copy(entries = listOf(achievement("2", "Collection", "5/10", 50f)))
        ui.updateSnapshot(improved)
        assertNull(ui.retroProgress)
        ui.configure(Settings())
        ui.updateSnapshot(improved)
        assertNull(ui.retroProgress)
        ui.updateSnapshot(improved.copy(entries = listOf(achievement("2", "Collection", "6/10", 60f))))
        assertEquals("6/10", ui.retroProgress?.detail)
    }

    @Test
    fun `les preferences de notification sont conservees et normalisees`() {
        val store = AndroidSettingsStore(context)
        val chosen = Settings(retroUnlockPopups = false, retroProgressPopups = false,
            retroPopupSound = true, retroPopupPosition = "bottom", retroPopupSeconds = 8, retroProgressSeconds = 4)
        store.write(chosen)
        assertEquals(chosen, AndroidSettingsStore(context).read())
    }

    @Test
    fun `le son ne joue que pour les succes et peut etre coupe`() {
        var played = 0
        val soundUi = RetroAchievementsUi(context, session, scope, playSound = { played++ })
        try {
            soundUi.configure(Settings(retroPopupSound = true, retroPopupSeconds = 2))
            soundUi.handleEvent(AchievementClient.Event("completed", "Synthetic", "10/10", "", ""))
            val job = scope.launch { soundUi.showUnlocks() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, played)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            soundUi.configure(Settings(retroPopupSound = false))
            soundUi.handleEvent(AchievementClient.Event("achievement", "Second", "", "5", ""))
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, played)
            job.cancel()
        } finally { soundUi.retro.close() }
    }

    @Test
    fun `la duree choisie garde un tour fini pour chaque progression`() {
        ui.configure(Settings(retroProgressSeconds = 4))
        showMeasuredProgress()
        val job = scope.launch { ui.showProgress() }
        nowMillis = 2_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertTrue(ui.retroProgress != null)
        nowMillis = 4_000L
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        assertNull(ui.retroProgress)
        job.cancel()
    }

    @Test
    fun `les notifications de succes peuvent etre coupees sans masquer une erreur reseau`() {
        ui.configure(Settings(retroUnlockPopups = false))
        val job = scope.launch { ui.showUnlocks() }
        ui.handleEvent(AchievementClient.Event("achievement", "Victoire", "", "5", ""))
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(ui.retroNotice)
        ui.handleEvent(AchievementClient.Event("offline", "Hors ligne", "", "", ""))
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Hors ligne", ui.retroNotice)
        ui.configure(Settings(retroUnlockPopups = false))
        assertEquals("Hors ligne", ui.retroNotice)
        job.cancel()
    }

    @Test
    fun `changer de compte efface une notification sans casser sa coroutine`() {
        ui.updateSnapshot(RetroSnapshot(username = "first", gameId = 1))
        val job = scope.launch { ui.showUnlocks() }
        ui.handleEvent(AchievementClient.Event("completed", "Synthetic", "first · 10/10", "", ""))
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ui.retroNotice.orEmpty().contains("Synthetic"))
        ui.updateSnapshot(RetroSnapshot(username = "second", gameId = 1))
        assertNull(ui.retroNotice)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        ui.handleEvent(AchievementClient.Event("achievement", "Nouveau", "", "5", ""))
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ui.retroNotice.orEmpty().contains("Nouveau"))
        job.cancel()
    }

    private fun showMeasuredProgress(): RetroSnapshot {
        publishBaseline(RetroSnapshot(username = "joueur", gameId = 1,
            entries = listOf(achievement("2", "Collection", "3/10", 30f))))
        val snapshot = ui.retroSnapshot.copy(entries = listOf(achievement("2", "Collection", "4/10", 40f)))
        ui.updateSnapshot(snapshot)
        return snapshot
    }

    @Test
    fun `la premiere frame retablit une mesure existante sans popup ni perte du defi`() {
        val snapshot = RetroSnapshot(username = "joueur", gameId = 1,
            entries = listOf(achievement("2", "Collection", "", 0f)))
        ui.updateSnapshot(snapshot)
        ui.handleEvent(AchievementClient.Event("challenge_show", "Défi", "", "1", ""))
        val loaded = snapshot.copy(entries = listOf(achievement("2", "Collection", "8/10", 80f)))
        ui.updateSnapshot(loaded)
        assertNull(ui.retroProgress)
        ui.handleEvent(AchievementClient.Event("progress_baseline", "", "", "", ""))
        ui.updateSnapshot(loaded)
        assertNull(ui.retroProgress)
        assertEquals(listOf("Défi"), ui.retroIndicators)
        ui.updateSnapshot(loaded.copy(entries = listOf(achievement("2", "Collection", "9/10", 90f))))
        assertEquals("9/10", ui.retroProgress?.detail)
    }

    private fun publishBaseline(snapshot: RetroSnapshot) {
        ui.updateSnapshot(snapshot)
        ui.handleEvent(AchievementClient.Event("progress_baseline", "", "", "", ""))
        ui.updateSnapshot(snapshot)
    }

    private fun achievement(id: String, title: String, progress: String, percent: Float) =
        AchievementEntry(id, title, "", 5, 1, progress, 0L, 1, "$id.png", percent)
}
