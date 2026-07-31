package com.ravenemu.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cohérence entre ce que le dépôt fait et ce que la documentation en dit.
 *
 * Une documentation fausse est pire qu'une documentation absente : elle donne
 * des consignes de vérification qui ne vérifient rien. Ces tests figent les
 * affirmations qui ont réellement dérivé — un canal de distribution supprimé
 * qu'on continuait d'annoncer, et une signature décrite comme provisoire alors
 * qu'elle est le fondement des mises à jour en place.
 */
class DocumentationPolicyTest {

    private val racine = WorkflowFile.repositoryRoot

    /** Tous les Markdown du dépôt, hors artefacts de construction. */
    private val documents: List<File> = racine.walkTopDown()
        .onEnter { it.name !in setOf(".git", "build", ".gradle") }
        .filter { it.isFile && it.extension == "md" }
        .toList()

    private fun relatif(fichier: File) = fichier.relativeTo(racine).path

    @Test
    fun `aucun document ne renvoie vers la preversion Debug supprimee`() {
        val fautifs = documents.filter { "releases/download/debug-latest" in it.readText() }
            .map(::relatif)
        assertEquals(
            emptyList(),
            fautifs,
            "La préversion debug-latest n'existe plus : ces liens ne mènent nulle part",
        )
    }

    @Test
    fun `le workflow ne publie ni ne nettoie plus de preversion Debug`() {
        val workflow = WorkflowFile.androidWorkflow().text
        assertTrue(
            "debug-latest" !in workflow,
            "Le canal debug-latest est retiré : plus rien ne doit y faire référence",
        )
        assertTrue(
            "apk/debug" !in workflow.substringAfter("jobs:"),
            "Aucun APK Debug n'est publié",
        )
    }

    @Test
    fun `aucun document ne decrit l'APK Test comme signe par une cle provisoire`() {
        // L'APK Test a une clé dédiée et stable : c'est ce qui permet de mettre
        // à jour l'application sans la désinstaller. Le décrire comme « signé
        // avec la clé de développement » inviterait à désinstaller pour rien.
        val formulations = listOf(
            "APK Test utilise la signature de développement",
            "APK Test utilise une signature de développement",
        )
        val fautifs = documents.filter { document ->
            val texte = document.readText()
            formulations.any { it in texte }
        }.map(::relatif)
        assertEquals(emptyList(), fautifs, "Description obsolète de la signature Test")
    }

    @Test
    fun `la verification du certificat est expliquee aux utilisateurs`() {
        // Publier une empreinte ne sert à rien si personne ne sait quoi en faire.
        for (nom in listOf("README.md", "SECURITY.md", "RELEASING.md")) {
            val texte = File(racine, nom).readText()
            assertTrue(
                "apksigner verify" in texte,
                "$nom doit indiquer comment vérifier le certificat de l'APK",
            )
        }
    }

    @Test
    fun `les liens relatifs des documents racine pointent vers des fichiers existants`() {
        val lien = Regex("\\[[^\\]]*]\\((?!https?:|#|mailto:)([^)#]+)")
        val casses = mutableListOf<String>()
        for (nom in listOf("README.md", "SECURITY.md", "RELEASING.md", "CONTRIBUTING.md")) {
            val document = File(racine, nom)
            for (correspondance in lien.findAll(document.readText())) {
                val cible = correspondance.groupValues[1].trim()
                if (!File(racine, cible).exists()) casses += "$nom → $cible"
            }
        }
        assertEquals(emptyList(), casses, "Liens relatifs cassés")
    }

