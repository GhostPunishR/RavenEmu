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
    implementation(project(":engine:api"))
    api(project(":platform:android:input"))
    implementation(project(":features:skins"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.preference)
}
