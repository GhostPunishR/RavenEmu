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

// Plugins de convention, compilés avant le build principal.
includeBuild("build-logic")

// Les modules sont regroupés par rôle : `core/` ne dépend d'aucune plateforme,
// `android/` porte tout ce qui touche au système, `tools/` vérifie le dépôt
// lui-même. Le chemin de projet suit ce découpage (`:core:gba-core`), ce qui
// rend la couche visible dans chaque dépendance déclarée.

// Modules JVM purs : constructibles et testables sans SDK Android.
include(":core:emulation-api")
include(":core:deltaskin")
include(":core:gameboy-core")
include(":core:gba-core")
include(":core:rom-library")

// Vérification de la configuration du dépôt (workflows GitHub Actions,
// signature des APK) : uniquement des tests, aucun code de production.
// Voir RELEASING.md.
include(":tools:ci-policy")

// Modules Android : inclus uniquement si un SDK Android est disponible
// (variable d'environnement ou local.properties), afin que les modules JVM
// restent constructibles sur toute machine. Voir wiki/Architecture.md.
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
    include(":android:app")
    include(":android:storage")
    include(":android:renderer")
    include(":android:input")
    include(":android:settings")
} else {
    logger.lifecycle(
        "RavenEmu : SDK Android introuvable, seuls les modules non Android " +
            "sont inclus (" +
            rootProject.children.flatMap { it.children }.map { it.path.removePrefix(":") }
                .sorted()
                .joinToString(", ") +
            ")."
    )
}