    @Test
    fun `la documentation ne renvoie pas vers un document inexistant`() {
        // `docs/ARCHITECTURE.md` était cité par trois commentaires de code sans
        // avoir jamais existé.
        val sources = racine.walkTopDown()
            // `ci-policy` est le contrôleur, pas le contrôlé : ses tests citent
            // forcément les motifs qu'ils cherchent ailleurs.
            .onEnter { it.name !in setOf(".git", "build", ".gradle", "ci-policy") }
            .filter { it.isFile && it.extension in setOf("kt", "kts") }
            .toList()
        val fautifs = sources.filter { "docs/ARCHITECTURE.md" in it.readText() }
            .map(::relatif)
        assertEquals(emptyList(), fautifs, "Renvoi vers un document qui n'existe pas")
    }

    @Test
    fun `les trois canaux de distribution sont documentes`() {
        val readme = File(racine, "README.md").readText()
        for (paquet in listOf(
            "com.ravenemu.app.profil",
            "com.ravenemu.app.debug",
        )) {
            assertTrue(paquet in readme, "Le README doit nommer le package « $paquet »")
        }
        assertTrue(
            "test-latest" in readme,
            "Le README doit nommer la préversion qui porte l'APK Test",
        )
    }

    @Test
    fun `l'empreinte du certificat publiee est la meme partout`() {
        // Elle est écrite dans quatre documents. Une rotation de clé qui n'en
        // mettrait qu'un à jour laisserait ailleurs une consigne de
        // vérification fausse — c'est-à-dire pire qu'aucune consigne : elle
        // ferait rejeter un APK authentique, ou accepter le doute.
        val empreinte = Regex("\\b[0-9a-f]{64}\\b")
        val documents = listOf(
            "README.md",
            "SECURITY.md",
            "wiki/Installation.md",
            "docs/index.html",
        )
        val relevees = documents.associateWith { nom ->
            empreinte.findAll(File(racine, nom).readText()).map { it.value }.toSet()
        }

        val sansEmpreinte = relevees.filterValues { it.isEmpty() }.keys
        assertTrue(
            sansEmpreinte.isEmpty(),
            "Ces documents doivent publier l'empreinte du certificat Test : $sansEmpreinte",
        )

        // L'empreinte du certificat est la seule valeur de cette forme que ces
        // documents partagent : elle doit apparaître dans chacun d'eux.
        val communes = relevees.values.reduce { a, b -> a intersect b }
        assertEquals(
            1,
            communes.size,
            "Une et une seule empreinte doit être commune aux quatre documents, trouvé : $communes",
        )
    }

    @Test
    fun `le wiki respecte ses regles de publication`() {
        // Ces règles sont celles du workflow de publication du wiki, qui ne
        // s'exécute qu'après une fusion sur `main` : une pull request pouvait
        // donc être verte et casser la publication. Les reprendre ici les fait
        // vérifier à chaque construction, et sur le poste du contributeur.
        val wiki = File(racine, "wiki")
        for (obligatoire in listOf("Home.md", "_Sidebar.md")) {
            assertTrue(
                File(wiki, obligatoire).isFile,
                "Le wiki doit comporter $obligatoire",
            )
        }

        val fautifs = wiki.walkTopDown()
            .filter { it.isFile }
            .flatMap { fichier ->
                fichier.readLines().withIndex()
                    .filter { (_, ligne) -> TIRET_CADRATIN in ligne }
                    .map { (index, _) -> "${relatif(fichier)}:${index + 1}" }
            }
            .toList()
        assertEquals(
            emptyList(),
            fautifs,
            "Le tiret cadratin est interdit dans les textes du wiki",
        )
    }

    @Test
    fun `la politique de compatibilite des sauvegardes est ecrite`() {
        val page = File(racine, "wiki/Sauvegardes-et-etats.md").readText()
        assertTrue("RVNS" in page, "Le format d'état doit être nommé")
        assertTrue(
            ".sav" in page && "refus" in page.lowercase(),
            "La page doit dire ce qui est garanti et ce qui ne l'est pas",
        )
    }

    private companion object {
        /** U+2014, nommé plutôt qu'écrit pour rester lisible dans le source. */
        const val TIRET_CADRATIN = '\u2014'
    }
}
