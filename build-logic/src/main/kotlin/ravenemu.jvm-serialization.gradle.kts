/**
 * Modules JVM qui sérialisent en JSON.
 *
 * Le plugin de sérialisation est un plugin Kotlin : l'appliquer avec sa version
 * dans un module chargeait Kotlin une seconde fois et faisait réapparaître
 * l'avertissement que `ravenemu.jvm` supprime. Il est donc déclaré ici, sans
 * version, comme le reste.
 */
plugins {
    id("ravenemu.jvm")
    kotlin("plugin.serialization")
}
