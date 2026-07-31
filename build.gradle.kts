// Racine du build RavenEmu. Aucun plugin n'est appliqué ici : chaque module
// déclare les siens, ce qui permet d'exclure les modules Android quand aucun
// SDK n'est disponible (voir settings.gradle.kts et wiki/Architecture.md).
tasks.register("jvmTest") {
    group = "verification"
    description = "Exécute les tests des modules JVM purs."
    dependsOn(
        ":core:emulation-api:test",
        ":core:deltaskin:test",
        ":core:gameboy-core:test",
        ":core:gba-core:test",
        ":core:rom-library:test",
        ":tools:ci-policy:test",
    )
}
