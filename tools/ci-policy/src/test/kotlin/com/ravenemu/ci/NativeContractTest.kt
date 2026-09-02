package com.ravenemu.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ce que le pont natif transporte, et qui doit coïncider des deux côtés.
 *
 * Deux énumérations traversent la frontière entre Kotlin et C++, et aucune des
 * deux ne transporte de nom : ce sont des nombres. Une valeur ajoutée d'un seul
 * côté, ou insérée au milieu, ne casse rien à la compilation ; elle décale ou
 * détourne silencieusement le sens des autres. Aucun test de cœur ne le
 * verrait, chacun n'observant que son propre côté du pont.
 *
 * Les **touches** passent par leur rang : `button.ordinal` devient une valeur de
 * `ravenemu::Button`. Un décalage fait obtenir une touche pour une autre.
 *
 * Les **consoles** passent par leur identifiant persisté, qui sert à la fois à
 * choisir la fabrique de moteur et à marquer les états enregistrés. Une valeur
 * différente fait construire le moteur d'une autre console, ou relire un état
 * pour elle.
 */
class NativeContractTest {

    private val racine = WorkflowFile.repositoryRoot

    private val enumerationNative: List<String> = valeurs(
        fichier = File(racine, "cores/common/include/ravenemu/core.hpp"),
        ouverture = "enum class Button : std::uint8_t {",
        fermeture = "};",
    )

    private val enumerationKotlin: List<String> = valeurs(
        fichier = File(racine, EMULATOR_BUTTON),
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

    @Test
    fun `chaque console porte le meme identifiant des deux cotes`() {
        // Le pont transporte l'identifiant persisté d'une console pour choisir
        // la fabrique de moteur, et ce même identifiant s'écrit dans les états
        // enregistrés. Les deux déclarations doivent donc coïncider valeur par
        // valeur : une console ajoutée d'un seul côté ferait échouer la création
        // du moteur, une valeur différente ferait relire un état pour la
        // mauvaise console.
        val natives = valeursNommees(
            fichier = File(racine, "cores/common/include/ravenemu/core.hpp"),
            ouverture = "enum class Console : std::uint8_t {",
            fermeture = "};",
        )
        // La déclaration ne se referme pas forcément sur l'identifiant : une
        // console peut porter d'autres champs après lui, et sur d'autres
        // lignes. Ce qui est lu est le nom et l'identifiant, pas la forme de
        // l'entrée.
        val kotlin = Regex(
            """^\s*([A-Z_]+)\(\s*".*?",\s*setOf\([^)]*\),\s*storageId\s*=\s*(\d+)""",
            RegexOption.MULTILINE,
        )
            .findAll(File(racine, CONSOLE_TYPE).readText())
            .associate { it.groupValues[1].lowercase() to it.groupValues[2].toInt() }

        assertTrue(natives.isNotEmpty(), "Aucune console lue côté natif")
        assertEquals(
            natives,
            kotlin,
            "Les identifiants de console doivent coïncider de part et d'autre du pont",
        )
    }

    @Test
    fun `le pont sait construire chaque console declaree`() {
        // Une console déclarée que le pont ne construit pas se voit à
        // l'exécution seulement, sous la forme « Console RavenEmu inconnue »,
        // et seulement si quelqu'un possède un jeu de cette console.
        val pont = File(racine, "native/jni/src/jni_bridge.cpp").readText()
        val absentes = valeursNommees(
            fichier = File(racine, "cores/common/include/ravenemu/core.hpp"),
            ouverture = "enum class Console : std::uint8_t {",
            fermeture = "};",
        ).keys.filterNot { "ravenemu::Console::$it" in pont }
        assertEquals(emptyList(), absentes, "Consoles déclarées que le pont ne construit pas")
    }

    /**
     * Les valeurs d'une énumération C++ avec leur valeur explicite.
     *
     * Contrairement aux touches, une console **porte** son identifiant : c'est
     * lui qui traverse le pont, et le lire est tout l'intérêt.
     */
    private fun valeursNommees(
        fichier: File,
        ouverture: String,
        fermeture: String,
    ): Map<String, Int> {
        val brutes = lignesDe(fichier, ouverture, fermeture)
            .map { it.substringBefore("//").trim().removeSuffix(",").trim() }
            .filter { it.isNotEmpty() }
        return brutes.associate { entree ->
            val nom = entree.substringBefore("=").trim().lowercase()
            val valeur = entree.substringAfter("=", "").trim().toIntOrNull()
            requireNotNull(valeur) { "La valeur de « $nom » doit être écrite en toutes lettres" }
            nom to valeur
        }
    }

    /** Les valeurs déclarées entre [ouverture] et [fermeture], commentaires ôtés. */
    private fun valeurs(fichier: File, ouverture: String, fermeture: String): List<String> =
        lignesDe(fichier, ouverture, fermeture)
            // Une valeur explicite (« a = 4 ») ne change pas le nom de la
            // touche : c'est lui qu'on compare, pas la façon de l'écrire.
            .map { it.substringBefore("//").substringBefore("=").trim().removeSuffix(",").trim() }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }

    /** Les lignes brutes du corps d'une énumération. */
    private fun lignesDe(fichier: File, ouverture: String, fermeture: String): List<String> {
        val lignes = fichier.readLines()
        val debut = lignes.indexOfFirst { it.trim() == ouverture }
        require(debut >= 0) { "« $ouverture » introuvable dans ${fichier.name}" }
        val fin = lignes.drop(debut + 1).indexOfFirst { it.trim() == fermeture }
        require(fin >= 0) { "Fin de l'énumération introuvable dans ${fichier.name}" }
        return lignes.subList(debut + 1, debut + 1 + fin)
    }

    private companion object {
        const val CONSOLE_TYPE =
            "engine/api/src/main/kotlin/com/ravenemu/emulation/api/ConsoleType.kt"
        const val EMULATOR_BUTTON =
            "engine/api/src/main/kotlin/com/ravenemu/emulation/api/EmulatorButton.kt"
    }
}
