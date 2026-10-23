package com.ravenemu.android.library

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.ravenemu.shared.saves.BatteryFileFormat
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GameDetailsTouchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `la fiche s'ouvre en entier et ne remonte pas quand on la tire vers le haut`() {
        openDetails(linkAvailable = true, onExport = { _, _ -> })
        val handle = compose.onNodeWithContentDescription("Drag handle").assertIsDisplayed()
        val opened = handle.fetchSemanticsNode().boundsInRoot.top
        assertTrue(opened > 100f, "La fiche ne doit pas remplir l'écran quand son contenu tient.")
        handle.performTouchInput {
            down(center)
            moveTo(center - Offset(0f, 600f), delayMillis = 400)
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        assertEquals(opened, handle.fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.onNodeWithText("Jouer").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi")
    fun `Jouer est visible des l'ouverture et lance le jeu au premier toucher`() {
        var starts = 0
        openDetails(onStart = { starts++ }, linkAvailable = true, onExport = { _, _ -> })
        compose.onNodeWithText("Jouer").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun `le menu en jeu permet aussi de reprendre le depliage`() {
        openDetails(controllerMode = true)
        compose.onNodeWithText("Jouer").performClick()
        compose.onNodeWithText("Préparation de l'image du jeu...").performClick()
        compose.waitForIdle()
        interruptExpansionWithDrag()
    }

    @Test
    fun `un glissement vers le bas ferme le panneau`() {
        openDetails(linkAvailable = true, onExport = { _, _ -> })
        compose.onNodeWithContentDescription("Drag handle").assertIsDisplayed().performTouchInput {
            down(center)
            moveTo(center + Offset(0f, 600f), delayMillis = 300)
            up()
        }
        compose.onNodeWithText("Jouer").assertDoesNotExist()
    }

    @Test
    fun `le premier toucher sur Jouer pendant que le panneau se replace lance le jeu`() {
        var starts = 0
        openDetails(onStart = { starts++ })
        lightlyDragPanel()
        compose.onNodeWithText("Jouer").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, starts) }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun `le premier toucher sur Jouer a deux pendant que le panneau se replace ouvre le partenaire`() {
        openDetails(linkAvailable = true)
        lightlyDragPanel()
        compose.onNodeWithText("Jouer à deux (câble link)")
            .assertIsDisplayed().performTouchInput { click() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("Jouer à deux").assertIsDisplayed()
        compose.onNodeWithText("Second jeu · Game Boy").assertIsDisplayed()
    }

    @Test
    fun `le premier toucher sur Exporter pendant que le panneau se replace propose sav ou srm`() {
        val exported = mutableListOf<Pair<String, BatteryFileFormat>>()
        openDetails(onExport = { id, format -> exported.add(id to format) })
        lightlyDragPanel()
        compose.onNodeWithText("Exporter").assertIsDisplayed().performTouchInput { click() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText(BatteryFileFormat.SAV.label).assertIsDisplayed()
        compose.onNodeWithText(BatteryFileFormat.SRM.label).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("test" to BatteryFileFormat.SRM), exported) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h568dp-mdpi")
    fun `le contenu defile sur petit ecran et Fermer ferme au premier toucher`() {
        openDetails(linkAvailable = true, onExport = { _, _ -> })
        compose.onNodeWithText("Fermer").performScrollTo()
            .assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithText("Jouer").assertDoesNotExist()
    }

    private fun openDetails(
        onStart: () -> Unit = {},
        linkAvailable: Boolean = false,
        onExport: ((String, BatteryFileFormat) -> Unit)? = null,
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
                onExportBatterySave = onExport,
                controllerMode = controllerMode,
            )
        }
        compose.onNodeWithText("Jeu de test").performClick()
        compose.waitForIdle()
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
        // Drag the content a little, then freeze the clock while the sheet
        // settles back: the tap that follows must reach the button.
        // Real pointer events: performClick() bypasses gesture interception.
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
    }
}
