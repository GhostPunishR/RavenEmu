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

val sourcesNatives = rootProject.layout.projectDirectory.dir("native")
val cmakeNatif = sourcesNatives.file("CMakeLists.txt")
val sourcesPontJni = sourcesNatives.dir("jni/cpp")
val sourcesCoeurs = rootProject.layout.projectDirectory.dir("cores")
val dossierBuild = layout.buildDirectory.dir("native-parity")

/**
 * Entrées CMake strictes.
 *
 * `native/jni` est aussi un module Gradle Java et produit `native/jni/build`.
 * Déclarer tout `native/` comme entrée ferait donc croire à Gradle que la
 * construction CMake lit les sorties de `:native:jni`, ce qui est faux et
 * déclenche à juste titre la validation des dépendances implicites de Gradle 9.
 * Les seules entrées réelles du binaire natif sont le CMake racine, le pont
 * C++ JNI et les cœurs.
 */
fun Exec.declarerSourcesNatives() {
    inputs.file(cmakeNatif).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(sourcesPontJni).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(sourcesCoeurs).withPathSensitivity(PathSensitivity.RELATIVE)
}

val configurerCoeursNatifs by tasks.registering(Exec::class) {
    group = "build"
    description = "Configure la construction CMake du pont natif et des cœurs pour les tests de parité."
    declarerSourcesNatives()
    outputs.file(dossierBuild.map { it.file("CMakeCache.txt") })
    commandLine(
        "cmake",
        "-S", sourcesNatives.asFile.absolutePath,
        "-B", dossierBuild.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
    )
}

val construireCoeursNatifs by tasks.registering(Exec::class) {
    group = "build"
    description = "Construit la bibliothèque native que les tests de parité chargent."
    dependsOn(configurerCoeursNatifs)
    declarerSourcesNatives()
    outputs.dir(dossierBuild)
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
