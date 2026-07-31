import org.gradle.accessors.dm.LibrariesForLibs
import org.gradle.api.plugins.JavaPluginExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Convention des modules JVM purs de RavenEmu : Kotlin, cible 17, et les
 * dépendances de test communes.
 *
 * Le plugin Kotlin est appliqué **impérativement**, et non par un bloc
 * `plugins { }`. Un bloc déclaratif obligerait `build-logic` à embarquer sa
 * propre copie du plugin, chargée dans un second classloader : c'est
 * exactement ce que Gradle signale en avertissant d'un chargement multiple.
 * Appliqué par identifiant, il est résolu depuis le classloader de la racine,
 * où il est déclaré une seule fois.
 *
 * Les versions restent lues dans `gradle/libs.versions.toml` : un plugin de
 * convention centralise la configuration, pas les numéros de version.
 */
apply(plugin = "org.jetbrains.kotlin.jvm")

// Les accesseurs typés du catalogue ne sont pas générés pour les scripts
// précompilés : on le récupère explicitement.
val libs = the<LibrariesForLibs>()

// Sans bloc `plugins { }`, les accesseurs typés ne sont pas générés : les
// extensions se configurent explicitement.
extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

extensions.configure<KotlinJvmProjectExtension> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    "testImplementation"(libs.kotlin.test)
    "testImplementation"(libs.junit4)
}
