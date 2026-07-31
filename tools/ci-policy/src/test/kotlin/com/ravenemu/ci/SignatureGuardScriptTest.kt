package com.ravenemu.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Exécution réelle du garde-fou de signature du job de publication Test.
 *
 * Le script du workflow est extrait tel quel puis lancé sous `bash -e`, comme
 * le fait GitHub Actions, avec une provenance fabriquée. On vérifie donc le
 * comportement du garde-fou et non la présence de mots-clés dans un fichier.
 */
class SignatureGuardScriptTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val script: String =
        WorkflowFile.androidWorkflow().stepScript("publish-test", "Vérifier l'identité de la signature Test")

    private val bash = File("/bin/bash")

    /** Lance le script avec la provenance donnée et retourne son code de sortie. */
    private fun run(
        provenance: String?,
        certAttendu: String = "",
        certRelease: String = "",
    ): Int {
        val runnerTemp = temporaryFolder.newFolder()
        if (provenance != null) {
            File(runnerTemp, "ravenemu-test").apply { mkdirs() }
                .resolve("provenance.txt")
                .writeText(provenance)
        }
        val scriptFile = File(runnerTemp, "garde.sh").apply { writeText(script) }
        val process = ProcessBuilder(bash.path, "-e", scriptFile.path)
            .redirectErrorStream(true)
            .apply {
                environment()["RUNNER_TEMP"] = runnerTemp.path
                environment()["TEST_CERT_ATTENDU"] = certAttendu
                environment()["RELEASE_CERT"] = certRelease
            }
            .start()
        val sortie = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) assertTrue(sortie.contains("::error::"), "Sortie inattendue :\n$sortie")
        return code
    }

    private fun provenance(
        cert: String = CERT_TEST,
        source: String = "cle-test-dediee",
    ) = """
        apk_sha256=0000000000000000000000000000000000000000000000000000000000000000
        cert_sha256=$cert
        commit=abcdef0123456789
        package=com.ravenemu.app.profil
        signature_source=$source
    """.trimIndent()

    @Test
    fun `une provenance conforme est acceptée`() {
        assumeTrue(bash.canExecute())
        assertEquals(0, run(provenance(), certAttendu = CERT_TEST))
    }

    @Test
    fun `une provenance absente arrête la publication`() {
        assumeTrue(bash.canExecute())
        assertEquals(1, run(provenance = null))
    }

    @Test
    fun `un APK signé par la clé de débogage est refusé`() {
        assumeTrue(bash.canExecute())
        // C'est exactement l'ancien comportement : la variante profil était
        // signée par le keystore de débogage éphémère du runner.
        assertEquals(1, run(provenance(source = "cle-debogage-locale")))
    }

    @Test
    fun `un APK Test signé par la clé Release est refusé`() {
        assumeTrue(bash.canExecute())
        assertEquals(1, run(provenance(cert = CERT_RELEASE), certRelease = CERT_RELEASE))
    }

    @Test
    fun `la comparaison des certificats ignore la casse`() {
        assumeTrue(bash.canExecute())
        assertEquals(1, run(provenance(cert = CERT_RELEASE.uppercase()), certRelease = CERT_RELEASE))
        assertEquals(0, run(provenance(cert = CERT_TEST.uppercase()), certAttendu = CERT_TEST))
    }

    @Test
    fun `une empreinte recopiee depuis keytool est acceptee`() {
        assumeTrue(bash.canExecute())
        // `keytool -list -v` affiche « AB:CD:EF:... » en majuscules, alors que
        // `apksigner` produit des minuscules d'un seul tenant. Les deux
        // désignent le même certificat : imposer un format de recopie ferait
        // échouer la publication pour une raison purement cosmétique.
        assertEquals(0, run(provenance(), certAttendu = formatKeytool(CERT_TEST)))
        assertEquals(
            1,
            run(provenance(cert = CERT_RELEASE), certRelease = formatKeytool(CERT_RELEASE)),
            "Le refus du certificat Release doit valoir dans les deux formats",
        )
    }

    /** Empreinte au format `keytool` : paires hexadécimales majuscules séparées. */
    private fun formatKeytool(empreinte: String): String =
        empreinte.chunked(2).joinToString(":").uppercase()

    @Test
    fun `une rotation de la clé Test arrête la publication`() {
        assumeTrue(bash.canExecute())
        assertEquals(1, run(provenance(cert = CERT_AUTRE), certAttendu = CERT_TEST))
    }

    @Test
    fun `sans empreinte de référence la publication continue en avertissant`() {
        assumeTrue(bash.canExecute())
        assertEquals(0, run(provenance(), certAttendu = ""))
    }

    private companion object {
        const val CERT_TEST = "1111111111111111111111111111111111111111111111111111111111111111"
        const val CERT_RELEASE = "2222222222222222222222222222222222222222222222222222222222222222"
        const val CERT_AUTRE = "3333333333333333333333333333333333333333333333333333333333333333"
    }
}
