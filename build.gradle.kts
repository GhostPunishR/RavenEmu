// Racine du build RavenEmu.
//
// AGP 9 fournit Kotlin intégré aux modules Android. Le plugin Kotlin Android
// n'est donc plus chargé ; Kotlin JVM reste versionné explicitement pour les
// modules moteur et les plugins de convention du dépôt.
// Les greffons Android ne sont plus déclarés ici : leur version est enregistrée
// dans le `pluginManagement` de `settings.gradle.kts`, qui la mappe sans la
// résoudre. Les déclarer ici en `apply false` forçait leur téléchargement à
// chaque configuration, y compris sur une machine sans SDK Android où aucun
// module ne les applique.
plugins {
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
