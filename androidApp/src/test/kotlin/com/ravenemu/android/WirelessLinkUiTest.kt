package com.ravenemu.android

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.ravenemu.shared.GameEntry
import com.ravenemu.shared.GamePlatform
import com.ravenemu.shared.RavenEmuApp
import com.ravenemu.shared.Settings
import com.ravenemu.shared.SettingsCapabilities
import com.ravenemu.shared.SettingsScreen
import com.ravenemu.shared.WirelessLinkChoice
import com.ravenemu.shared.WirelessLinkView
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WirelessLinkUiTest {
    @get:Rule val compose = createComposeRule()
    private val view = mutableStateOf(WirelessLinkView(ownLabel = "Pixel 6 · 81C3"))
    private val menuRequests = mutableStateOf(0)
    private val choices = mutableListOf<Long?>()
    private var unplugged = 0

    @Test
    fun `le bandeau du cable suit l'hote`() {
        openPlayer()
        compose.runOnIdle { view.value = view.value.copy(banner = "Câble relié à Pixel 7") }
        compose.onNodeWithText("Câble relié à Pixel 7").assertIsDisplayed()
        // WirelessLink le retire après quelques secondes.
        compose.runOnIdle { view.value = view.value.copy(banner = null) }
        compose.onNodeWithText("Câble relié à Pixel 7").assertDoesNotExist()
    }

    @Test
    fun `le jeu attend un partenaire en pause`() {
        openPlayer()
        compose.runOnIdle { view.value = view.value.copy(partnerPaused = true) }
        compose.onNodeWithText("Partenaire en pause").assertIsDisplayed()
        compose.runOnIdle { view.value = view.value.copy(partnerPaused = false) }
        compose.onNodeWithText("Partenaire en pause").assertDoesNotExist()
    }

    @Test
    fun `plusieurs telephones demandent un choix`() {
        openPlayer()
        compose.runOnIdle {
            view.value = view.value.copy(choices = listOf(
                WirelessLinkChoice(20, "Pixel 7 · 4F2A"),
                WirelessLinkChoice(30, "Galaxy S23 · 9B10"),
            ))
        }
        compose.onNodeWithText("Relier le câble à…").assertIsDisplayed()
        compose.onNodeWithText("Pixel 6 · 81C3", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Galaxy S23 · 9B10").performClick()
        compose.onNodeWithText("Plus tard").performClick()
        assertEquals(listOf<Long?>(30, null), choices)
    }

    @Test
    fun `le menu du jeu montre le partenaire et debranche`() {
        openPlayer()
        compose.runOnIdle { menuRequests.value++ }
        compose.onNodeWithText("Menu du jeu").assertIsDisplayed()
        compose.onNodeWithText("Débrancher").assertDoesNotExist()
        compose.runOnIdle { view.value = view.value.copy(partner = "Pixel 7 (Pokémon Bleu)") }
        compose.onNodeWithText("Pixel 7 (Pokémon Bleu)", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Débrancher").performClick()
        assertEquals(1, unplugged)
    }

    @Test
    fun `le reglage se trouve dans General, sauf sans cable sans fil`() {
        var settings = Settings()
        val capabilities = mutableStateOf(SettingsCapabilities())
        compose.setContent {
            SettingsScreen(settings = settings, capabilities = capabilities.value,
                onSettingsChange = { settings = it }, appVersion = "test", onOpenLink = null,
                aboutOnly = false, onBack = {})
        }
        compose.onNodeWithText("Général").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Câble link sans fil").performScrollTo().assertIsDisplayed()
        // Le premier interrupteur de la page Général est celui du câble.
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        assertFalse(settings.wirelessLink)
        compose.runOnIdle { capabilities.value = SettingsCapabilities(wirelessLink = false) }
        compose.onNodeWithText("Câble link sans fil").assertDoesNotExist()
    }

    private fun openPlayer() {
        compose.setContent {
            RavenEmuApp(
                libraryGames = listOf(GameEntry("test", "Jeu de test", GamePlatform.GB)),
                onLaunchLibraryGame = { _, ready -> ready() },
                onStartVideo = {},
                gameMenuRequests = menuRequests.value,
                wirelessLink = view.value,
                onWirelessLinkChoice = { choices += it },
                onWirelessUnplug = { unplugged++ },
            )
        }
        compose.onNodeWithText("Jeu de test").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Jouer").performClick()
        compose.waitForIdle()
    }
}
