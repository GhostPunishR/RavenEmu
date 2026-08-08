package com.ravenemu.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Séparation des clés de signature et stabilité de la signature Test.
 *
 * L'APK Test était signé par la clé de débogage générée à la volée sur le
 * runner : son certificat changeait à chaque construction, rendant impossible
 * la mise à jour sur l'appareil. Ces tests figent le remède : une clé Test
 * dédiée, jamais confondue avec la clé Release, et une publication qui refuse
 * de s'exécuter sans elle.
 */
class SigningPolicyTest {

    private val workflow = WorkflowFile.androidWorkflow()
    private val appBuildFile = WorkflowFile.moduleBuildFile("app").readText()

    private fun job(name: String): String =
        workflow.jobs[name]?.joinToString("\n") ?: error("Job « $name » absent du workflow")

    // Seules les lectures `secrets.` comptent : les empreintes de certificat
    // (`vars.`) sont publiques et se croisent volontairement d'un job à l'autre
    // pour interdire l'usage d'une clé commune.
    private fun releaseSecrets(text: String): List<String> =
        Regex("secrets\\.RAVENEMU_(?!TEST_)[A-Z_]+").findAll(text).map { it.value }.toList()

    private fun testSecrets(text: String): List<String> =
        Regex("secrets\\.RAVENEMU_TEST_[A-Z_]+").findAll(text).map { it.value }.toList()

    @Test
    fun `la variante profil utilise une clé Test dédiée quand elle est fournie`() {
        assertTrue(
            appBuildFile.contains("""create("test")"""),
            "Une configuration de signature Test distincte doit exister",
        )
        assertTrue(
            appBuildFile.contains("RAVENEMU_TEST_KEYSTORE_PASSWORD") &&
                appBuildFile.contains("RAVENEMU_TEST_KEY_ALIAS") &&
                appBuildFile.contains("RAVENEMU_TEST_KEY_PASSWORD"),
            "La signature Test doit être alimentée par des variables RAVENEMU_TEST_*",
        )
        assertTrue(
            appBuildFile.contains("""signingConfigs.getByName("test")"""),
            "La variante profil doit référencer la configuration de signature Test",
        )
    }

    @Test
    fun `la construction locale reste possible sans aucun secret`() {
        assertTrue(
            appBuildFile.contains("""signingConfigs.getByName("debug")"""),
            "Sans clé Test, la variante profil doit retomber sur la clé de débogage",
        )
        val restoreStep = workflow.text.substringAfter("Restaurer la clé de signature Test")
            .substringBefore("- name:")
        assertTrue(
            restoreStep.contains("if: env.TEST_KEYSTORE_BASE64 != ''"),
            "La restauration de la clé Test doit être conditionnelle",
        )
    }

    @Test
    fun `le package de l'APK Test reste inchangé`() {
        assertTrue(appBuildFile.contains("""applicationIdSuffix = ".profil""""))
        assertTrue(
            job("publish-test").contains("com.ravenemu.app.profil") ||
                job("build").contains("com.ravenemu.app.profil"),
            "Le nom du package publié doit rester com.ravenemu.app.profil",
        )
    }

    @Test
    fun `la publication Test échoue explicitement sans clé dédiée`() {
        val publish = job("publish-test")
        assertTrue(
            publish.contains("RAVENEMU_TEST_KEYSTORE_BASE64"),
            "Le job de publication doit contrôler la présence de la clé Test",
        )
        assertTrue(
            publish.contains("exit 1"),
            "L'absence de clé Test doit faire échouer le job, pas produire un résumé",
        )
        assertTrue(
            publish.contains("cle-test-dediee"),
            "Le job doit refuser un APK signé autrement que par la clé Test",
        )
    }

    @Test
    fun `la publication Release échoue explicitement sans clé Release`() {
        val release = job("release")
        assertTrue(
            release.contains("Exiger la clé de signature Release") && release.contains("exit 1"),
            "Un tag de version sans clé Release doit échouer au lieu de ne rien publier",
        )
    }

    @Test
    fun `la clé Release n'est jamais exposée aux jobs Test`() {
        for (name in listOf("build", "publish-test")) {
            assertEquals(
                emptyList(),
                releaseSecrets(job(name)),
                "Le job « $name » ne doit référencer aucun secret de la clé Release",
            )
        }
    }

    @Test
    fun `la clé Test n'est jamais exposée au job Release`() {
        assertEquals(
            emptyList(),
            testSecrets(job("release")),
            "Le job Release ne doit référencer aucun secret de la clé Test",
        )
    }

    @Test
    fun `les deux certificats sont comparés pour interdire une clé commune`() {
        assertTrue(
            job("publish-test").contains("RAVENEMU_RELEASE_CERT_SHA256"),
            "La publication Test doit refuser le certificat Release",
        )
        assertTrue(
            job("release").contains("RAVENEMU_TEST_CERT_SHA256"),
            "La publication Release doit refuser le certificat Test",
        )
    }

    @Test
    fun `la provenance publiée couvre les quatre éléments exigés`() {
        val build = job("build")
        for (champ in listOf("apk_sha256", "cert_sha256", "commit", "package")) {
            assertTrue(champ in build, "La provenance de l'APK Test doit publier « $champ »")
        }
        assertTrue(
            job("publish-test").contains("SHA-256 du certificat de signature"),
            "Les notes de la préversion Test doivent porter l'empreinte du certificat",
        )
    }

    @Test
    fun `aucun keystore ni mot de passe n'est versionné`() {
        val suspects = WorkflowFile.repositoryRoot.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" && it.name != ".gradle" }
            .filter { it.isFile }
            .filter { it.extension in setOf("jks", "keystore", "p12", "pfx") }
            .map { it.relativeTo(WorkflowFile.repositoryRoot).path }
            .toList()
        assertEquals(emptyList(), suspects, "Aucun keystore ne doit être présent dans le dépôt")

        assertFalse(
            appBuildFile.contains("storePassword = \""),
            "Aucun mot de passe ne doit être écrit en clair dans le build",
        )
    }
}
