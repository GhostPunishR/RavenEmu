plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    // Delta skin manifests (info.json), as in RavenEmu 0.8.1's skins module.
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.ravenemu.android"
    compileSdk = 37
    defaultConfig {
        // Distinct preview ID: cannot overwrite the existing RavenEmu Android app.
        applicationId = "com.ravenemu.preview"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0 - Bêta"
    }
    buildTypes {
        // APK bêta publié par la CI : même identifiant et même signature que
        // l'APK Debug (une mise à jour garde les données), mais non débogable.
        // Un APK débogable fait tourner le code Kotlin du lecteur (son, image,
        // cadence) moins optimisé ; la version 0.8.1 de RavenEmu publiait pour cela sa variante
        // « profil ». L'APK Debug reste disponible pour le débogage local.
        create("beta") {
            initWith(getByName("debug"))
            isDebuggable = false
            matchingFallbacks += listOf("debug")
        }
    }
    buildFeatures { compose = true }
    externalNativeBuild {
        cmake { path = file("../native/CMakeLists.txt") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Box/Image/offset for the Delta skin player; same Compose version as :shared.
    implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.4.21")
    testImplementation("org.json:json:20240303")
}
