plugins {
    id("com.android.library")
}

android {
    namespace = "com.ravenemu.settings"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":platform:android:input"))
    implementation(project(":features:skins"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.preference)
}
