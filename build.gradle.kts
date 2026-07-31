// Racine du build RavenEmu.
//
// Les plugins Kotlin sont déclarés ici, et **jamais appliqués** : `apply false`
// les charge une seule fois, dans le classloader de la racine, que tous les
// sous-projets partagent. Sans cela, chaque module les déclarait avec sa
// version et Gradle les chargeait depuis des classloaders distincts, en
// avertissant à chaque construction d'un chargement multiple « qui peut casser
// le build ». C'est la correction que Gradle recommande dans ce message même.
//
// AGP est déclaré ici pour la même raison, et lui aussi en `apply false` : le
// greffon Kotlin Android référence des types AGP. Chargé depuis la racine, il
// ne verrait pas un AGP resté propre aux modules Android — la configuration
// échouait alors sur `com.android.build.gradle.BaseExtension`. Le déclarer ici
// le place dans le classloader commun ; `apply false` ne l'applique pas à la
// racine et n'exige donc aucun SDK Android (voir settings.gradle.kts et
// wiki/Architecture.md).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

tasks.register("jvmTest") {
    group = "verification"
    description = "Exécute les tests des modules JVM purs."
    // Dérivé des modules réellement déclarés plutôt que recopié : `core/` et
    // `tools/` sont, par construction, indépendants d'Android, et un module
    // ajouté à l'une de ces catégories est couvert sans qu'on ait à compléter
    // une liste — ce que rien n'aurait signalé si on l'oubliait.
    dependsOn(
        provider {
            subprojects
                .filter { it.path.startsWith(":core:") || it.path.startsWith(":tools:") }
                .map { "${it.path}:test" }
        }
    )
}
