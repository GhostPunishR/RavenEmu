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

// Modules JVM purs : constructibles et testables sans SDK Android.
include(":emulation-api")
include(":gameboy-core")
include(":gba-core")
include(":rom-library")

// Modules Android : inclus uniquement si un SDK Android est disponible
// (variable d'environnement ou local.properties), afin que les modules JVM
// restent constructibles sur toute machine. Voir docs/ARCHITECTURE.md, AD-04.
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
    include(":app")
    include(":storage")
    include(":renderer")
    include(":input")
    include(":settings")
} else {
    logger.lifecycle(
        "RavenEmu : SDK Android introuvable, seuls les modules JVM sont inclus " +
            "(emulation-api, gameboy-core, gba-core, rom-library)."
    )
}
