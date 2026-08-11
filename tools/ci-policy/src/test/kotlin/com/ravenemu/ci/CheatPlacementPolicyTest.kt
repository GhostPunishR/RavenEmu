package com.ravenemu.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** Empêche les cheats propres à une ROM de dériver vers les réglages globaux. */
class CheatPlacementPolicyTest {
    private val root = WorkflowFile.repositoryRoot

    @Test
    fun `aucun cheat n'est ajoute aux parametres globaux`() {
        val globalSettings = listOf(
            "app/android/src/main/kotlin/com/ravenemu/app/settings/SettingsActivity.kt",
            "app/android/src/main/res/xml/preferences.xml",
            "features/settings/src/main/kotlin/com/ravenemu/settings/AppSettings.kt",
        ).map { File(root, it) }
        val offenders = globalSettings
            .filter { CHEAT.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }

        assertEquals(
            emptyList(),
            offenders,
            "Les cheats appartiennent au menu du jeu en cours, pas aux paramètres globaux",
        )
    }

    private companion object {
        val CHEAT = Regex("\\bcheats?\\b", RegexOption.IGNORE_CASE)
    }
}
