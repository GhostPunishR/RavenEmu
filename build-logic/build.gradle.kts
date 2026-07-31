plugins {
    `kotlin-dsl`
}

// Un plugin de convention applique le plugin Kotlin : il doit donc l'avoir sur
// son propre chemin de compilation.
//
// AGP est délibérément **absent** : les modules Android déclarent encore leurs
// plugins eux-mêmes. Les inclure ici obligerait à résoudre AGP pour toute
// construction, y compris celles qui ne visent que les modules JVM, et le dépôt
// tient à rester constructible sans SDK Android.
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
