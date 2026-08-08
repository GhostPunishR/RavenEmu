plugins {
    id("ravenemu.jvm")
    id("ravenemu.native-parity")
}

dependencies {
    api(project(":engine:api"))
    implementation(project(":native:jni"))
}
