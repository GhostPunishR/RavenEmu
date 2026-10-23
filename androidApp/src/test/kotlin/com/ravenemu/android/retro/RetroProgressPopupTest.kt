package com.ravenemu.android.retro

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import com.ravenemu.shared.library.GameEntry
import com.ravenemu.shared.library.GamePlatform
import com.ravenemu.shared.RavenEmuApp
import com.ravenemu.shared.retro.RetroBanner
import com.ravenemu.shared.retro.RetroProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h568dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RetroProgressPopupTest {
    @get:Rule val compose = createComposeRule()
    private val progress = mutableStateOf<RetroProgress?>(null)
    private val notice = mutableStateOf<RetroBanner?>(null)
    private val noticeBadge = mutableStateOf<ImageBitmap?>(null)
    private val images = mutableStateOf<Map<String, ImageBitmap>>(emptyMap())

    @Test
    fun `la valeur de progression reste visible avec un titre tres long`() {
        openPlayer()
        compose.runOnIdle {
            progress.value = RetroProgress("Un très long titre de succès qui occupe plusieurs lignes ".repeat(4), "9/10", "")
        }
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("9/10", substring = true, useUnmergedTree = true)
            .assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val layout = results.single()
        assertFalse(layout.isLineEllipsized(layout.lineCount - 1), "La valeur de progression doit rester lisible.")
        assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
    }

    @Test
    fun `la progression se met a jour puis disparait au signal du SDK`() {
        openPlayer()
        compose.runOnIdle { progress.value = RetroProgress("Premier succès", "1/10", "") }
        compose.onNodeWithText("1/10", substring = true).assertIsDisplayed()
        compose.runOnIdle { progress.value = RetroProgress("Second succès", "2/20", "") }
        compose.onNodeWithText("Premier succès", substring = true).assertDoesNotExist()
        compose.onNodeWithText("2/20", substring = true).assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { progress.value = null }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("2/20", substring = true).assertExists()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("2/20", substring = true).assertDoesNotExist()
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { progress.value = RetroProgress("Troisième succès", "3/30", "") }
        compose.onNodeWithText("3/30", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Second succès", substring = true).assertDoesNotExist()
    }

    @Test
    fun `un succes debloque ne remplace pas la progression en cours`() {
        openPlayer()
        compose.runOnIdle {
            progress.value = RetroProgress("Collection", "4/10", "")
            notice.value = RetroBanner("Succès débloqué · 5 points", "Victoire", award = true)
        }
        compose.onNodeWithText("4/10", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Succès débloqué · 5 points").assertIsDisplayed()
        compose.onNodeWithText("Victoire").assertIsDisplayed()
        compose.runOnIdle { notice.value = null }
        compose.onNodeWithText("Victoire").assertDoesNotExist()
        compose.onNodeWithText("4/10", substring = true).assertIsDisplayed()
    }

    @Test
    fun `le bandeau d'un jeu qui demarre montre son icone, sinon l'etoile`() {
        openPlayer()
        compose.runOnIdle { notice.value = RetroBanner("12 succès disponibles", "Jeu de test - Version Démo") }
        compose.onNodeWithText("12 succès disponibles").assertIsDisplayed()
        compose.onNodeWithText("Jeu de test - Version Démo").assertIsDisplayed()
        compose.onNodeWithText("★").assertIsDisplayed()
        compose.runOnIdle { noticeBadge.value = ImageBitmap(64, 64) }
        compose.onNodeWithContentDescription("Badge du bandeau").assertIsDisplayed()
        compose.onNodeWithText("★").assertDoesNotExist()
    }

    @Test
    fun `le popup ne bloque pas les touchers du lecteur en mode manette`() {
        openPlayer(controllerMode = true)
        compose.runOnIdle { progress.value = RetroProgress("Collection", "4/10", "") }
        compose.onNodeWithText("4/10", substring = true).assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Menu du jeu").assertIsDisplayed()
    }

    @Test
    fun `le popup sur un skin remplace le badge par une etoile puis affiche le nouveau badge`() {
        openPlayer(skin = true)
        compose.runOnIdle {
            images.value = mapOf("premier.png" to ImageBitmap(40, 40))
            progress.value = RetroProgress("Premier succès", "1/10", "premier.png")
        }
        compose.onNodeWithContentDescription("Badge du succès").assertIsDisplayed()
        compose.onNodeWithText("★").assertDoesNotExist()
        compose.runOnIdle { progress.value = RetroProgress("Second succès", "2/20", "second.png") }
        compose.onNodeWithContentDescription("Badge du succès").assertDoesNotExist()
        compose.onNodeWithText("★").assertIsDisplayed()
        compose.onNodeWithText("2/20", substring = true).assertIsDisplayed()
        compose.runOnIdle { images.value = images.value + ("second.png" to ImageBitmap(40, 40)) }
        compose.onNodeWithContentDescription("Badge du succès").assertIsDisplayed()
        compose.onNodeWithText("★").assertDoesNotExist()
    }

    private fun openPlayer(controllerMode: Boolean = false, skin: Boolean = false) {
        compose.setContent {
            RavenEmuApp(
                libraryGames = listOf(GameEntry("test", "Jeu de test", GamePlatform.GB)),
                onLaunchLibraryGame = { _, ready -> ready() },
                onStartVideo = {},
                controllerMode = controllerMode,
                retroProgress = progress.value,
                retroNotice = notice.value,
                retroNoticeBadge = noticeBadge.value,
                retroImages = images.value,
                skinControls = if (skin) ({ _, _ -> Box(Modifier.fillMaxSize()) }) else null,
            )
        }
        compose.onNodeWithText("Jeu de test").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Jouer").performClick()
        compose.waitForIdle()
    }
}
