package com.ravenemu.deltaskin

import com.ravenemu.emulation.api.EmulatorButton
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Import de bout en bout d'une archive **construite comme le sont les vraies**.
 *
 * Les fixtures existantes vérifient chaque règle isolément, avec des valeurs
 * choisies pour la commodité du test. Celle-ci fait l'inverse : elle reproduit
 * les caractéristiques relevées sur des archives réelles, et vérifie qu'elles
 * traversent toute la chaîne — import, disposition, entrées — sans accroc.
 *
 * Quatre de ces caractéristiques ne se devinent pas en lisant la spécification,
 * et chacune casserait un importateur écrit sans les connaître :
 *
 * 1. **Les archives viennent d'un Mac.** Elles contiennent un dossier
 *    `__MACOSX/` et un fichier `._` par entrée. Un importateur qui ne les écarte
 *    pas trouve deux `info.json` et refuse une archive parfaitement valide.
 * 2. **`extendedEdges` s'hérite côté par côté**, pas dictionnaire par
 *    dictionnaire : un item qui ne redéfinit que `right` conserve les trois
 *    autres bords de la représentation.
 * 3. **Une croix directionnelle est un dictionnaire**, découpé en grille 3×3.
 *    Les diagonales en découlent, et la cellule centrale est **neutre**.
 * 4. **Les frames débordent parfois du `mappingSize` d'une unité**, par simple
 *    arrondi à l'export. Refuser ces archives reviendrait à rejeter des skins
 *    corrects et courants.
 *
 * Rien de tout cela n'emprunte le moindre octet à une archive existante :
 * manifeste, PDF et parasites sont fabriqués ici.
 */
class ArchiveRealisteTest {

    @get:Rule
    val dossier = TemporaryFolder()

    /**
     * Inspecteur qui rend la taille attendue de chaque PDF.
     *
     * Les deux représentations n'ont pas le même `mappingSize` — le bord à bord
     * est plus haut pour occuper un écran sans marge — et le validateur compare
     * le ratio du PDF à celui de sa représentation. Un inspecteur à taille fixe
     * ferait donc échouer l'une des deux.
     */
    private val inspecteur = DeltaSkinPdfInspector { fichier ->
        val hauteur = when (fichier.name) {
            DeltaSkinArchiveImporter.EDGE_TO_EDGE_PORTRAIT_FILE -> HAUTEUR_BORD_A_BORD
            else -> HAUTEUR_MAPPAGE
        }
        DeltaSkinPdfInfo(pageCount = 1, width = LARGEUR_MAPPAGE.toInt(), height = hauteur.toInt())
    }

    // ---- Construction du manifeste ----

