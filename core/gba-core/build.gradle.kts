plugins {
    id("ravenemu.jvm")
}

dependencies {
    api(project(":core:emulation-api"))
    implementation(project(":core:native-bridge"))
}
