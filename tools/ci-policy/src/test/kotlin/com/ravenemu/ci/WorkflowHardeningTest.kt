package com.ravenemu.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Garde-fous génériques du workflow : permissions, épinglage, secrets. */
class WorkflowHardeningTest {

    private val workflow = WorkflowFile.androidWorkflow()

    @Test
    fun `les permissions par défaut sont en lecture seule`() {
        val header = workflow.text.substringBefore("jobs:")
        assertTrue(
            Regex("^permissions:\\s*\\n\\s+contents: read\\s*$", RegexOption.MULTILINE)
                .containsMatchIn(header),
            "Le workflow doit démarrer avec « permissions: contents: read »",
        )
    }

    @Test
    fun `seul le job de publication Test obtient l'écriture`() {
        for ((name, block) in workflow.jobs) {
            val declaration = block.joinToString("\n")
            if ("contents: write" !in declaration) continue
            assertEquals(
                "publish-test",
                name,
                "Seule la publication de la préversion Test justifie « contents: write »",
            )
        }
    }

    @Test
    fun `les actions tierces sont épinglées par SHA`() {
        val references = workflow.actionReferences
        assertTrue(references.isNotEmpty(), "Aucune action détectée : lecture du workflow cassée")
        for (reference in references) {
            if (reference.startsWith("./")) continue
            val version = reference.substringAfterLast('@', missingDelimiterValue = "")
            if (!Regex("^[0-9a-f]{40}$").matches(version)) {
                fail("Action non épinglée par SHA : $reference")
            }
        }
    }

    @Test
    fun `aucun secret n'est écrit dans un journal`() {
        // Un secret ne doit apparaître qu'en affectation de variable
        // d'environnement, jamais dans un `echo` ou une interpolation shell.
        val fautes = workflow.text.lines().withIndex().filter { (_, line) ->
            val trimmed = line.trim()
            "secrets." in trimmed &&
                (trimmed.startsWith("echo") || trimmed.startsWith("printf") ||
                    trimmed.startsWith("run:"))
        }
        assertEquals(
            emptyList(),
            fautes.map { "ligne ${it.index + 1} : ${it.value.trim()}" },
            "Un secret ne doit jamais être passé à une commande d'affichage",
        )
    }

    @Test
    fun `le checkout ne conserve pas les identifiants git`() {
        val checkouts = Regex("uses: actions/checkout@[0-9a-f]{40}[^\\n]*\\n((?:\\s+[^\\n]*\\n)*)")
            .findAll(workflow.text)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(checkouts.isNotEmpty(), "Aucun checkout détecté : lecture du workflow cassée")
        for (options in checkouts) {
            assertTrue(
                "persist-credentials: false" in options,
                "Chaque checkout doit désactiver la persistance des identifiants",
            )
        }
    }

    @Test
    fun `aucun APK Debug n'est publié`() {
        val uploads = workflow.text.substringAfter("jobs:")
        assertTrue(
            "apk/debug" !in uploads,
            "La CI ne publie pas d'APK Debug : seul l'APK Test optimisé est diffusé",
        )
    }
}
