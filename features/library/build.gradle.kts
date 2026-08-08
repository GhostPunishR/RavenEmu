plugins {
    id("ravenemu.jvm-serialization")
}

dependencies {
    api(project(":engine:api"))
    api(project(":engine:runtime"))
    implementation(libs.kotlinx.serialization.json)
}
