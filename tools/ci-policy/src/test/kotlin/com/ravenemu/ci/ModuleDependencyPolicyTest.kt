package com.ravenemu.ci

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RavenEmu V2: a Gradle path is its physical repository path. No projectDir
 * alias may hide legacy directories behind the new architecture.
 */
class ModuleDependencyPolicyTest {
    private val root = WorkflowFile.repositoryRoot
    private val settings = File(root, "settings.gradle.kts").readText()

    private val modules: List<String> = Regex("\"(:[A-Za-z0-9:_-]+)\"")
        .findAll(settings.substringAfter("includeBuild(\"build-logic\")"))
        .map { it.groupValues[1].removePrefix(":") }
        .filter { ':' in it || it == "tools:ci-policy" }
        .distinct()
        .toList()

    private fun moduleDir(module: String) = File(root, module.replace(':', '/'))
    private fun buildFile(module: String) = File(moduleDir(module), "build.gradle.kts")
    private fun dependencies(module: String): List<String> =
        if (!buildFile(module).isFile) emptyList() else
            Regex("""project\("(:[^"]+)"\)""")
                .findAll(buildFile(module).readText())
                .map { it.groupValues[1].removePrefix(":") }
                .distinct().toList()

    private fun isAndroid(module: String): Boolean =
        buildFile(module).takeIf(File::isFile)?.readText()?.contains("com.android.") == true

    @Test
    fun `les modules Gradle correspondent exactement aux dossiers physiques`() {
        val missing = modules.filterNot { buildFile(it).isFile }
        assertEquals(emptyList(), missing, "Module déclaré sans build physique")
        assertFalse("projectDir" in settings, "La V2 ne doit plus masquer les chemins avec projectDir")
        assertFalse("includeModule" in settings, "La V2 ne doit plus utiliser d'alias de module")
    }

    @Test
    fun `les anciennes racines source ont disparu`() {
        val forbidden = listOf("core", "android", "engine/systems")
            .filter { File(root, it).exists() }
        assertEquals(emptyList(), forbidden, "Ancienne architecture encore présente")
    }

    @Test
    fun `les frontieres V2 attendues existent`() {
        val expected = listOf(
            "app/android",
            "cores/common", "cores/gb", "cores/gbc", "cores/gba",
            "native/api", "native/jni",
            "engine/api", "engine/runtime", "engine/session", "engine/state",
            "engine/save", "engine/audio", "engine/diagnostics",
            "platform/android/audio", "platform/android/renderer", "platform/android/input",
            "platform/android/storage", "platform/android/vibration", "platform/android/lifecycle",
            "features/library", "features/player", "features/settings", "features/skins",
            "features/savestates", "features/diagnostics",
        )
        val missing = expected.filterNot { File(root, it).isDirectory }
        assertEquals(emptyList(), missing, "Frontières V2 absentes")
    }

    @Test
    fun `engine reste Kotlin JVM pur et ne depend jamais d Android`() {
        val engine = modules.filter { it.startsWith("engine:") }
        val badPlugins = engine.filter { isAndroid(it) }
        val badDeps = engine.flatMap { source ->
            dependencies(source)
                .filter { it.startsWith("platform:android:") || it.startsWith("app:") }
                .map { "$source → $it" }
        }
        assertEquals(emptyList(), badPlugins, "Module engine Android")
        assertEquals(emptyList(), badDeps, "Engine dépend d'Android")
    }

    @Test
    fun `engine api reste independant du runtime`() {
        assertTrue(dependencies("engine:api").isEmpty(), "engine:api doit être la base du graphe")
        assertTrue("engine:api" in dependencies("engine:runtime"))
        assertFalse("engine:runtime" in dependencies("engine:api"))
    }

    @Test
    fun `la bibliotheque reste une feature JVM sans migration de plateforme`() {
        assertTrue(File(root, "features/library/src/main/kotlin/com/ravenemu/romlibrary").isDirectory)
        assertFalse(isAndroid("features:library"), "features:library doit rester indépendante d'Android")
        assertTrue("engine:api" in dependencies("features:library"))
        assertTrue("engine:runtime" in dependencies("features:library"))
    }

    @Test
    fun `le graphe des modules ne contient aucun cycle`() {
        val visited = mutableSetOf<String>()
        val stack = mutableListOf<String>()
        val cycles = mutableListOf<String>()
        fun visit(module: String) {
            if (module in stack) {
                cycles += (stack.dropWhile { it != module } + module).joinToString(" → ")
                return
            }
            if (!visited.add(module)) return
            stack += module
            dependencies(module).filter { it in modules }.forEach(::visit)
            stack.removeAt(stack.lastIndex)
        }
        modules.forEach(::visit)
        assertEquals(emptyList(), cycles, "Dépendance circulaire")
    }
}
