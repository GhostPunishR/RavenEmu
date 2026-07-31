// Racine du build RavenEmu.
//
// Les plugins Kotlin sont déclarés ici, et **jamais appliqués** : `apply false`
// les charge une seule fois, dans le classloader de la racine, que tous les
// sous-projets partagent. Sans cela, chaque module les déclarait avec sa
// version et Gradle les chargeait depuis des classloaders distincts, en
// avertissant à chaque construction d'un chargement multiple « qui peut casser
// le build ». C'est la correction que Gradle recommande dans ce message même.
//
// Les greffons Android et Kotlin Android ne peuvent pas figurer ici : le second
// référence des types AGP, et la racine est évaluée même quand aucun module
// Android n'est inclus. Ils sont déclarés dans `android/build.gradle.kts`, qui
// n'existe que si ce groupe l'est (voir settings.gradle.kts et
// wiki/Architecture.md).
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
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
