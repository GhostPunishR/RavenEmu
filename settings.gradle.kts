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

/**
 * Déclare un module sous son rôle architectural sans imposer, dans cette PR,
 * un déplacement massif de ses sources. Cela permet de rendre le graphe de
 * dépendances explicite maintenant, puis de déplacer les répertoires physiques
 * par lots testés sans mélanger réorganisation et réécriture fonctionnelle.
 */
fun includeModule(path: String, directory: String) {
    include(path)
    project(path).projectDir = file(directory)
}

// Moteur Kotlin pur : contrats, orchestration et adaptateurs de consoles.
includeModule(":engine:api", "core/emulation-api")
includeModule(":engine:systems:gb", "core/gameboy-core")
includeModule(":engine:systems:gba", "core/gba-core")

// Frontière JVM/C++ uniquement. Les cœurs C++ vivent hors de Gradle dans cores/.
includeModule(":native:jni", "native/jni")

// Fonctions indépendantes de la plateforme.
includeModule(":features:skins", "core/deltaskin")

// Exception volontaire de cette refonte : la bibliothèque ROM vient d'être
// profondément remaniée et reste strictement à son emplacement et sous son
// identité actuels pour éviter tout changement fonctionnel ou de migration.
include(":core:rom-library")

// Vérification de la configuration du dépôt (workflows GitHub Actions,
// signature des APK, règles d'architecture) : uniquement des tests.
include(":tools:ci-policy")

// Modules Android : inclus uniquement si un SDK Android est disponible afin
// que les modules JVM purs restent constructibles sur toute machine.
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
    // L'application reste à son emplacement actuel : elle contient notamment
    // la bibliothèque gelée pour cette refonte.
    include(":android:app")
    includeModule(":platform:android:storage", "android/storage")
    includeModule(":platform:android:renderer", "android/renderer")
    includeModule(":platform:android:input", "android/input")
    includeModule(":platform:android:settings", "android/settings")
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
