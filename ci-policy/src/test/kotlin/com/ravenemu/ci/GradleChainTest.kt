package com.ravenemu.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Intégrité de la chaîne de construction.
 *
 * Le wrapper Gradle est un binaire versionné dans le dépôt, exécuté avant tout
 * le reste et relu par personne : c'est la pièce la plus exposée de la chaîne.
 * Ces tests figent ce qui protège son exécution et celle de la distribution
 * qu'il télécharge.
 */
class GradleChainTest {

    private val racine = WorkflowFile.repositoryRoot
    private val workflow = WorkflowFile.androidWorkflow()

    private val wrapperProperties: Map<String, String> =
        File(racine, "gradle/wrapper/gradle-wrapper.properties")
            .readLines()
            .filter { it.contains('=') && !it.trimStart().startsWith('#') }
            .associate { ligne ->
                ligne.substringBefore('=').trim() to
                    ligne.substringAfter('=').trim().replace("\\:", ":")
            }

    @Test
    fun `la distribution Gradle vient du site officiel en HTTPS`() {
        val url = wrapperProperties["distributionUrl"]
            ?: fail("distributionUrl absent de gradle-wrapper.properties")
        assertTrue(
            url.startsWith("https://services.gradle.org/distributions/"),
            "Distribution attendue depuis le site officiel, trouvé : $url",
        )
    }

    @Test
    fun `le wrapper refuse une URL de distribution inattendue`() {
        assertEquals(
            "true",
            wrapperProperties["validateDistributionUrl"],
            "validateDistributionUrl protège contre une URL substituée dans le dépôt",
        )
    }

    @Test
    fun `l'empreinte de distribution, si elle est fixee, est un SHA-256`() {
        // Le contrôle porte sur la forme : une valeur mal recopiée casserait
        // toute construction, sur tous les postes, sans message exploitable.
        val empreinte = wrapperProperties["distributionSha256Sum"] ?: return
        assertTrue(
            Regex("^[0-9a-f]{64}$").matches(empreinte),
            "distributionSha256Sum doit être 64 caractères hexadécimaux minuscules",
        )
    }

    @Test
    fun `le wrapper est valide avant d'etre execute`() {
        val build = workflow.jobs["build"]?.joinToString("\n") ?: fail("Job build absent")
        val validation = build.indexOf("gradle/actions/wrapper-validation@")
        assertTrue(validation >= 0, "Le workflow doit valider gradle-wrapper.jar")
        val premierAppel = build.indexOf("./gradlew")
        assertTrue(premierAppel >= 0, "Aucun appel au wrapper : lecture du workflow cassée")
        assertTrue(
            validation < premierAppel,
            "La validation doit précéder la première exécution du wrapper",
        )
    }

    @Test
    fun `la distribution utilisee est comparee a l'empreinte declaree`() {
        val build = workflow.jobs["build"]?.joinToString("\n") ?: fail("Job build absent")
        assertTrue(
            "distributionSha256Sum" in build,
            "Le workflow doit contrôler la distribution effectivement téléchargée",
        )
    }

    @Test
    fun `les mises a jour de dependances sont surveillees`() {
        val dependabot = File(racine, ".github/dependabot.yml")
        assertTrue(dependabot.isFile, "Aucune configuration Dependabot")
        val contenu = dependabot.readText()
        for (ecosysteme in listOf("gradle", "github-actions")) {
            assertTrue(
                "package-ecosystem: $ecosysteme" in contenu,
                "L'écosystème « $ecosysteme » doit être surveillé",
            )
        }
    }

    @Test
    fun `les actions epinglees par SHA restent tenues a jour`() {
        // Épingler par SHA fige une action : sans surveillance, une correction
        // de sécurité publiée en amont ne serait jamais reprise. Les deux
        // règles vont ensemble et ce test refuse qu'on n'en garde qu'une.
        val dependabot = File(racine, ".github/dependabot.yml")
        assertTrue("package-ecosystem: github-actions" in dependabot.readText())
        for (reference in workflow.actionReferences) {
            if (reference.startsWith("./")) continue
            assertTrue(
                Regex("^[0-9a-f]{40}$").matches(reference.substringAfterLast('@')),
                "Action non épinglée par SHA : $reference",
            )
        }
    }

    @Test
    fun `le wrapper versionne se limite aux fichiers attendus`() {
        val fichiers = File(racine, "gradle/wrapper").listFiles()?.map { it.name }?.sorted()
        assertEquals(
            listOf("gradle-wrapper.jar", "gradle-wrapper.properties"),
            fichiers,
            "Rien d'autre ne doit être exécutable depuis gradle/wrapper",
        )
    }
}
