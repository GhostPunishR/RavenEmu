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
        assertEquals(126, snapshot.mainMode)
        assertEquals(127, snapshot.secondaryMode)
        assertEquals(128, snapshot.mainStackPointer)
        assertEquals(129, snapshot.secondaryStackPointer)
        assertEquals(130, snapshot.mainLinkRegister)
        assertEquals(131, snapshot.secondaryLinkRegister)
        assertEquals(132, snapshot.mainVectorBase)
        assertEquals(133, snapshot.mainDtcmBase)
        assertEquals(134, snapshot.mainDtcmSize)
        assertEquals(135, snapshot.mainInterruptEnable)
        assertEquals(136, snapshot.mainInterruptFlags)
        assertEquals(137, snapshot.secondaryInterruptEnable)
        assertEquals(138, snapshot.secondaryInterruptFlags)
        assertEquals(139, snapshot.mainSync)
        assertEquals(140, snapshot.secondarySync)
        assertEquals(141, snapshot.unimplementedPalettes)
    }

    /**
     * Une suite d'une autre longueur est refusée plutôt que lue en partie.
     *
     * Un relevé décalé est pire que pas de relevé : il désigne un coupable, et
     * ce n'est pas le bon. Quarante-deux est écrit en toutes lettres, parce que
     * c'est la promesse du pont et non une conséquence de la classe.
     */
    @Test
    fun `une suite mal dimensionnee est refusee`() {
        assertEquals(42, NdsDebugSnapshot.VALUE_COUNT)
        assertNull(NdsDebugSnapshot.of(null))
        assertNull(NdsDebugSnapshot.of(IntArray(0)))
        assertNull(NdsDebugSnapshot.of(IntArray(41)))
        assertNull(NdsDebugSnapshot.of(IntArray(43)))
        assertNotNull(NdsDebugSnapshot.of(IntArray(42)))
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
    fun `un releve sans manque tient en neuf lignes`() {
        val texte = sain().toDiagnosticText(fps = 60.0, frameTimeMs = 4.0)
        val lignes = texte.lines()
        assertEquals(9, lignes.size, texte)
        assertTrue(lignes[0].startsWith("NDS "), lignes[0])
        assertTrue(lignes[1].startsWith("ARM9 actif"), lignes[1])
        assertTrue(lignes[2].contains("syst"), lignes[2])
        assertTrue(lignes[3].startsWith("ARM7 actif"), lignes[3])
        assertTrue(lignes[4].contains("syst"), lignes[4])
        assertTrue(lignes[5].startsWith("cp15 "), lignes[5])
        assertTrue(lignes[6].startsWith("irq9 "), lignes[6])
        assertTrue(lignes[7].startsWith("irq7 "), lignes[7])
        assertTrue(lignes[8].startsWith("image "), lignes[8])
        assertFalse(texte.contains("non dessiné"), texte)
        assertFalse(texte.contains("ignoré"), texte)
        assertFalse(texte.contains("buté"), texte)
    }

    /**
     * Le mode se lit en toutes lettres, et une valeur inconnue reste visible
     * telle quelle : la muer en « inconnu » cacherait ce qu'elle vaut.
     */
    @Test
    fun `chaque mode porte son nom`() {
        val noms = mapOf(
            0x10 to "user",
            0x11 to "fiq",
            0x12 to "irq",
            0x13 to "svc",
            0x17 to "abrt",
            0x1b to "undf",
            0x1f to "syst",
        )
        for ((valeur, nom) in noms) {
            val texte = sain().copy(mainMode = valeur).toDiagnosticText(60.0, 4.0)
            assertTrue(texte.contains(nom), "mode $valeur attendu $nom dans $texte")
        }
        val inconnu = sain().copy(mainMode = 0x42).toDiagnosticText(60.0, 4.0)
        assertTrue(inconnu.contains("0042"), inconnu)
    }

    /**
     * Le registre de lien et la pile sont montrés : le compteur de programme dit
     * où l'on est échoué, eux disent comment on y est arrivé et si le processeur
     * avait une pile pour en revenir.
     */
    @Test
    fun `la pile et le registre de lien se lisent`() {
        val texte = sain().copy(
            mainStackPointer = 0,
            mainLinkRegister = 0x0200_4444,
        ).toDiagnosticText(60.0, 4.0)
        assertTrue(texte.contains("sp 00000000"), texte)
        assertTrue(texte.contains("lr 02004444"), texte)
        assertTrue(texte.contains("cp15 vect FFFF0000"), texte)
        assertTrue(texte.contains("dtcm 0B000000+4000"), texte)
    }

    /**
     * Ce que chaque processeur autorise et ce qui l'attend se lisent séparément :
     * « personne ne la lève » et « personne ne la ramasse » n'ont pas le même
     * remède, et un seul nombre les confondrait.
     */
    @Test
    fun `les interruptions et le rendez-vous se lisent`() {
        val texte = sain().copy(
            mainInterruptEnable = 0x0000_2001,
            mainInterruptFlags = 0x0000_0001,
            secondaryInterruptEnable = 0x0001_0000,
            secondaryInterruptFlags = 0,
            mainSync = 0x0503,
            secondarySync = 0x0305,
        ).toDiagnosticText(60.0, 4.0)
        assertTrue(texte.contains("irq9 ie 00002001 if 00000001 sync 0503"), texte)
        assertTrue(texte.contains("irq7 ie 00010000 if 00000000 sync 0305"), texte)
    }

    /** Chaque manque ajoute sa ligne, et seulement quand il y en a un. */
    @Test
    fun `chaque manque ajoute sa ligne`() {
        val avecPlans = sain().copy(unimplementedLayers = 3).toDiagnosticText(60.0, 4.0)
        assertTrue(avecPlans.contains("non dessiné : plans 3"), avecPlans)
        assertFalse(avecPlans.contains("modes"), avecPlans)

        // Les teintes fausses se disent à part des plans absents.
        val avecTeintes = sain().copy(unimplementedPalettes = 192).toDiagnosticText(60.0, 4.0)
        assertTrue(avecTeintes.contains("non dessiné : teintes 192"), avecTeintes)
        assertFalse(avecTeintes.contains("plans"), avecTeintes)

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
        mainMode = 0x1f,
        secondaryMode = 0x1f,
        mainStackPointer = 0x0B00_3FC0,
        secondaryStackPointer = 0x0380_FF00,
        mainLinkRegister = 0x0200_0100,
        secondaryLinkRegister = 0x037F_8100,
        mainVectorBase = 0xFFFF_0000.toInt(),
        mainDtcmBase = 0x0B00_0000,
        mainDtcmSize = 0x4000,
        mainInterruptEnable = 0x0000_0001,
        mainInterruptFlags = 0,
        secondaryInterruptEnable = 0x0000_0001,
        secondaryInterruptFlags = 0,
        mainSync = 0,
        secondarySync = 0,
        unimplementedPalettes = 0,
    )
}
