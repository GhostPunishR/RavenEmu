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

    private fun files(path: String, vararg extensions: String) =
        File(root, path).walkTopDown().filter { it.isFile && it.extension in extensions }.toList()

    @Test
    fun `chaque méthode native Java a son symbole JNI, et inversement`() {
        // Un symbole manquant ne se voit qu'au lancement sur le téléphone
        // (UnsatisfiedLinkError) : une classe déplacée doit renommer ses symboles.
        val native = Regex("""\bnative\s+[\w.<>\[\]]+\s+(\w+)\s*\(""")
        // Sans commentaires ni chaînes : « the native pacer accepts (… » n'est pas une déclaration.
        val noise = Regex("""(?s)/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"""")
        val expected = files("android/src/main/java", "java").flatMap { file ->
            val source = file.readText().replace(noise, " ")
            val names = native.findAll(source).map { it.groupValues[1] }.toList()
            if (names.isEmpty()) return@flatMap emptyList()
            val pkg = Regex("""^package\s+([\w.]+)\s*;""", RegexOption.MULTILINE).find(source)!!.groupValues[1]
            val owner = file.nameWithoutExtension
            // JNI : un « _ » du nom s'écrit « _1 ».
            names.map { "Java_${pkg.replace('.', '_')}_${owner}_${it.replace("_", "_1")}" }
        }.toSet()
        val defined = files("android/src/main/cpp", "c", "cpp").flatMap { file ->
            Regex("""\b(Java_com_ravenemu_app_\w+)\s*\(""").findAll(file.readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue(expected.isNotEmpty() && defined.isNotEmpty(), "Aucune méthode native trouvée")
        assertEquals(emptySet(), expected - defined, "Méthodes Java sans symbole JNI")
        assertEquals(emptySet(), defined - expected, "Symboles JNI sans méthode Java")
    }

    @Test
    fun `l'accès au réseau local est déclaré exactement quand l'application cible Android 17`() {
        // https://developer.android.com/privacy-and-security/local-network-permission :
        // obligatoire en ciblant le SDK 37, à ne pas déclarer avant.
        val target = Regex("""targetSdk\s*=\s*(\d+)""").find(text("android/build.gradle.kts"))
            ?.groupValues?.get(1)?.toInt() ?: error("targetSdk introuvable dans android/build.gradle.kts")
        val manifest = text("android/src/main/AndroidManifest.xml").replace(Regex("(?s)<!--.*?-->"), "")
        val declared = Regex("""uses-permission[^>]*android\.permission\.ACCESS_LOCAL_NETWORK""").containsMatchIn(manifest)
        if (target >= 37) {
            assertTrue(declared, "targetSdk $target : déclarer android.permission.ACCESS_LOCAL_NETWORK (câble sans fil)")
        } else {
            assertFalse(declared, "targetSdk $target : ACCESS_LOCAL_NETWORK ne se déclare qu'à partir du SDK 37")
        }
    }
}
