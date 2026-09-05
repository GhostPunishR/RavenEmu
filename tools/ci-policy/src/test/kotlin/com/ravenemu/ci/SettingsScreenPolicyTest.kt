package com.ravenemu.ci

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Structure de l'écran des paramètres.
 *
 * Deux règles y sont vérifiées, et la seconde protège d'une panne **muette**.
 *
 * `findPreference` rend `null` quand la clé demandée n'existe pas, et tout le
 * câblage de `SettingsActivity` est écrit en appel sûr — c'est nécessaire,
 * puisque chaque écran ne contient qu'une partie des réglages. La contrepartie
 * est qu'une clé renommée dans le XML ne casse ni la compilation, ni le
 * lancement, ni aucun test : le bouton concerné cesse simplement de répondre.
 * Rien ne le dirait avant qu'un joueur appuie dessus.
 *
 * La première règle, elle, fixe le rangement demandé : quatre écrans au premier
 * niveau, et aucun réglage laissé à la racine où il échapperait au classement.
 */
class SettingsScreenPolicyTest {

    private val root = WorkflowFile.repositoryRoot
    private val preferencesFile = File(root, "app/android/src/main/res/xml/preferences.xml")
    private val activityFile = File(
        root,
        "app/android/src/main/kotlin/com/ravenemu/app/settings/SettingsActivity.kt",
    )

    private val document by lazy {
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(preferencesFile)
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length)
            .map(childNodes::item)
            .filter { it.nodeType == Node.ELEMENT_NODE }
            .map { it as Element }

    private fun Element.key(): String? = getAttribute("android:key").ifEmpty { null }

    /** Toutes les clés déclarées, écrans de navigation compris. */
    private fun allKeys(element: Element = document.documentElement): List<String> =
        listOfNotNull(element.key()) + element.childElements().flatMap { allKeys(it) }

    @Test
    fun `le premier niveau ne contient que des ecrans de categorie`() {
        val racine = document.documentElement
        val enfants = racine.childElements()
        val nonEcrans = enfants.filterNot { it.tagName == "PreferenceScreen" }
        assertEquals(
            emptyList(),
            nonEcrans.map { "${it.tagName}(${it.key()})" },
            "Un réglage est resté à la racine : il échappe au rangement par catégorie",
        )
        assertEquals(
            listOf("screen_general", "screen_audio", "screen_video", "screen_ui"),
            enfants.map { it.key() },
            "Les quatre catégories attendues, dans cet ordre",
        )
        for (ecran in enfants) {
            assertTrue(
                ecran.getAttribute("android:title").isNotEmpty(),
                "L'écran ${ecran.key()} doit porter un titre : il sert d'en-tête une fois ouvert",
            )
            assertTrue(
                ecran.getAttribute("android:summary").isNotEmpty(),
                "L'écran ${ecran.key()} doit porter un résumé : c'est lui qui évite " +
                    "d'ouvrir les quatre pour retrouver un réglage",
            )
        }
    }

    @Test
    fun `aucune cle n'est declaree deux fois`() {
        val doublons = allKeys().groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals(emptySet(), doublons, "Clé de préférence déclarée plusieurs fois")
    }

    /**
     * Le câblage du code ne peut pas viser une clé qui n'existe pas.
     *
     * Cette vérification est la raison d'être du fichier : elle transforme une
     * panne silencieuse — un bouton qui cesse de répondre — en échec de build.
     */
    @Test
    fun `chaque cle citee par le code existe dans le XML`() {
        val declarees = allKeys().toSet()
        val citees = Regex("""findPreference<[^>]+>\("([^"]+)"\)""")
            .findAll(activityFile.readText())
            .map { it.groupValues[1] }
            .toSortedSet()
        assertTrue(citees.isNotEmpty(), "Aucune clé citée : la lecture du code a échoué")
        assertEquals(
            emptyList(),
            (citees - declarees).toList(),
            "Clé câblée dans SettingsActivity mais absente de preferences.xml : " +
                "le réglage ne répondrait plus, sans erreur nulle part",
        )
    }
}
