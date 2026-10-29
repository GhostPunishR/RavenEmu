plugins {
    id("com.android.application")
}

// The source archive has no .git directory; its manifest supplies these values.
val sourceRevision = providers.gradleProperty("ravenSourceRevision").orElse(providers.exec {
    commandLine("git", "rev-parse", "HEAD")
}.standardOutput.asText.map { it.trim() }).map {
    require(it.matches(Regex("[a-fA-F0-9]{40}"))) { "Invalid source revision" }
    it
}
val sourceModified = providers.gradleProperty("ravenSourceModified").map {
    it.toBooleanStrict()
}.orElse(providers.exec {
    commandLine("git", "diff", "--quiet", "HEAD", "--")
    isIgnoreExitValue = true
}.result.map { it.exitValue != 0 })

// Resolve the test SDK through Gradle, also on read-only CI user homes.
val robolectricSdks by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}
val prepareRobolectricSdks by tasks.registering(Sync::class) {
    from(robolectricSdks)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdks)
    inputs.files(robolectricSdks).withPropertyName("robolectricSdks")
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir",
        layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
}

android {
    namespace = "com.ravenemu.nativepreview"
    compileSdk = 37

    defaultConfig {
        // Separate install identity: never overwrite the current beta.
        applicationId = "com.ravenemu.nativepreview"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-native-preview"
        buildConfigField("String", "SOURCE_REVISION", "\"${sourceRevision.get()}\"")
        buildConfigField("boolean", "SOURCE_MODIFIED", sourceModified.get().toString())

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                providers.gradleProperty("ravenCoreSource").orNull?.let {
                    arguments += "-DFETCHCONTENT_SOURCE_DIR_RAVENCORE=${rootProject.file(it).absolutePath}"
                }
                targets += listOf(
                    "raven_android", "ravenemu_gb_libretro",
                    "ravenemu_gbc_libretro", "ravenemu_gba_libretro"
                )
            }
        }
    }
    // The shared cores need to be unpacked as files for dlopen(path).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.material)
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.robolectric.core)
    robolectricSdks(libs.robolectric.sdk)
}

// Include the published sources, metadata and unmodified resource containers
// of the actual runtime libraries beside the native preview's source bundle.
tasks.register("collectDebugCorrespondingSources") {
    val destination = layout.buildDirectory.dir("corresponding-sources")
    inputs.files(configurations.named("debugRuntimeClasspath"))
    outputs.dir(destination)
    doLast {
        val directory = destination.get().asFile
        directory.mkdirs()
        val modules = configurations.getByName("debugRuntimeClasspath")
            .resolvedConfiguration.resolvedArtifacts
            .distinctBy { it.moduleVersion.id }
            .sortedBy { it.moduleVersion.id.toString() }
        val rows = mutableListOf("coordinate\tsources\tpom\tbinary")
        for (artifact in modules) {
            val id = artifact.moduleVersion.id
            val coordinate = "${id.group}:${id.name}:${id.version}"
            val prefix = "${id.group}_${id.name}_${id.version}"
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            fun resolve(suffix: String): java.io.File {
                val dependency = dependencies.create("$coordinate$suffix")
                val configuration = configurations.detachedConfiguration(dependency)
                configuration.isTransitive = false
                return configuration.singleFile
            }
            val sources = resolve(":sources@jar")
            val pom = resolve("@pom")
            val sourceName = "$prefix-sources.jar"
            val pomName = "$prefix.pom"
            val binaryName = "$prefix.${artifact.file.extension}"
            sources.copyTo(directory.resolve(sourceName), overwrite = true)
            pom.copyTo(directory.resolve(pomName), overwrite = true)
            artifact.file.copyTo(directory.resolve(binaryName), overwrite = true)
            rows.add("$coordinate\t$sourceName\t$pomName\t$binaryName")
        }
        directory.resolve("DEPENDENCIES.tsv").writeText(rows.joinToString("\n", postfix = "\n"))
        logger.lifecycle("Collected source artifacts for ${modules.size} runtime libraries")
    }
}
