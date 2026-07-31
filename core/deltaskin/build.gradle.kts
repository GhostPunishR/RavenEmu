plugins {
    id("ravenemu.jvm-serialization")
}

dependencies {
    api(project(":core:emulation-api"))
    implementation(libs.kotlinx.serialization.json)

}
