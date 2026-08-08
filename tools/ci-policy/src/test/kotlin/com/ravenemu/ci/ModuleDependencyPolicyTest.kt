package com.ravenemu.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val IMPORT_PROVIDER = "import com.ravenemu.emulation.api.ConsoleProvider"
private val IMPLEMENTE_PROVIDER =
    Regex("""\)\s*:\s*ConsoleProvider\b|(?:object|class)\s+\w+\s*:\s*ConsoleProvider\b""")

/**
 * Vérifie le graphe architectural déclaré dans `settings.gradle.kts`.
 *
 * La V2 distingue le rôle du code (`engine`, `native`, `features`, `platform`)
 * de son emplacement physique pendant la migration. `includeModule` associe
 * donc un chemin Gradle à un dossier existant ; ce test lit les deux au lieu de
 * supposer que le chemin Gradle est forcément le chemin disque.
 */
class ModuleDependencyPolicyTest {

    private data class ModuleDeclare(val path: String, val directory: String)

    private val racine = WorkflowFile.repositoryRoot
    private val settings = File(racine, "settings.gradle.kts").readText()

    private val modulesDeclares: List<ModuleDeclare> = buildList {
        Regex("""includeModule\(\s*"(:[^"]+)"\s*,\s*"([^"]+)"\s*\)""")
            .findAll(settings)
            .forEach { match ->
                add(ModuleDeclare(match.groupValues[1].removePrefix(":"), match.groupValues[2]))
            }

        Regex("""(?m)^\s*include\(\s*"(:[^"]+)"\s*\)""")
            .findAll(settings)
            .forEach { match ->
                val path = match.groupValues[1].removePrefix(":")
                add(ModuleDeclare(path, path.replace(':', '/')))
            }
    }.distinctBy { it.path }

    private val modules = modulesDeclares.map { it.path }
    private val dossiers = modulesDeclares.associate { it.path to it.directory }

    private fun categorie(module: String) = module.substringBefore(':')

    private fun estAndroid(module: String): Boolean =
        module.startsWith("android:") || module.startsWith("platform:android:")

    private fun estJvmPur(module: String): Boolean =
        categorie(module) in setOf("engine", "features", "native", "core", "tools")

    private fun dossierModule(module: String): File =
        File(racine, dossiers[module] ?: error("Module non déclaré : $module"))

    private fun buildFile(module: String) = File(dossierModule(module), "build.gradle.kts")

    private fun dependances(module: String): List<String> {
        val build = buildFile(module)
        if (!build.isFile) return emptyList()
        return Regex("""project\("(:[^"]+)"\)""")
            .findAll(build.readText())
            .map { it.groupValues[1].removePrefix(":") }
            .distinct()
            .toList()
    }

    private val coeursConcrets: List<String> = modules.filter { module ->
        val sources = File(dossierModule(module), "src/main")
        sources.isDirectory && sources.walkTopDown().any { fichier ->
            if (!fichier.isFile || fichier.extension != "kt") return@any false
            val texte = fichier.readText()
            IMPORT_PROVIDER in texte && IMPLEMENTE_PROVIDER.containsMatchIn(texte)
        }
    }

    private val aliasAndroid: List<String> = Regex(
        """^\s*([\w.-]+)\s*=\s*\{[^}]*module\s*=\s*"([^:"]+):([^"]+)"""",
        RegexOption.MULTILINE,
    ).findAll(
        File(racine, "gradle/libs.versions.toml").readText()
            .substringAfter("[libraries]")
            .substringBefore("\n[plugins]"),
    ).filter { match ->
        val groupe = match.groupValues[2]
        val artefact = match.groupValues[3]
        groupe.startsWith("androidx.") ||
            groupe == "androidx" ||
            groupe.startsWith("com.android") ||
            groupe.startsWith("com.google.android") ||
            artefact.endsWith("-android")
    }.map { "libs." + it.groupValues[1].replace('-', '.') }
        .toList()

    @Test
    fun `les modules declares sont rattaches a une responsabilite connue`() {
        val connues = setOf("engine", "native", "features", "core", "platform", "android", "tools")
        val inconnus = modules.filterNot { categorie(it) in connues }
        assertEquals(emptyList(), inconnus, "Responsabilités attendues : $connues")
        assertTrue(modules.size >= 10, "Inventaire suspect : ${modules.size} modules trouvés")
    }

    @Test
    fun `les dossiers de modules declares existent et possedent un build`() {
        val absents = modules.filterNot { buildFile(it).isFile }
        assertEquals(emptyList(), absents, "Modules sans build.gradle.kts")
    }

    @Test
    fun `aucun module JVM pur ne depend de la plateforme Android`() {
        val fautifs = modules.filter(::estJvmPur)
            .flatMap { module -> dependances(module).map { module to it } }
            .filter { (_, cible) -> estAndroid(cible) }
            .map { (module, cible) -> "$module → $cible" }
        assertEquals(emptyList(), fautifs, "Une couche indépendante dépend d'Android")
    }

    @Test
    fun `engine api ne depend d'aucun coeur concret`() {
        val fautifs = dependances("engine:api").filter { it in coeursConcrets }
        assertEquals(emptyList(), fautifs, "engine:api dépend d'un cœur concret")
    }

    @Test
    fun `un coeur concret ne depend pas d'un autre coeur concret`() {
        assertTrue(coeursConcrets.size >= 2, "Cœurs détectés : $coeursConcrets")
        val fautifs = coeursConcrets
            .flatMap { coeur -> dependances(coeur).map { coeur to it } }
            .filter { (coeur, cible) -> cible in coeursConcrets && cible != coeur }
            .map { (a, b) -> "$a → $b" }
        assertEquals(emptyList(), fautifs, "Deux adaptateurs de console se connaissent")
    }

    @Test
    fun `aucun module JVM pur ne declare de bibliotheque Android`() {
        assertTrue(
            listOf("libs.material", "libs.kotlinx.coroutines.android").all { it in aliasAndroid },
            "Alias Android déduits du catalogue : $aliasAndroid",
        )
        val fautifs = modules.filter(::estJvmPur).flatMap { module ->
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
