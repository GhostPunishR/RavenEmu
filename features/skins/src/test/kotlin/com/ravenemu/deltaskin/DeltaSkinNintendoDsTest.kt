package com.ravenemu.deltaskin

import com.ravenemu.emulation.api.EmulatorButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ce qu'un skin de Nintendo DS ajoute aux deux autres consoles.
 *
 * La DS apporte trois choses qu'aucun skin précédent ne portait : deux touches
 * de plus, un écran deux fois plus haut, et une zone tactile écrite comme un
 * D-pad. La dernière est la plus dangereuse : elle a la même forme qu'une croix
 * dans le manifeste, et la confondre avec une croix presserait des directions
 * dès qu'un doigt touche l'écran du bas.
 */
class DeltaSkinNintendoDsTest {
    private val panel = DeltaSkinRect(0.0, 0.0, 320.0, 240.0)
    private val representation = DeltaSkinTestFixtures.representation(
        mappingHeight = 240.0,
        asset = "portrait.pdf",
        console = DeltaSkinConsole.DS,
    )
    private val mapper = DeltaSkinInputMapper(DeltaSkinConsole.DS, representation, panel)

    @Test
    fun `la console est reconnue a son identifiant de type de jeu`() {
        assertEquals(
            DeltaSkinConsole.DS,
            DeltaSkinConsole.fromGameTypeIdentifier("com.rileytestut.delta.game.ds"),
        )
    }

    @Test
    fun `un manifeste DS complet est accepte`() {
        val manifest = DeltaSkinParser.parse(
            DeltaSkinParser.encode(DeltaSkinTestFixtures.manifest(DeltaSkinConsole.DS))
        )
        val validation = DeltaSkinValidator.validate(manifest)
        assertEquals(DeltaSkinConsole.DS, validation.console)
        // La zone tactile est reliée à la dalle de la console : plus rien n'est
        // signalé, parce que plus rien n'est laissé de côté.
        assertEquals(emptySet(), validation.ignoredInputs)
    }

    @Test
    fun `X et Y sont presses sur DS`() {
        assertEquals(setOf(EmulatorButton.X), mapper.inputAt(275.0, 62.0).buttons)
        assertEquals(setOf(EmulatorButton.Y), mapper.inputAt(195.0, 90.0).buttons)
    }

    @Test
    fun `X et Y restent muets sur une console qui ne les a pas`() {
        val gbaMapper = DeltaSkinInputMapper(DeltaSkinConsole.GBA, representation, panel)
        for (point in listOf(275.0 to 62.0, 195.0 to 90.0)) {
            val input = gbaMapper.inputAt(point.first, point.second)
            assertEquals(DeltaSkinMappedInput(), input, "point $point")
        }
        // Les gâchettes, elles, sont bien communes aux deux consoles : ce n'est
        // pas la représentation qui est ignorée, seulement ces deux touches.
        assertEquals(setOf(EmulatorButton.L), gbaMapper.inputAt(30.0, 45.0).buttons)
    }

    @Test
    fun `la zone tactile ne presse rien mais rend une position`() {
        val input = mapper.inputAt(200.0, 5.0)
        assertTrue(input.buttons.isEmpty(), "une zone tactile ne presse aucune direction")
        assertFalse(input.menu)
        assertTrue(input.visuals.isEmpty(), "et elle ne s'allume pas")
        // La zone couvre (0,0)-(320,60) du panneau : la position s'y lit en
        // fractions, jamais en pixels d'une console que ce module ne connaît pas.
        val touch = assertNotNull(input.touch)
        assertEquals(200.0 / 320.0, touch.fractionX, 1e-9)
        assertEquals(5.0 / 60.0, touch.fractionY, 1e-9)
    }

    @Test
    fun `un doigt qui deborde de la zone est retenu sur son bord`() {
        // L'extension tactile porte le doigt au-delà de la dalle dessinée. Il y
        // reste reçu, mais à son bord : un stylet qui sort de l'écran d'une
        // console ne saute pas de l'autre côté.
        val touch = assertNotNull(mapper.inputAt(319.0, 64.0).touch)
        assertEquals(1.0, touch.fractionY, 1e-9)
        assertTrue(touch.fractionX in 0.0..1.0)
    }

    @Test
    fun `une zone tactile declaree pour une console sans dalle reste signalee`() {
        // Le même item, sous l'identifiant d'une console qui n'a pas d'écran
        // tactile : le skin est accepté, mais l'utilisateur est prévenu que
        // cette zone ne fera rien.
        val manifest = DeltaSkinParser.parse(
            DeltaSkinParser.encode(
                DeltaSkinTestFixtures.manifest(DeltaSkinConsole.DS)
                    .copy(gameTypeIdentifier = DeltaSkinConsole.GBA.gameTypeIdentifier)
            )
        )
        val validation = DeltaSkinValidator.validate(manifest)
        assertEquals(DeltaSkinConsole.GBA, validation.console)
        assertTrue("touchScreenX" in validation.ignoredInputs)
        assertTrue("touchScreenY" in validation.ignoredInputs)
    }

