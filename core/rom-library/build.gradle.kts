plugins {
    id("ravenemu.jvm-serialization")
}

dependencies {
    api(project(":engine:api"))
    api(project(":engine:systems:gb"))
    api(project(":engine:systems:gba"))
    implementation(libs.kotlinx.serialization.json)
}