    /**
     * Manifeste calqué sur la structure réelle : un `mappingSize` de 320 × 240
     * en portrait standard, des bords étendus déclarés une fois sur la
     * représentation, et un seul item qui en redéfinit un seul côté.
     */
    private fun manifeste(console: DeltaSkinConsole): DeltaSkinManifest {
        val items = mutableListOf(
            // Croix directionnelle : dictionnaire, donc grille 3×3.
            DeltaSkinItem(
                inputs = DeltaSkinInputs.Directional(
                    mapOf("up" to "up", "down" to "down", "left" to "left", "right" to "right"),
                ),
                frame = DeltaSkinFrame(6.0, 56.0, 132.0, 132.0),
                extendedEdges = DeltaSkinEdges(top = 15.0, bottom = 15.0, left = 15.0, right = 15.0),
            ),
            // Redéfinit `right` seul : les trois autres bords doivent rester à 7.
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("a")),
                DeltaSkinFrame(241.0, 62.0, 64.0, 64.0),
                extendedEdges = DeltaSkinEdges(right = 15.0),
            ),
            // Aucun bord propre : hérite les quatre.
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("b")),
                DeltaSkinFrame(176.0, 121.0, 64.0, 64.0),
            ),
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("start")),
                DeltaSkinFrame(176.0, 205.0, 21.0, 21.0),
            ),
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("select")),
                DeltaSkinFrame(124.0, 205.0, 21.0, 21.0),
            ),
            DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("menu")),
                DeltaSkinFrame(8.0, 205.0, 21.0, 21.0),
            ),
        )
        if (console == DeltaSkinConsole.GBA) {
            items += DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("l")),
                DeltaSkinFrame(0.0, 0.0, 97.0, 29.0),
            )
            // 224 + 97 = 321 pour une largeur de mappage de 320 : le débordement
            // d'une unité observé sur de vraies archives GBA.
            items += DeltaSkinItem(
                DeltaSkinInputs.Buttons(listOf("r")),
                DeltaSkinFrame(224.0, 0.0, 97.0, 29.0),
            )
        }
        val portrait = DeltaSkinRepresentation(
            assets = DeltaSkinAssets(resizable = ASSET_STANDARD),
            mappingSize = DeltaSkinSize(LARGEUR_MAPPAGE, HAUTEUR_MAPPAGE),
            items = items,
            extendedEdges = DeltaSkinEdges(top = 7.0, bottom = 7.0, left = 7.0, right = 7.0),
        )
        val bordABord = portrait.copy(
            assets = DeltaSkinAssets(resizable = ASSET_BORD_A_BORD),
            mappingSize = DeltaSkinSize(LARGEUR_MAPPAGE, HAUTEUR_BORD_A_BORD),
        )
        return DeltaSkinManifest(
            name = "Skin d'essai ${console.name}",
            identifier = "com.ravenemu.test.${console.name.lowercase()}",
            gameTypeIdentifier = console.gameTypeIdentifier,
            debug = false,
            representations = DeltaSkinRepresentations(
                iphone = DeltaSkinIphoneRepresentations(
                    standard = DeltaSkinOrientations(portrait = portrait),
                    edgeToEdge = DeltaSkinOrientations(portrait = bordABord),
                ),
            ),
        )
    }

    /**
     * Archive compressée depuis macOS : un dossier `__MACOSX/` et un compagnon
     * `._` par entrée, y compris pour `info.json`.
     */
    private fun archive(console: DeltaSkinConsole): File {
        val manifest = manifeste(console)
        val parasites = linkedMapOf<String, ByteArray>(
            "__MACOSX/" to ByteArray(0),
            "__MACOSX/._info.json" to PARASITE,
            "__MACOSX/._$ASSET_STANDARD" to PARASITE,
            "__MACOSX/._$ASSET_BORD_A_BORD" to PARASITE,
            ".DS_Store" to PARASITE,
        )
        return DeltaSkinTestFixtures.createArchive(
            directory = dossier.newFolder(),
            manifest = manifest,
            extraEntries = parasites,
            fileName = "realiste.deltaskin",
        )
    }

    private fun importer(console: DeltaSkinConsole): DeltaSkinPreparedImport =
        DeltaSkinArchiveImporter(
            repositoryRoot = dossier.newFolder(),
            pdfInspector = inspecteur,
        ).prepare(archive(console), "realiste.deltaskin")

    // ---- Import ----

    @Test
    fun `une archive compressee depuis macOS s'importe malgre ses parasites`() {
        for (console in DeltaSkinConsole.entries) {
            val meta = importer(console).metadata
            assertEquals(console, meta.console, "console mal déduite")
            assertEquals(emptyList(), meta.ignoredInputs, "aucune entrée ne devait être ignorée")
            // Les PDF sont réécrits sous des noms internes : aucun nom venant de
            // l'archive ne devient un chemin.
            assertEquals(
                DeltaSkinArchiveImporter.STANDARD_PORTRAIT_FILE,
                meta.standardPortraitAsset,
            )
            assertEquals(
                DeltaSkinArchiveImporter.EDGE_TO_EDGE_PORTRAIT_FILE,
                meta.edgeToEdgePortraitAsset,
            )
        }
    }

    @Test
    fun `un compagnon macOS ne peut pas se faire passer pour le manifeste`() {
        // `__MACOSX/._info.json` est un fichier valide du point de vue du ZIP.
        // S'il était retenu, l'import échouerait sur « plusieurs info.json » ou,
        // pire, lirait ce contenu comme manifeste.
        val meta = importer(DeltaSkinConsole.GB_GBC).metadata
        assertEquals("Skin d'essai GB_GBC", meta.manifest.name)
    }

    // ---- Bords étendus ----

    @Test
    fun `un item ne redefinit qu'un cote et herite des trois autres`() {
        val rep = importer(DeltaSkinConsole.GB_GBC)
            .metadata.manifest.representations.iphone!!.standard!!.portrait!!
        val a = rep.items.first { (it.inputs as? DeltaSkinInputs.Buttons)?.values == listOf("a") }
        val bords = a.extendedEdges.resolvedOver(rep.extendedEdges)

        assertEquals(15.0, bords.right, "le côté redéfini doit l'emporter")
        assertEquals(7.0, bords.top, "les côtés absents viennent de la représentation")
        assertEquals(7.0, bords.bottom)
        assertEquals(7.0, bords.left)

        val b = rep.items.first { (it.inputs as? DeltaSkinInputs.Buttons)?.values == listOf("b") }
        val heritesEntierement = b.extendedEdges.resolvedOver(rep.extendedEdges)
        assertEquals(
            DeltaSkinResolvedEdges(7.0, 7.0, 7.0, 7.0),
            heritesEntierement,
            "un item sans bords propres hérite des quatre",
        )
    }

    // ---- Disposition ----

    /** Disposition sur un téléphone 1080 × 2340 sans encoche. */
    private fun disposition(console: DeltaSkinConsole): Pair<DeltaSkinLayout, DeltaSkinRepresentation> {
        val rep = importer(console)
            .metadata.manifest.representations.iphone!!.standard!!.portrait!!
        // La taille native vient de la console : la redire ici en ferait une
        // seconde source, et un écran ajouté ailleurs ne serait pas vu de ce
        // côté.
        val natif = console.screenSize
        val layout = DeltaSkinLayoutCalculator.calculate(
            containerWidth = 1080.0,
            containerHeight = 2340.0,
            insets = DeltaSkinInsets(),
            mappingSize = rep.mappingSize,
            nativeScreenSize = natif,
        )
        return checkNotNull(layout) to rep
    }

    @Test
    fun `l'ecran de jeu conserve le rapport natif de la console`() {
        for ((console, rapport) in listOf(
            DeltaSkinConsole.GBA to 240.0 / 160.0,
            DeltaSkinConsole.GB_GBC to 160.0 / 144.0,
            // Les deux écrans de la DS sont empilés dans un seul tampon : le
            // rapport encadré est celui de la pile, non celui d'un écran.
            DeltaSkinConsole.DS to 256.0 / 384.0,
        )) {
            val (layout, _) = disposition(console)
            val ecran = layout.gameScreen
            val mesure = (ecran.right - ecran.left) / (ecran.bottom - ecran.top)
            assertTrue(
                kotlin.math.abs(mesure - rapport) < 0.001,
                "$console : rapport $mesure au lieu de $rapport",
            )
            assertTrue(ecran.bottom <= layout.panel.top, "l'écran doit rester au-dessus du panneau")
        }
    }

    // ---- Entrées ----

    private fun centreDe(item: DeltaSkinItem, layout: DeltaSkinLayout, rep: DeltaSkinRepresentation):
        Pair<Double, Double> {
        val r = DeltaSkinLayoutCalculator.mapFrame(item.frame, layout.panel, rep.mappingSize)
        return (r.left + r.right) / 2 to (r.top + r.bottom) / 2
    }

    @Test
    fun `chaque bouton repond en son centre`() {
        val attendus = mapOf(
            "a" to EmulatorButton.A,
            "b" to EmulatorButton.B,
            "start" to EmulatorButton.START,
            "select" to EmulatorButton.SELECT,
            "l" to EmulatorButton.L,
            "r" to EmulatorButton.R,
        )
        for (console in DeltaSkinConsole.entries) {
            val (layout, rep) = disposition(console)
            val mapper = DeltaSkinInputMapper(console, rep, layout.panel)
            for (item in rep.items) {
                val noms = (item.inputs as? DeltaSkinInputs.Buttons)?.values ?: continue
                val (x, y) = centreDe(item, layout, rep)
                val resultat = mapper.inputAt(x, y)
                if (noms == listOf("menu")) {
                    assertTrue(resultat.menu, "$console : menu non levé")
                    assertEquals(emptySet(), resultat.buttons, "le menu n'est pas un bouton console")
                } else {
                    val attendu = attendus.getValue(noms.single())
                    assertEquals(setOf(attendu), resultat.buttons, "$console : ${noms.single()}")
                }
            }
        }
    }

    @Test
    fun `les coins du D-pad donnent les diagonales et le centre ne donne rien`() {
        for (console in DeltaSkinConsole.entries) {
            val (layout, rep) = disposition(console)
            val mapper = DeltaSkinInputMapper(console, rep, layout.panel)
            val dpad = rep.items.first { it.inputs is DeltaSkinInputs.Directional }
            val r = DeltaSkinLayoutCalculator.mapFrame(dpad.frame, layout.panel, rep.mappingSize)
            val pas = (r.right - r.left) / 6
            val pasY = (r.bottom - r.top) / 6

            val coins = mapOf(
                (r.left + pas to r.top + pasY) to setOf(EmulatorButton.UP, EmulatorButton.LEFT),
                (r.right - pas to r.top + pasY) to setOf(EmulatorButton.UP, EmulatorButton.RIGHT),
                (r.left + pas to r.bottom - pasY) to setOf(EmulatorButton.DOWN, EmulatorButton.LEFT),
                (r.right - pas to r.bottom - pasY) to
                    setOf(EmulatorButton.DOWN, EmulatorButton.RIGHT),
            )
            for ((point, attendu) in coins) {
                assertEquals(
                    attendu,
                    mapper.inputAt(point.first, point.second).buttons,
                    "$console : diagonale attendue en $point",
                )
            }

            // Cellule centrale de la grille 3×3 : aucune direction.
            val (cx, cy) = centreDe(dpad, layout, rep)
            assertEquals(
                emptySet(),
                mapper.inputAt(cx, cy).buttons,
                "$console : le centre du D-pad doit être neutre",
            )
        }
    }

    // ---- Débordement d'arrondi ----

    @Test
    fun `une frame debordant d'une unite est acceptee et le bouton reste jouable`() {
        // C'est le cas `r` des skins GBA réels : 224 + 97 = 321 pour 320 de
        // large. Refuser l'archive priverait l'utilisateur d'un skin correct ;
        // l'accepter sans borner laisserait une hitbox hors panneau.
        val (layout, rep) = disposition(DeltaSkinConsole.GBA)
        val r = rep.items.first { (it.inputs as? DeltaSkinInputs.Buttons)?.values == listOf("r") }
        assertTrue(
            r.frame.x + r.frame.width > rep.mappingSize.width,
            "le cas d'essai doit bien déborder",
        )

        val mapper = DeltaSkinInputMapper(DeltaSkinConsole.GBA, rep, layout.panel)
        val (cx, cy) = centreDe(r, layout, rep)
        assertEquals(setOf(EmulatorButton.R), mapper.inputAt(cx, cy).buttons, "R doit rester jouable")

        // Un point au-delà du bord droit du panneau n'appartient à personne.
        val dehors = mapper.inputAt(layout.panel.right + 1.0, cy)
        assertEquals(emptySet(), dehors.buttons, "aucune entrée hors du panneau")
        assertTrue(dehors.visuals.isEmpty(), "aucun retour visuel hors du panneau")
    }

    private companion object {
        const val LARGEUR_MAPPAGE = 320.0
        const val HAUTEUR_MAPPAGE = 240.0

        /** Le bord à bord est plus haut : il occupe un écran sans marge. */
        const val HAUTEUR_BORD_A_BORD = 274.0
        const val ASSET_STANDARD = "iphone_portrait.pdf"
        const val ASSET_BORD_A_BORD = "iphone_edgetoedge_portrait.pdf"

        /** Contenu d'un compagnon macOS : quelques octets qui ne sont pas du JSON. */
        val PARASITE = "Mac OS X          ".toByteArray(StandardCharsets.ISO_8859_1)
    }
}
