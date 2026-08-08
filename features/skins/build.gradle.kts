plugins {
    id("ravenemu.jvm-serialization")
}

dependencies {
    api(project(":engine:api"))
    implementation(libs.kotlinx.serialization.json)
}
