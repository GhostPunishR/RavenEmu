// Racine du build RavenEmu.
//
// AGP 9 fournit Kotlin intégré aux modules Android. Le plugin Kotlin Android
// n'est donc plus chargé ; Kotlin JVM reste versionné explicitement pour les
// modules moteur et les plugins de convention du dépôt.
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
