// Racine du build RavenEmu.
//
// Les plugins sont déclarés ici et appliqués uniquement dans les modules qui
// les utilisent. Le graphe de projets exprime désormais les responsabilités
// (`engine`, `native`, `features`, `platform`) plutôt que la technologie seule.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("jvmTest") {
    group = "verification"
    description = "Exécute les tests des modules indépendants du SDK Android."
    dependsOn(
        provider {
            subprojects
                .filter { project ->
                    val purePrefix = listOf(
                        ":engine:",
                        ":features:",
                        ":native:",
                        ":core:",
                        ":tools:",
                    ).any(project.path::startsWith)
                    purePrefix && project.file("build.gradle.kts").isFile
                }
                .map { "${it.path}:test" }
        }
    )
}
