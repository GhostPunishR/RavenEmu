/**
 * Modules JVM qui sérialisent en JSON.
 *
 * Le plugin de sérialisation est un plugin Kotlin : comme lui, il est appliqué
 * par identifiant pour être résolu depuis le classloader de la racine.
 */
apply(plugin = "ravenemu.jvm")
apply(plugin = "org.jetbrains.kotlin.plugin.serialization")