    @Test
    fun `la zone tactile reste inerte sur une console sans ecran tactile`() {
        val gbaMapper = DeltaSkinInputMapper(DeltaSkinConsole.GBA, representation, panel)
        assertNull(gbaMapper.inputAt(200.0, 5.0).touch)
        assertTrue(DeltaSkinConsole.DS.hasTouchScreen)
        for (console in DeltaSkinConsole.entries - DeltaSkinConsole.DS) {
            assertFalse(console.hasTouchScreen, console.name)
        }
    }

    @Test
    fun `un bouton dessine par-dessus la zone tactile reste atteignable`() {
        // La zone tactile de cette représentation couvre le haut du panneau, où
        // le menu est dessiné. Seul l'item tactile est laissé de côté, pas les
        // items qu'il recouvre.
        val menu = mapper.inputAt(155.0, 20.0)
        assertTrue(menu.menu)
        assertTrue(menu.buttons.isEmpty())
        assertEquals(1, menu.visuals.size)
    }

    @Test
    fun `un dictionnaire de directions incomplet reste refuse`() {
        val error = assertFailsWith<DeltaSkinException> {
            validateDirectional(mapOf("up" to "up", "down" to "down", "left" to "left"))
        }
        assertEquals(DeltaSkinErrorCode.MISSING_REQUIRED_PROPERTY, error.code)
    }

    @Test
    fun `deux axes qui ne nomment pas la zone tactile restent refuses`() {
        // Les clés seules ne suffisent pas : sans cette seconde condition, un
        // D-pad mal écrit passerait pour un écran tactile et ne presserait plus
        // rien du tout.
        val error = assertFailsWith<DeltaSkinException> {
            validateDirectional(mapOf("x" to "up", "y" to "down"))
        }
        assertEquals(DeltaSkinErrorCode.MISSING_REQUIRED_PROPERTY, error.code)
    }

    @Test
    fun `la zone tactile est reconnue quelles que soient la casse et les espaces`() {
        val inputs = DeltaSkinInputs.Directional(
            mapOf(" X " to "TOUCHSCREENX", "y" to " touchscreeny ")
        )
        assertTrue(inputs.isTouchScreen)
        assertFalse(DeltaSkinInputs.Directional(mapOf("x" to "touchScreenX")).isTouchScreen)
        // Et le contrôle des archives la reconnaît de la même façon : les deux
        // ne peuvent pas diverger sans qu'un skin soit refusé pour une espace.
        validateDirectional(inputs.values)
    }

    @Test
    fun `l ecran encadre par un skin DS est celui des deux ecrans empiles`() {
        assertEquals(DeltaSkinSize(256.0, 384.0), DeltaSkinConsole.DS.screenSize)
        assertEquals(384.0, DeltaSkinConsole.DS.screenHeight)
        assertEquals(2.0, DeltaSkinConsole.DS.screenHeight / 192.0)
    }

    @Test
    fun `seule la DS honore les deux touches supplementaires`() {
        assertTrue(EmulatorButton.X in DeltaSkinConsole.DS.buttons)
        assertTrue(EmulatorButton.Y in DeltaSkinConsole.DS.buttons)
        assertTrue("x" in DeltaSkinConsole.DS.supportedInputNames)
        assertTrue("y" in DeltaSkinConsole.DS.supportedInputNames)
        for (console in DeltaSkinConsole.entries - DeltaSkinConsole.DS) {
            assertFalse(EmulatorButton.X in console.buttons, console.name)
            assertFalse("y" in console.supportedInputNames, console.name)
        }
    }

    /** Valide une représentation dont le seul item porte ce dictionnaire. */
    private fun validateDirectional(values: Map<String, String>) {
        val manifest = DeltaSkinTestFixtures.manifest(DeltaSkinConsole.DS)
        val iphone = manifest.representations.iphone!!
        val standard = iphone.standard!!
        val portrait = standard.portrait!!
        DeltaSkinValidator.validate(
            manifest.copy(
                representations = manifest.representations.copy(
                    iphone = DeltaSkinIphoneRepresentations(
                        standard = standard.copy(
                            portrait = portrait.copy(
                                items = listOf(
                                    DeltaSkinItem(
                                        inputs = DeltaSkinInputs.Directional(values),
                                        frame = DeltaSkinFrame(20.0, 65.0, 90.0, 90.0),
                                    )
                                )
                            )
                        ),
                    )
                )
            )
        )
    }
}
