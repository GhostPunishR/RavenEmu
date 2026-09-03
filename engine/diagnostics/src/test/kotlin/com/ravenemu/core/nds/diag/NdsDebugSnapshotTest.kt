package com.ravenemu.core.nds.diag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Le relevé traverse le pont natif comme une suite de nombres, et rien dans la
 * suite ne dit ce que chacun désigne. C'est cet ordre qu'on vérifie ici : une
 * valeur qui se lirait un cran plus loin accuserait le mauvais organe avec
 * l'aplomb d'une mesure.
 */
class NdsDebugSnapshotTest {

    /**
     * Chaque nombre va au champ qui lui revient.
     *
     * La suite porte des valeurs toutes différentes, et chaque attendu est écrit
     * en toutes lettres : se comparer à la table qu'on remplit ne prouverait
     * rien de la table.
     */
    @Test
    fun `chaque valeur va a son champ`() {
        val values = IntArray(NdsDebugSnapshot.VALUE_COUNT) { it + 100 }
        // Les quatre champs booléens sont posés à part : un entier croissant les
        // rendrait tous vrais, et ne dirait pas lequel est lu.
        values[4] = 1
        values[5] = 0
        values[22] = 1

        val snapshot = assertNotNull(NdsDebugSnapshot.of(values))
        assertEquals(100, snapshot.mainInstructions)
        assertEquals(101, snapshot.secondaryInstructions)
        assertEquals(102, snapshot.mainProgramCounter)
        assertEquals(103, snapshot.secondaryProgramCounter)
        assertTrue(snapshot.mainHalted)
        assertFalse(snapshot.secondaryHalted)
        assertEquals(106, snapshot.mainUndefined)
        assertEquals(107, snapshot.mainFirstUndefined)
        assertEquals(108, snapshot.secondaryUndefined)
        assertEquals(109, snapshot.secondaryFirstUndefined)
        assertEquals(110, snapshot.mainUnimplementedIo)
        assertEquals(111, snapshot.mainFirstUnimplementedIo)
        assertEquals(112, snapshot.secondaryUnimplementedIo)
        assertEquals(113, snapshot.secondaryFirstUnimplementedIo)
        assertEquals(114, snapshot.mainUnsupportedSwi)
        assertEquals(115, snapshot.secondaryUnsupportedSwi)
        assertEquals(116, snapshot.mainDisplayControl)
        assertEquals(117, snapshot.secondaryDisplayControl)
        assertEquals(118, snapshot.unimplementedLayers)
        assertEquals(119, snapshot.unimplementedDisplay)
        assertEquals(120, snapshot.unimplementedObjects)
        assertEquals(121, snapshot.nonBlackPixels)
        assertTrue(snapshot.screensSwapped)
        assertEquals(123, snapshot.cartridgeUnsupported)
        assertEquals(124, snapshot.mainFirstUnsupportedSwi)
        assertEquals(125, snapshot.secondaryFirstUnsupportedSwi)
    }

    /**
     * Une suite d'une autre longueur est refusée plutôt que lue en partie.
     *
     * Un relevé décalé est pire que pas de relevé : il désigne un coupable, et
     * ce n'est pas le bon. Vingt-six est écrit en toutes lettres, parce que
     * c'est la promesse du pont et non une conséquence de la classe.
     */
    @Test
    fun `une suite mal dimensionnee est refusee`() {
        assertEquals(26, NdsDebugSnapshot.VALUE_COUNT)
        assertNull(NdsDebugSnapshot.of(null))
        assertNull(NdsDebugSnapshot.of(IntArray(0)))
        assertNull(NdsDebugSnapshot.of(IntArray(25)))
        assertNull(NdsDebugSnapshot.of(IntArray(27)))
        assertNotNull(NdsDebugSnapshot.of(IntArray(26)))
    }

    /**
     * N'importe quelle valeur non nulle vaut vrai, et non la seule valeur 1 :
     * le pont promet un drapeau, pas un nombre convenu.
     */
    @Test
    fun `un drapeau se lit sur toute valeur non nulle`() {
        val values = IntArray(NdsDebugSnapshot.VALUE_COUNT)
        values[5] = 7
        values[22] = -1
        val snapshot = assertNotNull(NdsDebugSnapshot.of(values))
        assertTrue(snapshot.secondaryHalted)
        assertTrue(snapshot.screensSwapped)
        assertFalse(snapshot.mainHalted)
    }

