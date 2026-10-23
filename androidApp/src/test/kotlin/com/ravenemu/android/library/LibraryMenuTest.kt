package com.ravenemu.android.library

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ravenemu.shared.library.GameEntry
import com.ravenemu.shared.library.GamePlatform
import com.ravenemu.shared.RavenEmuApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `le menu de la bibliotheque actualise et exporte toutes les sauvegardes, sans A propos`() {
        var refreshes = 0
        var exports = 0
        compose.setContent {
            RavenEmuApp(
                libraryGames = listOf(GameEntry("test", "Jeu de test", GamePlatform.GB)),
                onRefreshLibrary = { refreshes++ },
                onExportAllSaves = { exports++ },
            )
        }
        compose.onNodeWithContentDescription("Plus d'options").performClick()
        compose.onNodeWithText("À propos").assertDoesNotExist()
        compose.onNodeWithText("Actualiser la bibliothèque de jeux").performClick()
        compose.runOnIdle { assertEquals(1, refreshes) }
        compose.onNodeWithContentDescription("Plus d'options").performClick()
        compose.onNodeWithText("Exporter toutes les sauvegardes (.zip)").performClick()
        compose.runOnIdle { assertEquals(1, exports) }
    }

    @Test
    fun `la fiche d'un jeu n'exporte plus l'archive de toutes les sauvegardes`() {
        compose.setContent {
            RavenEmuApp(
                libraryGames = listOf(GameEntry("test", "Jeu de test", GamePlatform.GB)),
                onExportBatterySave = { _, _ -> },
                onImportGameSave = {},
                onExportAllSaves = {},
            )
        }
        compose.onNodeWithText("Jeu de test").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Exporter").assertExists()
        compose.onNodeWithText("Importer").assertExists()
        compose.onNodeWithText("Exporter toutes les sauvegardes (.zip)").assertDoesNotExist()
    }
}
