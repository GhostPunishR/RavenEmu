package com.ravenemu.android.retro

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ravenemu.shared.GameEntry
import com.ravenemu.shared.GamePlatform
import com.ravenemu.shared.RavenEmuApp
import com.ravenemu.shared.RetroSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h568dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetroAccountCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `le menu en jeu montre le compte et ouvre RetroAchievements`() {
        openGameMenu(RetroSnapshot(
            status = "Connecté • Mode normal (Softcore)", username = "joueur", displayName = "Joueur",
            softcorePoints = 1200, hardcorePoints = 40, gameTitle = "Jeu", gameId = 7,
            unlockedCount = 3, totalCount = 20, unlockedPoints = 25, totalPoints = 200,
        ))
        compose.onNodeWithText("Joueur").assertIsDisplayed()
        compose.onNodeWithText("Softcore 1200 pts  ·  Hardcore 40 pts").assertIsDisplayed()
        compose.onNodeWithText("3/20 succès  ·  25/200 pts").assertIsDisplayed()
        compose.onNodeWithText("Joueur").performClick()
        compose.onNodeWithText("‹ Retour au menu du jeu").assertIsDisplayed()
    }

    @Test
    fun `hors connexion le menu en jeu garde la raison du refus`() {
        val refused = "Connexion refusée par RetroAchievements : mot de passe incorrect."
        openGameMenu(RetroSnapshot(status = refused))
        compose.onNodeWithText("Non connecté").assertIsDisplayed()
        compose.onNodeWithText(refused).assertIsDisplayed()
    }

    private fun openGameMenu(snapshot: RetroSnapshot) {
        compose.setContent {
            RavenEmuApp(
                libraryGames = listOf(GameEntry("test", "Jeu de test", GamePlatform.GB)),
                onLaunchLibraryGame = { _, ready -> ready() },
                onStartVideo = {},
                controllerMode = true,
                retroSnapshot = snapshot,
                onRetroLogin = { _, _ -> },
            )
        }
        compose.onNodeWithText("Jeu de test").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Jouer").performClick()
        compose.onNodeWithText("Préparation de l'image du jeu...").performClick()
        compose.waitForIdle()
    }
}
