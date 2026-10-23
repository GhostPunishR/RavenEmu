package com.ravenemu.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Règles de l'application Android qu'un simple test JVM peut vérifier. */
class AndroidPolicyTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, ".github/workflows").isDirectory }
        ?: error("Racine du dépôt introuvable depuis ${File("").absolutePath}")

    private fun text(path: String) = File(root, path).readText()

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
