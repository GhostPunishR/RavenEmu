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

    // Version des greffons Android, déclarée sans les résoudre.
    //
    // Les déclarer à la racine du build en `apply false` les mettait sur le
    // chemin de compilation dès la configuration, donc **téléchargés depuis le
    // dépôt Google même quand aucun module Android n'est inclus**. La promesse
    // faite plus bas — rester constructible sur une machine JVM/C++ sans SDK
    // Android — supposait alors quand même d'atteindre `dl.google.com`.
    //
    // Ici, le couple identifiant/version est seulement enregistré : la
    // résolution n'a lieu que si un module applique réellement le greffon, ce
    // que seuls les modules Android font, et seulement lorsqu'ils sont inclus.
    //
    // Le catalogue de versions n'est pas encore lisible à ce stade du build :
    // il est déclaré plus bas, dans `dependencyResolutionManagement`. La
    // version est donc relue directement dans le catalogue plutôt que recopiée,
    // pour qu'il reste la source unique.
    val agpVersion = File(rootDir, "gradle/libs.versions.toml").readLines()
        .first { it.substringBefore('=').trim() == "agp" }
        .substringAfter('"').substringBefore('"')

    plugins {
        id("com.android.application") version agpVersion
        id("com.android.library") version agpVersion
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
    ":engine:runtime",
    ":engine:session",
    ":engine:state",
    ":engine:save",
    ":engine:audio",
    ":engine:diagnostics",
    ":native:jni",
    ":features:library",
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
