package com.ravenemu.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.ravenemu.shared.GameEntry
import com.ravenemu.shared.GamePlatform
import com.ravenemu.shared.RavenEmuApp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameDetailsTouchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `la poignee suit le doigt meme pendant le depliage`() {
        openDetails(linkAvailable = true, onExport = {}, expanded = false)
        interruptExpansionWithDrag()
    }

    @Test
    fun `le menu en jeu permet aussi de reprendre le depliage`() {
        openDetails(expanded = false, controllerMode = true)
        compose.onNodeWithText("Jouer").performClick()
        compose.onNodeWithText("Préparation de l'image du jeu...").performClick()
        compose.waitForIdle()
        interruptExpansionWithDrag()
    }

    @Test
    fun `la poignee permet de deplier puis replier le panneau`() {
        openDetails(linkAvailable = true, onExport = {}, expanded = false)
        val handle = compose.onNodeWithContentDescription("Drag handle").assertIsDisplayed()
        val initial = handle.fetchSemanticsNode().boundsInRoot.top
        handle.performTouchInput {
            down(center)
            moveTo(center - Offset(0f, 600f), delayMillis = 400)
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        val expanded = handle.fetchSemanticsNode().boundsInRoot.top
        assertTrue(expanded < initial - 100f, "Le panneau doit suivre le glissement vers le haut.")
        handle.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, initial - expanded), delayMillis = 400)
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        assertEquals(initial, handle.fetchSemanticsNode().boundsInRoot.top, 2f)
    }

    @Test
    fun `un glissement vers le bas ferme le panneau`() {
        openDetails(linkAvailable = true, onExport = {}, expanded = false)
        compose.onNodeWithContentDescription("Drag handle").assertIsDisplayed().performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 600f), delayMillis = 300)
            up()
        }
        compose.onNodeWithText("Jouer").assertDoesNotExist()
    }

    @Test
    fun `le premier toucher sur Jouer apres stabilisation du panneau lance le jeu`() {
        var starts = 0
        openDetails(onStart = { starts++ })
        lightlyDragPanel()
        compose.onNodeWithText("Jouer").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun `le premier toucher sur Jouer a deux apres stabilisation ouvre le partenaire`() {
        openDetails(linkAvailable = true)
        lightlyDragPanel()
        compose.onNodeWithText("Jouer à deux (câble link)")
            .assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Jouer à deux").assertIsDisplayed()
        compose.onNodeWithText("Second jeu · Game Boy").assertIsDisplayed()
    }

    @Test
    fun `le premier toucher sur Exporter apres stabilisation appelle une seule fois le transfert`() {
        val exported = mutableListOf<String>()
        openDetails(onExport = { exported.add(it) })
        lightlyDragPanel()
        compose.onNodeWithText("Exporter toutes les sauvegardes (.zip)")
            .assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("test"), exported) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h568dp-mdpi")
    fun `le contenu defile sur petit ecran et Fermer ferme au premier toucher`() {
        openDetails(linkAvailable = true, onExport = {})
        compose.onNodeWithText("Fermer").performScrollTo()
            .assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Jouer").assertDoesNotExist()
    }

    private fun openDetails(
        onStart: () -> Unit = {},
        linkAvailable: Boolean = false,
        onExport: ((String) -> Unit)? = null,
        expanded: Boolean = true,
        controllerMode: Boolean = false,
    ) {
        compose.setContent {
            RavenEmuApp(
                libraryGames = listOf(
                    GameEntry("test", "Jeu de test", GamePlatform.GB),
                    GameEntry("second", "Second jeu", GamePlatform.GB),
                ),
                onStartVideo = onStart,
                onLaunchLibraryGame = { _, ready -> ready() },
                onStartLinked = if (linkAvailable) ({}) else null,
                onLaunchLinkedGames = if (linkAvailable) ({ _, _, ready -> ready() }) else null,
                onExportGameBackup = onExport,
                controllerMode = controllerMode,
            )
        }
        compose.onNodeWithText("Jeu de test").performClick()
        compose.waitForIdle()
        if (expanded) {
            compose.onNodeWithContentDescription("Drag handle").performClick()
            compose.waitForIdle()
        }
        compose.onNodeWithText("Jouer").assertIsDisplayed()
    }

    private fun interruptExpansionWithDrag() {
        val handle = compose.onNodeWithContentDescription("Drag handle").assertIsDisplayed()
        val initial = handle.fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        handle.performClick()
        compose.mainClock.advanceTimeBy(100)
        val duringExpansion = handle.fetchSemanticsNode().boundsInRoot.top
        assertTrue(duringExpansion < initial - 1f, "Le test doit reprendre un dépliage déjà commencé.")
        handle.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 180f), delayMillis = 200)
        }
        compose.mainClock.advanceTimeByFrame()
        val dragged = handle.fetchSemanticsNode().boundsInRoot.top
        assertTrue(dragged > duringExpansion + 80f,
            "Le panneau doit suivre le doigt pendant le dépliage, comme le menu en jeu.")
        handle.performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun lightlyDragPanel() {
        // Check that the content really moves the sheet, then let it settle
        // before tapping, following the same Material behavior as the game menu.
        val initial = compose.onNodeWithContentDescription("Drag handle")
            .fetchSemanticsNode().boundsInRoot.top
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Jaquette introuvable automatiquement").performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 60f), delayMillis = 100)
            advanceEventTime(200)
            up()
        }
        compose.mainClock.advanceTimeByFrame()
        val moved = compose.onNodeWithContentDescription("Drag handle")
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(moved > initial + 1f, "Le contenu doit aussi permettre de déplacer le panneau.")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }
}
