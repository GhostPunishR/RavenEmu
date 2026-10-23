package com.ravenemu.android.retro

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.ravenemu.shared.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetroDashboardUiTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(RetroSnapshot(username = "SyntheticUser", gameId = 1,
        gameTitle = "Synthetic", richPresence = "Explore la zone 2", pendingAwards = 1,
        entries = listOf(
            AchievementEntry("1", "Collection", "Trouver dix objets", 5, 2, "", 1700000000, 1, "",
                sync = RetroSync.PENDING, unlockedDate = "14/11/2023 22:13"),
            AchievementEntry("2", "Exploration", "Découvrir le monde", 5, 1, "2/10", 0, 1, "", 20f)),
        leaderboards = listOf(RetroLeaderboard(7, "Course synthétique", "Terminer rapidement", "", 0, true))))

    @Test fun `le tableau affiche activite synchro date et recherche a petite largeur`() {
        open()
        compose.onNodeWithText("Explore la zone 2").assertIsDisplayed()
        compose.onNodeWithText("En attente d'envoi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("14/11/2023 22:13").assertIsDisplayed()
        compose.onNodeWithText("Rechercher un succès").performScrollTo().performTextInput("explor")
        compose.onNodeWithText("Collection").assertDoesNotExist()
        compose.onNodeWithText("Exploration").performScrollTo().assertIsDisplayed()
    }

    @Test fun `les classements demandent le bon tableau et affichent le record et le rang`() {
        val calls = mutableListOf<Pair<Int, Boolean>>()
        open { id, around -> calls += id to around }
        compose.onNodeWithText("Course synthétique").performScrollTo().performTouchInput { click() }
        assertEquals(listOf(7 to false), calls)
        compose.runOnIdle {
            state.value = state.value.copy(leaderboardView = RetroLeaderboardView(7, total = 50,
                entries = listOf(RetroLeaderboardEntry(3, "SyntheticUser", "0:12.34", "14/11/2023", true))),
                scoreboard = RetroScoreboard(7, "Course synthétique", "0:12.34", "0:11.80", 3, 50))
        }
        compose.onNodeWithText("Score : 0:12.34\nRecord : 0:11.80\nRang : 3/50").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("#3 · SyntheticUser (vous)\n0:12.34").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Autour de moi").performScrollTo().performTouchInput { click() }
        assertEquals(7 to true, calls.last())
    }

    private fun open(onFetch: (Int, Boolean) -> Unit = { _, _ -> }) {
        compose.setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                RetroAchievementsContent(state.value, loginBusy = false, onLogin = null,
                    onDisconnect = null, onRetry = null, onOpenLink = {}, onLeaderboard = onFetch)
            }
        }
    }
}
