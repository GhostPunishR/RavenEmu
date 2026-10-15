// Repository rules for the multiplatform version's CI. Plain JVM tests that
// only read files: no Android SDK, no emulator, no network.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
