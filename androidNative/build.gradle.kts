plugins {
    id("com.android.application")
}

android {
    namespace = "com.ravenemu.nativepreview"
    compileSdk = 37

    defaultConfig {
        // Separate install identity: never overwrite the current beta.
        applicationId = "com.ravenemu.nativepreview"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-native-preview"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                targets += listOf(
                    "raven_android", "ravenemu_gb_libretro",
                    "ravenemu_gbc_libretro", "ravenemu_gba_libretro"
                )
            }
        }
    }
    // The shared cores need to be unpacked as files for dlopen(path).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
