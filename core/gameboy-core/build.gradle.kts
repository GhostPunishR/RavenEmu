plugins {
    id("ravenemu.jvm")
    id("ravenemu.native-parity")
}

dependencies {
    api(project(":core:emulation-api"))
    implementation(project(":core:native-bridge"))
}
