plugins {
    id("ravenemu.jvm")
    id("ravenemu.native-parity")
}

dependencies {
    api(project(":engine:api"))
    api(project(":engine:cheats"))
    api(project(":engine:save"))
    api(project(":engine:diagnostics"))
    implementation(project(":native:jni"))
    testImplementation(project(":engine:session"))
}
