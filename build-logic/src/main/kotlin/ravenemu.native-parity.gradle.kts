import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

/**
 * Convention des modules qui comparent leur implémentation Kotlin de référence
 * au cœur C++ réellement livré.
 *
 * Un test de parité ne vaut que s'il s'exécute contre la bibliothèque native
 * **du dépôt**. Elle est donc construite ici, par CMake, et son chemin est
 * remis aux tests par la propriété que lit `NativeCoreBridge`.
 *
 * La construction est une dépendance dure de `test`, sans repli silencieux :
 * un test de parité qu'on laisse s'ignorer faute de bibliothèque redonnerait
 * exactement l'illusion qu'il est censé dissiper — une suite verte qui ne teste
 * pas le code livré. Sans CMake, la tâche échoue et le dit.
 */

val sourcesNatives = rootProject.layout.projectDirectory.dir("cores")
val dossierBuild = layout.buildDirectory.dir("native-parity")

val configurerCoeursNatifs by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure la construction CMake des cœurs natifs pour les tests de parité."
    inputs.dir(sourcesNatives).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(dossierBuild.map { it.file("CMakeCache.txt") })
    commandLine(
        "cmake",
        "-S", sourcesNatives.asFile.absolutePath,
        "-B", dossierBuild.get().asFile.absolutePath,
        "-DRAVENEMU_BUILD_JNI=ON",
        "-DCMAKE_BUILD_TYPE=Release",
    )
}

val construireCoeursNatifs by tasks.registering(Exec::class) {
    group = "build"
    description = "Construit la bibliothèque native que les tests de parité chargent."
    dependsOn(configurerCoeursNatifs)
    inputs.dir(sourcesNatives).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(dossierBuild.map { it.dir("bridge") })
    commandLine("cmake", "--build", dossierBuild.get().asFile.absolutePath, "--parallel")
}

tasks.withType<Test>().configureEach {
    dependsOn(construireCoeursNatifs)
    // Le nom du fichier dépend du système : il est résolu à l'exécution, une
    // fois la bibliothèque produite, et non pendant la configuration.
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            val produite = dossierBuild.get().asFile.walkTopDown().firstOrNull { fichier ->
                fichier.isFile &&
                    fichier.name.startsWith("libravenemu_native") &&
                    fichier.extension in setOf("so", "dylib", "dll")
            } ?: error(
                "Bibliothèque native introuvable sous ${dossierBuild.get().asFile}. " +
                    "Les tests de parité comparent le Kotlin au C++ livré : " +
                    "sans elle, ils ne compareraient rien."
            )
            listOf("-Dravenemu.native.library=${produite.absolutePath}")
        }
    )
}
