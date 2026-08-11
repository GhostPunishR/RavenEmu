plugins {
    id("ravenemu.jvm-serialization")
}

dependencies {
    api(project(":engine:cheats"))
    implementation(libs.kotlinx.serialization.json)
}
