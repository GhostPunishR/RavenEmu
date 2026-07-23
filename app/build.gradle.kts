plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ravenemu.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ravenemu.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Signature release alimentée exclusivement par les secrets CI ou un
        // keystore.properties local (jamais versionné).
        create("release") {
            val keystorePath = System.getenv("RAVENEMU_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RAVENEMU_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RAVENEMU_KEY_ALIAS")
                keyPassword = System.getenv("RAVENEMU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            val hasSigning = System.getenv("RAVENEMU_KEYSTORE_PATH") != null
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    // App Bundle (.aab) pour la distribution Play Store : Google génère des
    // APK optimisés par appareil à partir du bundle. Le moteur est en Kotlin
    // pur (aucune bibliothèque native), donc le découpage par ABI est sans
    // objet ; on conserve le découpage par densité et par langue (défauts AGP).
    bundle {
        density { enableSplit = true }
        language { enableSplit = true }
        abi { enableSplit = true }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Le rapport lint est publié en artefact CI ; les erreurs bloquantes
        // sont traitées via la revue plutôt qu'en cassant le build.
        abortOnError = false
        checkDependencies = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":emulation-api"))
    implementation(project(":gameboy-core"))
    implementation(project(":rom-library"))
    implementation(project(":storage"))
    implementation(project(":renderer"))
    implementation(project(":input"))
    implementation(project(":settings"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.preference)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit4)
}