    /**
     * Le texte répond dans l'ordre des questions qu'on se pose devant un écran
     * noir : la console avance-t-elle, a-t-elle produit une image, que lui
     * manque-t-il. Une console qui va bien ne dit que les trois premières.
     */
    @Test
    fun `un releve sans manque tient en quatre lignes`() {
        val texte = sain().toDiagnosticText(fps = 60.0, frameTimeMs = 4.0)
        val lignes = texte.lines()
        assertEquals(4, lignes.size, texte)
        assertTrue(lignes[0].startsWith("NDS "), lignes[0])
        assertTrue(lignes[1].startsWith("ARM9 actif"), lignes[1])
        assertTrue(lignes[2].startsWith("ARM7 actif"), lignes[2])
        assertTrue(lignes[3].startsWith("image "), lignes[3])
        assertFalse(texte.contains("non dessiné"), texte)
        assertFalse(texte.contains("ignoré"), texte)
        assertFalse(texte.contains("buté"), texte)
    }

    /** Chaque manque ajoute sa ligne, et seulement quand il y en a un. */
    @Test
    fun `chaque manque ajoute sa ligne`() {
        val avecPlans = sain().copy(unimplementedLayers = 3).toDiagnosticText(60.0, 4.0)
        assertTrue(avecPlans.contains("non dessiné : plans 3"), avecPlans)
        assertFalse(avecPlans.contains("modes"), avecPlans)

        val avecIo = sain().copy(
            secondaryUnimplementedIo = 12,
            secondaryFirstUnimplementedIo = 0x0400_0138,
        ).toDiagnosticText(60.0, 4.0)
        assertTrue(avecIo.contains("ignoré : E/S7 12 dès 04000138"), avecIo)

        val avecFaute = sain().copy(
            mainUndefined = 5,
            mainFirstUndefined = 0x0200_0010,
        ).toDiagnosticText(60.0, 4.0)
        assertTrue(avecFaute.contains("buté : indéf.9 5 dès 02000010"), avecFaute)

        // Le compte seul ne dit pas quel service écrire : le numéro le dit.
        val avecAppel = sain().copy(
            secondaryUnsupportedSwi = 2,
            secondaryFirstUnsupportedSwi = 0x08,
        ).toDiagnosticText(60.0, 4.0)
        assertTrue(avecAppel.contains("buté : BIOS7 2 dès n° 08"), avecAppel)
    }

    /**
     * Un processeur arrêté et des écrans échangés se lisent dans le texte : ce
     * sont deux états normaux de la console qu'un relevé muet ferait passer
     * pour des pannes.
     */
    @Test
    fun `l arret et l echange des ecrans se lisent`() {
        val texte = sain().copy(mainHalted = true, screensSwapped = true)
            .toDiagnosticText(60.0, 4.0)
        assertTrue(texte.contains("ARM9 arrêt"), texte)
        assertTrue(texte.contains("écrans échangés"), texte)
    }

    /** Une console qui tourne sans rien laisser de côté. */
    private fun sain(): NdsDebugSnapshot = NdsDebugSnapshot(
        mainInstructions = 550_000,
        secondaryInstructions = 260_000,
        mainProgramCounter = 0x0200_1234,
        secondaryProgramCounter = 0x037F_8010,
        mainHalted = false,
        secondaryHalted = false,
        mainUndefined = 0,
        mainFirstUndefined = 0,
        secondaryUndefined = 0,
        secondaryFirstUndefined = 0,
        mainUnimplementedIo = 0,
        mainFirstUnimplementedIo = 0,
        secondaryUnimplementedIo = 0,
        secondaryFirstUnimplementedIo = 0,
        mainUnsupportedSwi = 0,
        secondaryUnsupportedSwi = 0,
        mainDisplayControl = 0x0001_0100,
        secondaryDisplayControl = 0,
        unimplementedLayers = 0,
        unimplementedDisplay = 0,
        unimplementedObjects = 0,
        nonBlackPixels = 49_152,
        screensSwapped = false,
        cartridgeUnsupported = 0,
        mainFirstUnsupportedSwi = 0,
        secondaryFirstUnsupportedSwi = 0,
    )
}
