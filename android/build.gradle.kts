// Projet conteneur du groupe `android/`.
//
// Les greffons Android et Kotlin Android sont déclarés ici, et **jamais
// appliqués** : `apply false` les charge une seule fois, dans un classloader
// que les modules du groupe partagent. Les déclarer dans chaque module les
// chargeait plusieurs fois, ce dont Gradle avertit à chaque construction.
//
// Ils ne peuvent pas être déclarés à la racine : le greffon Kotlin Android
// référence des types AGP, et la racine est évaluée même quand aucun module
// Android n'est inclus — la configuration échouait alors sur
// `com.android.build.gradle.BaseExtension`. Ce fichier, lui, n'existe que si le
// groupe `android/` est inclus, c'est-à-dire seulement quand un SDK est
// disponible. Les modules JVM restent donc constructibles sans lui.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
