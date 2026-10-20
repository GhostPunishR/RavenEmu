package com.ravenemu.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.ravenemu.shared.Settings
import com.ravenemu.shared.SettingsCapabilities
import com.ravenemu.shared.SettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsCapabilitiesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `les services non raccordes sont masques mais les commandes restent reglables`() {
        open(SettingsCapabilities(onlineCovers = false, cartridgeHardware = false, deltaSkins = false,
            haptics = false, diagnostics = false, audioStartupFade = false))
        compose.onNodeWithText("Interface").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Taille des commandes").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retour haptique").assertDoesNotExist()
        compose.onNodeWithText("Vibration de la cartouche").assertDoesNotExist()
        compose.onNodeWithText("Capteurs de la cartouche").assertDoesNotExist()
        compose.onNodeWithText("Skin Delta").assertDoesNotExist()
    }

    @Test fun `les capacites par defaut conservent les options Android`() {
        open(SettingsCapabilities())
        compose.onNodeWithText("Interface").performScrollTo().performTouchInput { click() }
        compose.onNodeWithText("Retour haptique").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Vibration de la cartouche").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skin Delta").performScrollTo().assertIsDisplayed()
    }

    private fun open(capabilities: SettingsCapabilities) {
        compose.setContent { SettingsScreen(settings = Settings(), capabilities = capabilities,
            onSettingsChange = {}, appVersion = "test", onOpenLink = null, aboutOnly = false, onBack = {}) }
    }
}
