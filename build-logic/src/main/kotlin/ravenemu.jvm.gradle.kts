import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Convention des modules JVM purs de RavenEmu : Kotlin, cible 17, et les
 * dépendances de test communes.
 *
 * Ces réglages étaient recopiés dans chaque module. Au-delà de la répétition,
 * déclarer le plugin Kotlin dans plusieurs sous-projets fait avertir Gradle
 * qu'il est chargé plusieurs fois — un avertissement qui parle de rupture
 * possible du build. Le charger une seule fois, ici, l'élimine.
 *
 * Les versions restent lues dans `gradle/libs.versions.toml` : un plugin de
 * convention centralise la configuration, pas les numéros de version.
 */
plugins {
    kotlin("jvm")
}

// Les accesseurs typés du catalogue ne sont pas générés pour les scripts
// précompilés : on le récupère explicitement.
val libs = the<LibrariesForLibs>()

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    "testImplementation"(libs.kotlin.test)
    "testImplementation"(libs.junit4)
}
