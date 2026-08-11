pluginManagement {
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "RavenEmu"
includeBuild("build-logic")

// Kotlin/JVM pur.
include(
    ":engine:api",
    ":engine:cheats",
    ":engine:runtime",
    ":engine:session",
    ":engine:state",
    ":engine:save",
    ":engine:audio",
    ":engine:diagnostics",
    ":native:jni",
    ":features:library",
    ":features:cheats",
    ":features:skins",
    ":tools:ci-policy",
)

// Les couches Android ne sont incluses que lorsqu'un SDK est réellement présent,
// afin que `engine`, `features:library`, `features:skins` et les outils restent
// testables sur une machine JVM/C++ sans Android Studio.
val localProperties = File(rootDir, "local.properties")
val sdkFromLocalProperties = localProperties.takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { it.trim().startsWith("sdk.dir") }
    ?.substringAfter("=")
    ?.trim()
val sdkDir = sequenceOf(
    sdkFromLocalProperties,
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
).filterNotNull().map(::File).firstOrNull(File::isDirectory)

if (sdkDir != null) {
    include(
        ":app:android",
        ":platform:android:audio",
        ":platform:android:renderer",
        ":platform:android:input",
        ":platform:android:storage",
        ":platform:android:vibration",
        ":platform:android:lifecycle",
        ":features:settings",
        ":features:player",
        ":features:savestates",
        ":features:diagnostics",
    )
} else {
    logger.lifecycle("RavenEmu : SDK Android introuvable, build limité aux couches JVM/C++.")
}
