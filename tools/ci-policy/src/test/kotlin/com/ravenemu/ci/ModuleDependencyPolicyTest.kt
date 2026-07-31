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

    /**
     * Accesseurs des alias Android du catalogue, sous la forme employée dans
     * les `build.gradle.kts` (`libs.material`, `libs.kotlinx.coroutines.android`).
     *
     * Rien dans un alias ne dit qu'il est Android : c'est la coordonnée qui le
     * dit. Un artefact est retenu si son groupe est celui d'AndroidX, d'AGP ou
     * des bibliothèques Android de Google, ou si son nom porte le suffixe
     * `-android` — la variante Android d'une bibliothèque par ailleurs
     * multiplateforme, comme les coroutines.
     */
    private val aliasAndroid: List<String> = Regex("""^\s*([\w.-]+)\s*=\s*\{[^}]*module\s*=\s*"([^:"]+):([^"]+)"""", RegexOption.MULTILINE)
        .findAll(
            File(racine, "gradle/libs.versions.toml").readText()
                .substringAfter("[libraries]")
                .substringBefore("\n[plugins]")
        )
        .filter { correspondance ->
            val (groupe, artefact) = correspondance.destructured.toList().drop(1)
            groupe.startsWith("androidx.") ||
                groupe == "androidx" ||
                groupe.startsWith("com.android") ||
                groupe.startsWith("com.google.android") ||
                artefact.endsWith("-android")
        }
        .map { "libs." + it.groupValues[1].replace('-', '.') }
        .toList()

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
        //
        // La règle porte sur les seuls cœurs concrets. Exiger l'absence de
        // *toute* dépendance de projet interdirait par avance un découpage du
        // contrat lui-même, ce que personne n'a demandé et que rien ne motive.
        val fautifs = dependances("core:emulation-api").filter { it in coeursConcrets }
        assertEquals(
            emptyList(),
            fautifs,
            "emulation-api dépend d'un cœur concret : la dépendance s'inverse",
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
        // Une dépendance Android dans `core/` ou `tools/` rendrait le module
        // inconstructible sans SDK — et la règle ne se verrait qu'à la première
        // machine sans SDK.
        //
        // Chercher « androidx » ne suffisait pas : `libs.material` et
        // `libs.kotlinx.coroutines.android` sont tout aussi Android et
        // passaient. La liste des alias concernés est donc déduite du
        // catalogue, seule source de vérité sur ce que chaque alias désigne.
        // Garde-fou sur la dérivation elle-même : ce sont les deux alias que la
        // recherche textuelle laissait passer. S'ils cessaient d'être détectés,
        // la règle redeviendrait silencieusement inopérante.
        assertTrue(
            listOf("libs.material", "libs.kotlinx.coroutines.android").all { it in aliasAndroid },
            "Alias Android déduits du catalogue : $aliasAndroid",
        )
        val fautifs = modules
            .filter { categorie(it) in setOf("core", "tools") }
            .flatMap { module ->
                val texte = buildFile(module).readText()
                val alias = aliasAndroid.filter { accesseur ->
                    Regex(Regex.escape(accesseur) + """(?![\w.])""").containsMatchIn(texte)
                }
                val brut = listOf("androidx", "libs.plugins.android", "com.android.")
                    .filter { it in texte }
                (alias + brut).map { "$module → $it" }
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
