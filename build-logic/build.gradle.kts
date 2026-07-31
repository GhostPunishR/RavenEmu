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
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
}

// `LibrariesForLibs` est généré par Gradle pour le catalogue de versions ; il
// n'est visible qu'en ajoutant explicitement les accesseurs au chemin de
// compilation des scripts précompilés.
dependencies {
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}
