// Racine du build RavenEmu.
//
// AGP 9 fournit Kotlin intégré aux modules Android. Le plugin Kotlin Android
// n'est donc plus chargé ; Kotlin JVM reste versionné explicitement pour les
// modules moteur et les plugins de convention du dépôt.
//
// Les quatre déclarations ci-dessous doivent rester **ici**, à la racine, et
// non dans le bloc `plugins` de `pluginManagement`. Ce déplacement paraît
// pourtant gagnant : il éviterait de résoudre AGP depuis le dépôt Google sur
// une machine sans SDK Android, où `settings.gradle.kts` n'inclut aucun module
// qui l'applique. Il a été essayé, et la construction Android échoue :
//
//     Failed to apply plugin 'com.android.internal.application'
//       > Could not create an instance of type ...KotlinAndroidTarget
//          > com/android/build/gradle/api/BaseVariant
//
// AGP 9 applique Kotlin lui-même sur les modules Android. Déclarés ensemble
// ici, AGP et le greffon Kotlin sont chargés dans le classloader de la racine,
// et Kotlin y voit les classes d'AGP. Déclaré dans `pluginManagement`, AGP est
// chargé ailleurs : le Kotlin de la racine ne le voit plus, et l'application du
// greffon Android casse. Cette unicité de classloader est la même que celle que
// `build-logic/build.gradle.kts` documente pour ses conventions.
//
// Le coût assumé est donc une résolution d'AGP même sans SDK Android.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("jvmTest") {
    group = "verification"
    description = "Exécute les tests des modules JVM purs."
    val prefixes = listOf(":engine:", ":features:library", ":features:skins", ":native:jni", ":tools:")
    dependsOn(
        provider {
            subprojects
                .filter { p -> prefixes.any { prefix -> p.path.startsWith(prefix) } }
                .filter { it.tasks.names.contains("test") }
                .map { "${it.path}:test" }
        }
    )
}
