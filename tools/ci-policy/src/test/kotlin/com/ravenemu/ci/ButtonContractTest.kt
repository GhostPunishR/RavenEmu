package com.ravenemu.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Alignement des deux énumérations de touches, de part et d'autre du pont natif.
 *
 * Le pont ne transporte pas un nom mais un rang : `button.ordinal` côté Kotlin
 * devient une valeur de `ravenemu::Button` côté C++. Les deux listes doivent
 * donc nommer les mêmes touches **dans le même ordre**. Une touche ajoutée d'un
 * seul côté, ou insérée au milieu de l'une des deux, ne casse rien à la
 * compilation : elle décale silencieusement toutes les suivantes, et le joueur
 * appuie sur une touche pour en obtenir une autre. Aucun test de cœur ne le
 * verrait, chacun n'observant que son propre côté.
 */
class ButtonContractTest {

    private val racine = WorkflowFile.repositoryRoot

    private val enumerationNative: List<String> = valeurs(
        fichier = File(racine, "cores/common/include/ravenemu/core.hpp"),
        ouverture = "enum class Button : std::uint8_t {",
        fermeture = "};",
    )

    private val enumerationKotlin: List<String> = valeurs(
        fichier = File(racine, "engine/api/src/main/kotlin/com/ravenemu/emulation/api/EmulatorButton.kt"),
        ouverture = "enum class EmulatorButton {",
        fermeture = "}",
    )

    @Test
    fun `les deux enumerations nomment les memes touches dans le meme ordre`() {
        assertTrue(enumerationNative.isNotEmpty(), "Aucune touche lue côté natif")
        assertEquals(
            enumerationNative,
            enumerationKotlin,
            "Le pont natif transporte un rang : les deux listes doivent coïncider",
        )
    }

    @Test
    fun `le pont refuse tout rang au-dela de la derniere touche`() {
        // La borne est écrite avec le nom de la dernière touche. Ajouter une
        // touche sans la déplacer laisserait passer un rang que le cœur
        // n'interprète pas.
        val pont = File(racine, "native/jni/src/jni_bridge.cpp").readText()
        val derniere = enumerationNative.last()
        assertTrue(
            "ravenemu::Button::$derniere" in pont,
            "Le pont doit borner les rangs sur « $derniere », la dernière touche déclarée",
        )
    }

    /** Les valeurs déclarées entre [ouverture] et [fermeture], commentaires ôtés. */
    private fun valeurs(fichier: File, ouverture: String, fermeture: String): List<String> {
        val lignes = fichier.readLines()
        val debut = lignes.indexOfFirst { it.trim() == ouverture }
        require(debut >= 0) { "« $ouverture » introuvable dans ${fichier.name}" }
        val fin = lignes.drop(debut + 1).indexOfFirst { it.trim() == fermeture }
        require(fin >= 0) { "Fin de l'énumération introuvable dans ${fichier.name}" }
        return lignes.subList(debut + 1, debut + 1 + fin)
            // Une valeur explicite (« a = 4 ») ne change pas le nom de la
            // touche : c'est lui qu'on compare, pas la façon de l'écrire.
            .map { it.substringBefore("//").substringBefore("=").trim().removeSuffix(",").trim() }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
    }
}
