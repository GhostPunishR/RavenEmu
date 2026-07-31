plugins {
    id("ravenemu.jvm-serialization")
}

dependencies {
    api(project(":core:emulation-api"))
    api(project(":core:gameboy-core"))
    api(project(":core:gba-core"))
    implementation(libs.kotlinx.serialization.json)
}
