package com.ravenemu.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Import du contrat de console.
 *
 * Le module qui **déclare** l'interface ne l'importe jamais : exiger l'import
 * suffit à l'écarter, sans avoir à le nommer.
 */
private const val IMPORT_PROVIDER = "import com.ravenemu.emulation.api.ConsoleProvider"

/**
 * Déclaration d'un type qui **implémente** `ConsoleProvider`.
 *
 * Les deux formes existent dans le dépôt : une déclaration d'une seule ligne, et
 * une déclaration à constructeur multiligne dont la clause d'héritage suit la
 * parenthèse fermante. Chercher la simple mention du nom désignerait en outre
 * les modules qui reçoivent un provider en paramètre.
 */
private val IMPLEMENTE_PROVIDER =
    Regex("""\)\s*:\s*ConsoleProvider\b|(?:object|class)\s+\w+\s*:\s*ConsoleProvider\b""")

/**
 * Règles de dépendance entre modules.
 *
 * Le rangement en `core/`, `android/` et `tools/` exprime une architecture en
 * couches, mais rien ne la faisait respecter : un `implementation(project(...))`
 * mal placé compilait sans broncher, et la couche ne se serait défaite qu'à
 * l'usage — le jour où un cœur d'émulation aurait cessé d'être testable sans
 * SDK Android.
 *
 * Les règles sont déduites des modules **réellement déclarés** : la liste vient
 * de `settings.gradle.kts`, et les dépendances des `build.gradle.kts`. Aucun
 * inventaire n'est recopié ici, sans quoi il faudrait le tenir à jour et il
 * finirait par mentir.
 */
class ModuleDependencyPolicyTest {

    private val racine = WorkflowFile.repositoryRoot

    /** Chemins de projet déclarés, par exemple `core:gba-core`. */
    private val modules: List<String> = Regex("""include\("(:[^"]+)"\)""")
        .findAll(File(racine, "settings.gradle.kts").readText())
        .map { it.groupValues[1].removePrefix(":") }
        .toList()

    private fun categorie(module: String) = module.substringBefore(':')

    private fun buildFile(module: String) =
        File(racine, module.replace(':', File.separatorChar) + "/build.gradle.kts")

    /** Modules dont dépend [module], via `project(":…")`. */
    private fun dependances(module: String): List<String> =
        Regex("""project\("(:[^"]+)"\)""")
            .findAll(buildFile(module).readText())
            .map { it.groupValues[1].removePrefix(":") }
            .distinct()
            .toList()

    /**
     * Cœurs concrets : les modules de `core/` qui fournissent une console.
     *
     * Ils sont reconnus à ce qu'ils déclarent — un `ConsoleProvider` — et non à
     * une liste de noms, pour qu'un cœur ajouté demain soit couvert sans que
     * personne n'ait à y penser.
     */
    private val coeursConcrets: List<String> = modules
        .filter { categorie(it) == "core" }
        .filter { module ->
            val sources = File(racine, module.replace(':', File.separatorChar) + "/src/main")
            sources.isDirectory && sources.walkTopDown().any { fichier ->
                if (!fichier.isFile || fichier.extension != "kt") return@any false
                val texte = fichier.readText()
                IMPORT_PROVIDER in texte && IMPLEMENTE_PROVIDER.containsMatchIn(texte)
            }
        }

    @Test
    fun `les modules declares sont tous rattaches a une categorie connue`() {
        val inconnus = modules.filterNot { categorie(it) in setOf("core", "android", "tools") }
        assertEquals(emptyList(), inconnus, "Catégories attendues : core, android, tools")
        assertTrue(modules.size >= 6, "Inventaire suspect : ${modules.size} modules trouvés")
    }

    @Test
    fun `aucun module de core ne depend d'un module android`() {
        // C'est la règle qui garantit que les cœurs restent testables sans SDK.
        val fautifs = modules.filter { categorie(it) == "core" }
            .flatMap { module -> dependances(module).map { module to it } }
            .filter { (_, cible) -> categorie(cible) == "android" }
            .map { (m, c) -> "$m → $c" }
        assertEquals(emptyList(), fautifs, "Un module core dépend d'un module Android")
    }

    @Test
    fun `emulation-api ne depend d'aucun coeur concret`() {
        // C'est le contrat que les cœurs implémentent : s'il connaissait l'un
        // d'eux, la dépendance s'inverserait et le contrat cesserait d'en être un.
        val dependances = dependances("core:emulation-api")
        assertEquals(
            emptyList(),
            dependances,
            "emulation-api doit rester sans dépendance de projet",
        )
    }

    @Test
    fun `un coeur concret ne depend pas d'un autre coeur concret`() {
        assertTrue(coeursConcrets.size >= 2, "Cœurs détectés : $coeursConcrets")
        val fautifs = coeursConcrets
            .flatMap { coeur -> dependances(coeur).map { coeur to it } }
            .filter { (coeur, cible) -> cible in coeursConcrets && cible != coeur }
            .map { (a, b) -> "$a → $b" }
        assertEquals(emptyList(), fautifs, "Deux cœurs d'émulation se connaissent")
    }

    @Test
    fun `aucun module JVM pur ne declare de dependance Android`() {
        // Une dépendance `androidx` ou le plugin AGP dans `core/` ou `tools/`
        // rendrait le module inconstructible sans SDK — et la règle ne se
        // verrait qu'à la première machine sans SDK.
        val fautifs = modules
            .filter { categorie(it) in setOf("core", "tools") }
            .filter { module ->
                val texte = buildFile(module).readText()
                "androidx" in texte ||
                    "libs.plugins.android" in texte ||
                    "com.android." in texte
            }
        assertEquals(emptyList(), fautifs, "Dépendance Android dans un module JVM pur")
    }

    @Test
    fun `le graphe des modules ne contient aucun cycle`() {
        // Gradle refuserait un cycle de projets, mais le message arrive tard et
        // reste obscur. Le nommer ici le rend lisible, et le test couvre aussi
        // les cycles qui ne passeraient que par une configuration particulière.
        val visites = mutableSetOf<String>()
        val pile = mutableListOf<String>()
        val cycles = mutableListOf<String>()

        fun parcourir(module: String) {
            if (module in pile) {
                cycles += (pile.dropWhile { it != module } + module).joinToString(" → ")
                return
            }
            if (!visites.add(module)) return
            pile += module
            dependances(module).filter { it in modules }.forEach(::parcourir)
            pile.removeAt(pile.lastIndex)
        }

        modules.forEach(::parcourir)
        assertEquals(emptyList(), cycles, "Dépendance circulaire entre modules")
    }
}
