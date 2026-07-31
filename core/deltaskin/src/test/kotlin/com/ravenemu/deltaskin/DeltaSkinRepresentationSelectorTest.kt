package com.ravenemu.deltaskin

import kotlin.test.Test
import kotlin.test.assertEquals

class DeltaSkinRepresentationSelectorTest {
    private val both = setOf(
        DeltaSkinRepresentationKind.STANDARD,
        DeltaSkinRepresentationKind.EDGE_TO_EDGE,
    )

    @Test
    fun `standard uniquement est toujours choisi`() {
        assertEquals(
            DeltaSkinRepresentationKind.STANDARD,
            select(setOf(DeltaSkinRepresentationKind.STANDARD), 393.0, 852.0),
        )
    }

    @Test
    fun `edgeToEdge uniquement est toujours choisi`() {
        assertEquals(
            DeltaSkinRepresentationKind.EDGE_TO_EDGE,
            select(setOf(DeltaSkinRepresentationKind.EDGE_TO_EDGE), 360.0, 640.0),
        )
    }

    @Test
    fun `automatique choisit standard sur ecran court`() {
        assertEquals(
            DeltaSkinRepresentationKind.STANDARD,
            select(both, 360.0, 640.0),
        )
    }

    @Test
    fun `automatique choisit bord a bord sur ecran haut`() {
        assertEquals(
            DeltaSkinRepresentationKind.EDGE_TO_EDGE,
            select(both, 393.0, 852.0),
        )
    }

    @Test
    fun `preference utilisateur gagne`() {
        assertEquals(
            DeltaSkinRepresentationKind.STANDARD,
            DeltaSkinRepresentationSelector.select(
                both,
                DeltaSkinRepresentationPreference.STANDARD,
                393.0,
                852.0,
            ),
        )
    }

    @Test
    fun `preference absente retombe sur lautre representation`() {
        assertEquals(
            DeltaSkinRepresentationKind.EDGE_TO_EDGE,
            DeltaSkinRepresentationSelector.select(
                setOf(DeltaSkinRepresentationKind.EDGE_TO_EDGE),
                DeltaSkinRepresentationPreference.STANDARD,
                360.0,
                640.0,
            ),
        )
    }

    private fun select(
        kinds: Set<DeltaSkinRepresentationKind>,
        width: Double,
        height: Double,
    ) = DeltaSkinRepresentationSelector.select(
        kinds,
        DeltaSkinRepresentationPreference.AUTOMATIC,
        width,
        height,
    )
}
