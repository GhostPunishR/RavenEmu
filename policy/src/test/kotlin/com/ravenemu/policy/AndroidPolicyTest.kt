package com.ravenemu.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Règles de l'application Android qu'un simple test JVM peut vérifier. */
class AndroidPolicyTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, ".github/workflows").isDirectory }
        ?: error("Racine du dépôt introuvable depuis ${File("").absolutePath}")

    private fun text(path: String) = File(root, path).readText()

    private fun files(path: String, extension: String) =
        File(root, path).walkTopDown().filter { it.isFile && it.extension == extension }.toList()

    @Test
    fun `chaque fonction native Kotlin a son symbole JNI, et inversement`() {
        // Un symbole manquant ne se voit qu'au lancement sur le téléphone
        // (UnsatisfiedLinkError) : un paquet déplacé doit renommer ses symboles.
        val declaration = Regex("""^(?:\w+\s+)*(?:class|object)\s+(\w+)""", RegexOption.MULTILINE)
        val external = Regex("""\bexternal\s+fun\s+(\w+)""")
        val expected = files("androidApp/src/main/kotlin", "kt").flatMap { file ->
            val source = file.readText()
            val names = external.findAll(source).map { it.groupValues[1] }.toList()
            if (names.isEmpty()) return@flatMap emptyList()
            val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(source)!!.groupValues[1]
            val owner = declaration.find(source)?.groupValues?.get(1) ?: error("Classe introuvable : $file")
            names.map { "Java_${pkg.replace('.', '_')}_${owner}_$it" }
        }.toSet()
        val defined = files("native/android", "cpp").flatMap { file ->
            val source = file.readText()
            // Symboles écrits en entier, et ceux d'une macro #define X(name) Java_..._##name.
            val direct = Regex("""\b(Java_com_ravenemu_android_\w+)\s*\(""").findAll(source).map { it.groupValues[1] }
            val macros = Regex("""#define\s+(\w+)\(name\)\s+(Java_\w+)_##name""").findAll(source).flatMap { macro ->
                val (name, prefix) = macro.destructured
                val uses = source.lines().filterNot { it.trimStart().startsWith("#define") }.joinToString("\n")
                Regex("""\b$name\((\w+)\)""").findAll(uses).map { "${prefix}_${it.groupValues[1]}" }
            }
            (direct + macros).toList()
        }.toSet()
        assertTrue(expected.isNotEmpty() && defined.isNotEmpty(), "Aucune fonction native trouvée")
        assertEquals(emptySet(), expected - defined, "Fonctions Kotlin sans symbole JNI")
        assertEquals(emptySet(), defined - expected, "Symboles JNI sans fonction Kotlin")
    }

    @Test
    fun `l'accès au réseau local est déclaré exactement quand l'application cible Android 17`() {
        // https://developer.android.com/privacy-and-security/local-network-permission :
        // obligatoire en ciblant le SDK 37, à ne pas déclarer avant.
        val target = Regex("""targetSdk\s*=\s*(\d+)""").find(text("androidApp/build.gradle.kts"))
            ?.groupValues?.get(1)?.toInt() ?: error("targetSdk introuvable dans androidApp/build.gradle.kts")
        val manifest = text("androidApp/src/main/AndroidManifest.xml").replace(Regex("(?s)<!--.*?-->"), "")
        val declared = Regex("""uses-permission[^>]*android\.permission\.ACCESS_LOCAL_NETWORK""").containsMatchIn(manifest)
        if (target >= 37) {
            assertTrue(declared, "targetSdk $target : déclarer android.permission.ACCESS_LOCAL_NETWORK (câble sans fil)")
        } else {
            assertFalse(declared, "targetSdk $target : ACCESS_LOCAL_NETWORK ne se déclare qu'à partir du SDK 37")
        }
    }
}
