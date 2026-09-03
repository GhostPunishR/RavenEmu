package com.ravenemu.deltaskin

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeltaSkinLayoutCalculatorTest {
    @Test
    fun `telephones et insets gardent panneau et jeu separes`() {
        val devices = listOf(
            Triple(360.0, 640.0, DeltaSkinInsets()),
            Triple(393.0, 852.0, DeltaSkinInsets(top = 47.0, bottom = 21.0)),
            Triple(412.0, 915.0, DeltaSkinInsets(left = 4.0, top = 32.0, right = 4.0)),
        )
        for ((width, height, insets) in devices) {
            val layout = calculate(width, height, insets, DeltaSkinSize(320.0, 274.0))
            assertEquals(layout.gameArea.bottom, layout.panel.top, 0.0001)
            assertTrue(layout.gameScreen.bottom <= layout.gameArea.bottom)
            assertTrue(layout.gameScreen.top >= layout.gameArea.top)
            assertTrue(layout.panel.bottom <= height - insets.bottom + 0.0001)
        }
    }

    @Test
    fun `panneaux 320 par 240 et 320 par 274 ne sont pas etires`() {
        for (mapping in listOf(DeltaSkinSize(320.0, 240.0), DeltaSkinSize(320.0, 274.0))) {
            val layout = calculate(393.0, 852.0, DeltaSkinInsets(), mapping)
            assertEquals(mapping.height / mapping.width, layout.panel.height / layout.panel.width, 1e-9)
            val scaleX = layout.panel.width / mapping.width
            val scaleY = layout.panel.height / mapping.height
            assertEquals(scaleX, scaleY, 1e-9)
        }
    }

    @Test
    fun `ratios natifs GB et GBA sont preserves et centres`() {
        for (native in listOf(DeltaSkinSize(160.0, 144.0), DeltaSkinSize(240.0, 160.0))) {
            val layout = DeltaSkinLayoutCalculator.calculate(
                393.0,
                852.0,
                DeltaSkinInsets(top = 30.0),
                DeltaSkinSize(320.0, 240.0),
                native,
            )!!
            assertTrue(abs(layout.gameScreen.width / layout.gameScreen.height - native.width / native.height) < 1e-9)
            assertEquals(
                layout.gameArea.left + layout.gameArea.width / 2.0,
                layout.gameScreen.left + layout.gameScreen.width / 2.0,
                1e-9,
            )
            assertEquals(
                layout.gameArea.top + layout.gameArea.height / 2.0,
                layout.gameScreen.top + layout.gameScreen.height / 2.0,
                1e-9,
            )
        }
    }

    @Test
    fun `hitbox suit exactement la transformation du PDF`() {
        val mapping = DeltaSkinSize(320.0, 240.0)
        val layout = calculate(360.0, 640.0, DeltaSkinInsets(), mapping)
        val mapped = DeltaSkinLayoutCalculator.mapFrame(
            DeltaSkinFrame(20.0, 60.0, 80.0, 40.0),
            layout.panel,
            mapping,
        )
        assertEquals(layout.panel.left + 22.5, mapped.left, 1e-9)
        assertEquals(layout.panel.top + 67.5, mapped.top, 1e-9)
        assertEquals(90.0, mapped.width, 1e-9)
        assertEquals(45.0, mapped.height, 1e-9)
    }

    /**
     * Le défaut qui montrait « Skin invalide » à l’écran.
     *
     * Un skin de Nintendo DS couvre tout le téléphone : son image est aussi
     * haute que la vue, et la disposition historique, qui ancre le panneau en
     * bas et cherche de la place au-dessus, n’en trouvait aucune. Elle rendait
     * `null`, la vue signalait un cadrage invalide, et le skin était
     * silencieusement désactivé.
     */
    @Test
    fun `un skin aussi haut que la vue n est plus refuse`() {
        val mapping = DeltaSkinSize(414.0, 896.0)
        val sansEcrans = DeltaSkinLayoutCalculator.calculate(
            414.0,
            896.0,
            DeltaSkinInsets(),
            mapping,
            DeltaSkinSize(256.0, 384.0),
        )
        assertNull(sansEcrans, "la disposition historique n’a pas de place au-dessus")

        val layout = dsLayout(414.0, 896.0, mapping)
        assertEquals(0.0, layout.panel.left, 1e-9)
        assertEquals(0.0, layout.panel.top, 1e-9)
        assertEquals(414.0, layout.panel.right, 1e-9)
        assertEquals(896.0, layout.panel.bottom, 1e-9)
    }

    /**
     * Les deux écrans tombent chacun à son cadre, et non d’un bloc.
     *
     * Les cadres sont donnés en toutes lettres, et les attendus calculés à la
     * main : le panneau vaut ici exactement le mapping, l’échelle est donc 1,
     * et un cadre se retrouve tel quel à l’écran.
     */
    @Test
    fun `chaque ecran declare va a sa place`() {
        val layout = dsLayout(414.0, 896.0, DeltaSkinSize(414.0, 896.0))
        assertEquals(2, layout.screens.size)

        val haut = layout.screens[0]
        assertEquals(79.0, haut.destination.left, 1e-9)
        assertEquals(60.0, haut.destination.top, 1e-9)
        assertEquals(335.0, haut.destination.right, 1e-9)
        assertEquals(252.0, haut.destination.bottom, 1e-9)
        val source = assertNotNull(haut.source)
        assertEquals(0.0, source.y, 1e-9)
        assertEquals(192.0, source.height, 1e-9)

        val bas = layout.screens[1]
        assertEquals(300.0, bas.destination.top, 1e-9)
        assertEquals(492.0, bas.destination.bottom, 1e-9)
        assertEquals(192.0, assertNotNull(bas.source).y, 1e-9)

        // Les deux cadres restent séparés : c’est toute la différence avec une
        // seule zone, où l’image du haut déborderait sur la charnière.
        assertTrue(haut.destination.bottom < bas.destination.top)
    }

    /** L’enveloppe couvre les deux écrans, et rien de plus. */
    @Test
    fun `l enveloppe borne les deux ecrans`() {
        val layout = dsLayout(414.0, 896.0, DeltaSkinSize(414.0, 896.0))
        assertEquals(79.0, layout.gameArea.left, 1e-9)
        assertEquals(60.0, layout.gameArea.top, 1e-9)
        assertEquals(335.0, layout.gameArea.right, 1e-9)
        assertEquals(492.0, layout.gameArea.bottom, 1e-9)
        assertEquals(layout.gameArea, layout.gameScreen)
    }

    /**
     * Un panneau plus étroit que la vue est centré, pas étiré : les cadres
     * suivent l’échelle du panneau, sinon les écrans se décaleraient de
     * l’image dessinée autour d’eux.
     */
    @Test
    fun `un panneau plus etroit que la vue est centre sans deformation`() {
        val mapping = DeltaSkinSize(414.0, 896.0)
        val layout = dsLayout(600.0, 896.0, mapping)
        assertEquals(1.0, layout.scale, 1e-9)
        assertEquals(93.0, layout.panel.left, 1e-9)
        assertEquals(507.0, layout.panel.right, 1e-9)
        assertEquals(0.0, layout.panel.top, 1e-9)
        assertEquals(896.0, layout.panel.bottom, 1e-9)
        // Le premier cadre est à 79 du bord du panneau, donc à 93 + 79 de la vue.
        assertEquals(172.0, layout.screens[0].destination.left, 1e-9)
    }

    /**
     * Un skin qui ne place rien garde la disposition historique : le panneau en
     * bas, le jeu au-dessus, et aucun emplacement d’écran.
     */
    @Test
    fun `un skin qui ne place rien garde l ancienne disposition`() {
        val layout = calculate(393.0, 852.0, DeltaSkinInsets(), DeltaSkinSize(320.0, 240.0))
        assertEquals(emptyList(), layout.screens)
        assertEquals(layout.gameArea.bottom, layout.panel.top, 1e-9)
    }

    /**
     * L’écriture ancienne, `gameScreenFrame`, pose un seul écran et prend le
     * tampon entier. Elle mène à la même disposition que `screens`.
     */
    @Test
    fun `l ecriture ancienne pose un ecran unique`() {
        val representation = DeltaSkinTestFixtures.representation(
            mappingHeight = 240.0,
            asset = "portrait.pdf",
            console = DeltaSkinConsole.GBA,
        ).copy(gameScreenFrame = DeltaSkinFrame(10.0, 20.0, 300.0, 200.0))
        val declares = representation.declaredScreens
        assertEquals(1, declares.size)
        assertNull(declares[0].inputFrame, "l’écriture ancienne ne découpe pas le tampon")
        assertEquals(DeltaSkinFrame(10.0, 20.0, 300.0, 200.0), declares[0].outputFrame)

        // `screens` l’emporte quand les deux sont écrites : c’est la forme qui
        // sait en décrire plusieurs.
        val deux = representation.copy(
            screens = listOf(DeltaSkinScreen(outputFrame = DeltaSkinFrame(0.0, 0.0, 10.0, 10.0)))
        )
        assertEquals(deux.screens, deux.declaredScreens)
    }

    /** Deux écrans empilés, aux cadres d’un skin de Nintendo DS. */
    private fun dsLayout(
        width: Double,
        height: Double,
        mapping: DeltaSkinSize,
    ): DeltaSkinLayout = DeltaSkinLayoutCalculator.calculate(
        width,
        height,
        DeltaSkinInsets(),
        mapping,
        DeltaSkinSize(256.0, 384.0),
        listOf(
            DeltaSkinScreen(
                inputFrame = DeltaSkinFrame(0.0, 0.0, 256.0, 192.0),
                outputFrame = DeltaSkinFrame(79.0, 60.0, 256.0, 192.0),
            ),
            DeltaSkinScreen(
                inputFrame = DeltaSkinFrame(0.0, 192.0, 256.0, 192.0),
                outputFrame = DeltaSkinFrame(79.0, 300.0, 256.0, 192.0),
            ),
        ),
    )!!

    private fun calculate(
        width: Double,
        height: Double,
        insets: DeltaSkinInsets,
        mapping: DeltaSkinSize,
    ): DeltaSkinLayout = DeltaSkinLayoutCalculator.calculate(
        width,
        height,
        insets,
        mapping,
        DeltaSkinSize(160.0, 144.0),
    )!!
}
