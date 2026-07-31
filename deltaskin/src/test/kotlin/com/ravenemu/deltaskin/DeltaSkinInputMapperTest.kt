package com.ravenemu.deltaskin

import com.ravenemu.emulation.api.EmulatorButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeltaSkinInputMapperTest {
    private val representation = DeltaSkinTestFixtures.representation(
        mappingHeight = 240.0,
        asset = "portrait.pdf",
    )
    private val panel = DeltaSkinRect(0.0, 0.0, 320.0, 240.0)
    private val mapper = DeltaSkinInputMapper(
        DeltaSkinConsole.GB_GBC,
        representation,
        panel,
    )

    @Test
    fun `A B et combinaison A plus B`() {
        assertEquals(setOf(EmulatorButton.A), mapper.inputAt(280.0, 90.0).buttons)
        assertEquals(setOf(EmulatorButton.B), mapper.inputAt(225.0, 120.0).buttons)
        assertEquals(
            setOf(EmulatorButton.A, EmulatorButton.B),
            mapper.inputAt(280.0, 155.0).buttons,
        )
    }

    @Test
    fun `D-pad produit cardinaux et quatre diagonales`() {
        val cells = mapOf(
            35.0 to mapOf(
                80.0 to setOf(EmulatorButton.UP, EmulatorButton.LEFT),
                110.0 to setOf(EmulatorButton.LEFT),
                140.0 to setOf(EmulatorButton.DOWN, EmulatorButton.LEFT),
            ),
            65.0 to mapOf(
                80.0 to setOf(EmulatorButton.UP),
                110.0 to emptySet(),
                140.0 to setOf(EmulatorButton.DOWN),
            ),
            95.0 to mapOf(
                80.0 to setOf(EmulatorButton.UP, EmulatorButton.RIGHT),
                110.0 to setOf(EmulatorButton.RIGHT),
                140.0 to setOf(EmulatorButton.DOWN, EmulatorButton.RIGHT),
            ),
        )
        for ((x, rows) in cells) {
            for ((y, expected) in rows) {
                assertEquals(expected, mapper.inputAt(x, y).buttons, "cellule $x,$y")
            }
        }
    }

    @Test
    fun `extendedEdges sont heritees et bornees au panneau`() {
        val inherited = mapper.inputAt(255.0, 90.0)
        assertEquals(setOf(EmulatorButton.A), inherited.buttons)
        assertTrue(mapper.inputAt(-1.0, 90.0).buttons.isEmpty())

        val partialRepresentation = DeltaSkinTestFixtures.representation(
            240.0,
            "portrait.pdf",
            partialItemEdges = true,
        )
        val partialMapper = DeltaSkinInputMapper(
            DeltaSkinConsole.GB_GBC,
            partialRepresentation,
            panel,
        )
        // À gauche de la frame du D-pad : l'extension propre à l'item gagne.
        val extendedDpad = partialMapper.inputAt(10.0, 80.0)
        assertEquals(
            setOf(EmulatorButton.UP, EmulatorButton.LEFT),
            extendedDpad.buttons,
        )
        assertTrue(extendedDpad.visuals.all { it.rect.left >= 20.0 })
    }

    @Test
    fun `Start Select et Menu`() {
        assertEquals(setOf(EmulatorButton.SELECT), mapper.inputAt(110.0, 160.0).buttons)
        assertEquals(setOf(EmulatorButton.START), mapper.inputAt(170.0, 160.0).buttons)
        val menu = mapper.inputAt(155.0, 20.0)
        assertTrue(menu.menu)
        assertTrue(menu.buttons.isEmpty())
    }

    @Test
    fun `L et R ne sont emis que pour GBA`() {
        val gbaRepresentation = DeltaSkinTestFixtures.representation(
            240.0,
            "portrait.pdf",
            DeltaSkinConsole.GBA,
        )
        val gbaMapper = DeltaSkinInputMapper(DeltaSkinConsole.GBA, gbaRepresentation, panel)
        assertEquals(setOf(EmulatorButton.L), gbaMapper.inputAt(30.0, 45.0).buttons)
        assertEquals(setOf(EmulatorButton.R), gbaMapper.inputAt(280.0, 45.0).buttons)

        val gbcMapper = DeltaSkinInputMapper(DeltaSkinConsole.GB_GBC, gbaRepresentation, panel)
        assertTrue(gbcMapper.inputAt(30.0, 45.0).buttons.isEmpty())
        assertTrue(gbcMapper.inputAt(280.0, 45.0).buttons.isEmpty())
    }

    @Test
    fun `glissement entre directions est publie en une transition`() {
        val changes = mutableListOf<DeltaSkinButtonChanges>()
        val state = DeltaSkinInputState({ changes += it }, onMenu = {})
        state.updatePointer(1, mapper.inputAt(65.0, 80.0))
        state.updatePointer(1, mapper.inputAt(95.0, 110.0))

        assertEquals(
            DeltaSkinButtonChanges(setOf(EmulatorButton.UP), emptySet()),
            changes[0],
        )
        assertEquals(
            DeltaSkinButtonChanges(
                pressed = setOf(EmulatorButton.RIGHT),
                released = setOf(EmulatorButton.UP),
            ),
            changes[1],
        )
    }

    @Test
    fun `multitouch et references multiples ne relachent pas A trop tot`() {
        val changes = mutableListOf<DeltaSkinButtonChanges>()
        val state = DeltaSkinInputState({ changes += it }, onMenu = {})
        state.updatePointer(1, mapper.inputAt(280.0, 90.0))
        state.updatePointer(2, mapper.inputAt(280.0, 155.0))
        state.releasePointer(1)

        assertEquals(
            listOf(
                DeltaSkinButtonChanges(setOf(EmulatorButton.A), emptySet()),
                DeltaSkinButtonChanges(setOf(EmulatorButton.B), emptySet()),
            ),
            changes,
        )
        state.releasePointer(2)
        assertEquals(
            DeltaSkinButtonChanges(
                emptySet(),
                setOf(EmulatorButton.A, EmulatorButton.B),
            ),
            changes.last(),
        )
    }

    @Test
    fun `D-pad et bouton fonctionnent avec deux pointeurs`() {
        val active = mutableSetOf<EmulatorButton>()
        val state = DeltaSkinInputState(
            onButtonsChanged = { change ->
                active -= change.released
                active += change.pressed
            },
            onMenu = {},
        )
        state.updatePointer(3, mapper.inputAt(65.0, 80.0))
        state.updatePointer(4, mapper.inputAt(280.0, 90.0))
        assertEquals(setOf(EmulatorButton.UP, EmulatorButton.A), active)
    }

    @Test
    fun `ACTION_CANCEL logique relache tout`() {
        val changes = mutableListOf<DeltaSkinButtonChanges>()
        val state = DeltaSkinInputState({ changes += it }, onMenu = {})
        state.updatePointer(1, mapper.inputAt(280.0, 155.0))
        state.updatePointer(2, mapper.inputAt(65.0, 80.0))
        state.releaseAll()
        assertEquals(
            setOf(EmulatorButton.A, EmulatorButton.B, EmulatorButton.UP),
            changes.last().released,
        )
    }

    @Test
    fun `Menu nest ouvert quune fois tant quun pointeur le maintient`() {
        var menuCount = 0
        val state = DeltaSkinInputState(onButtonsChanged = {}, onMenu = { menuCount++ })
        val menu = mapper.inputAt(155.0, 20.0)
        state.updatePointer(1, menu)
        state.updatePointer(2, menu)
        state.releasePointer(1)
        assertEquals(1, menuCount)
        state.releasePointer(2)
        state.updatePointer(3, menu)
        assertEquals(2, menuCount)
    }

    @Test
    fun `centre du D-pad reste neutre et sans surbrillance`() {
        val center = mapper.inputAt(65.0, 110.0)
        assertTrue(center.buttons.isEmpty())
        assertTrue(center.visuals.isEmpty())
        assertFalse(center.menu)
    }
}
