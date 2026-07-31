plugins {
    `kotlin-dsl`
}

// Un plugin de convention applique le plugin Kotlin : il doit donc l'avoir sur
// son propre chemin de compilation.
//
// AGP est délibérément **absent** : `build-logic` ne fournit que des
// conventions JVM, aucune convention Android, et n'a donc rien à compiler
// contre l'API d'AGP. Les greffons Android sont déclarés à la racine du build
// principal, en `apply false`.
// `compileOnly` et non `implementation` : les conventions compilent contre
// l'API des plugins Kotlin, mais ne doivent pas en embarquer une seconde copie.
// À l'exécution, le plugin vient du classloader de la racine, partagé avec les
// modules Android — c'est cette unicité qui supprime l'avertissement de
// chargement multiple.
dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.kotlin.serialization.plugin)
}

// `LibrariesForLibs` est généré par Gradle pour le catalogue de versions ; il
// n'est visible qu'en ajoutant explicitement les accesseurs au chemin de
// compilation des scripts précompilés.
dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
