package com.ravenemu.ci

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Non-régression de la politique de publication.
 *
 * Ces tests évaluent réellement les conditions `if:` du workflow pour une série
 * de déclenchements simulés. Avant correctif, le job Release portait
 * `if: github.event_name == 'push'` : il se lançait donc — et construisait des
 * livrables signés — sur n'importe quel push de branche de travail. Les cas
 * « branche ordinaire » ci-dessous échouent avec cette condition et passent
 * avec la condition corrigée.
 */
class PublicationPolicyTest {

    private val workflow = WorkflowFile.androidWorkflow()

    private fun jobCondition(job: String): String {
        val block = workflow.jobs[job] ?: error("Job « $job » absent du workflow")
        return assertNotNull(
            workflow.scalar(block, "if", indent = 4),
            "Le job « $job » doit porter une condition explicite",
        )
    }

    private fun push(ref: String) = ConditionEvaluator.Context(
        mapOf("github.event_name" to "push", "github.ref" to ref),
    )

    private fun dispatch(publierRelease: Boolean) = ConditionEvaluator.Context(
        mapOf(
            "github.event_name" to "workflow_dispatch",
            "github.ref" to "refs/heads/main",
            "inputs.publier_release" to publierRelease,
        ),
    )

    private val pullRequest = ConditionEvaluator.Context(
        mapOf(
            "github.event_name" to "pull_request",
            "github.ref" to "refs/pull/42/merge",
        ),
    )

    private fun runs(job: String, context: ConditionEvaluator.Context): Boolean =
        ConditionEvaluator.evaluate(jobCondition(job), context)

    @Test
    fun `la publication Release ignore les pushes de branche`() {
        val condition = jobCondition("release")
        assertFalse(runs("release", push("refs/heads/main")), condition)
        assertFalse(runs("release", push("refs/heads/develop")), condition)
        assertFalse(runs("release", push("refs/heads/claude/une-branche")), condition)
        assertFalse(runs("release", pullRequest), condition)
    }

    @Test
    fun `la publication Release accepte un tag de version`() {
        assertTrue(runs("release", push("refs/tags/v1.0.0")))
        assertTrue(runs("release", push("refs/tags/v0.2.0-rc1")))
    }

    @Test
    fun `un tag hors versionnement ne publie rien`() {
        assertFalse(runs("release", push("refs/tags/test-latest")))
        assertFalse(runs("release", push("refs/tags/nightly")))
    }

    @Test
    fun `la publication Release manuelle doit être demandée explicitement`() {
        assertTrue(runs("release", dispatch(publierRelease = true)))
        assertFalse(runs("release", dispatch(publierRelease = false)))
    }

    @Test
    fun `la publication Release passe par un environnement protégé`() {
        val block = workflow.jobs.getValue("release")
        assertEquals(
            "release",
            workflow.scalar(block, "environment", indent = 4),
            "L'environnement GitHub porte l'approbation manuelle et l'accès aux secrets",
        )
    }

    @Test
    fun `l'APK Test n'est publié que depuis main`() {
        assertTrue(runs("publish-test", push("refs/heads/main")))
        assertFalse(runs("publish-test", push("refs/heads/develop")))
        assertFalse(runs("publish-test", push("refs/heads/claude/une-branche")))
        assertFalse(runs("publish-test", push("refs/tags/v1.0.0")))
        assertFalse(runs("publish-test", pullRequest))
    }
}
