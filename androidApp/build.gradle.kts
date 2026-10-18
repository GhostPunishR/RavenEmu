plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    // Delta skin manifests (info.json), as in RavenEmu 0.8.1's skins module.
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Resolve Robolectric's test/resource SDK through Gradle as well, including
// in environments where the user home is read-only.
val robolectricSdks by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val prepareRobolectricSdks by tasks.registering(Sync::class) {
    from(robolectricSdks)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdks)
    inputs.files(robolectricSdks).withPropertyName("robolectricSdks")
    systemProperty("robolectric.offline", "true")
    systemProperty(
        "robolectric.dependency.dir",
        layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath,
    )
}

android {
    namespace = "com.ravenemu.android"
    compileSdk = 37
    defaultConfig {
        // Distinct preview ID: cannot overwrite the existing RavenEmu Android app.
        applicationId = "com.ravenemu.preview"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.environmentVariable("RAVENEMU_VERSION_CODE")
            .orElse("1").get().toInt()
        versionName = "0.1.0 - Bêta"
    }
    buildTypes {
        // Non-debuggable beta; CI applies the persistent distribution
        // signature after building and testing this variant.
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
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
    testImplementation(libs.robolectric.core)
    testImplementation(libs.compose.test.junit4)
    debugImplementation(libs.compose.test.manifest)
    robolectricSdks(libs.robolectric.sdk)
}
