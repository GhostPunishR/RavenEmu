// Racine du build RavenEmu. Aucun plugin n'est appliqué ici : chaque module
// déclare les siens, ce qui permet d'exclure les modules Android quand aucun
// SDK n'est disponible (voir settings.gradle.kts et docs/ARCHITECTURE.md).
tasks.register("jvmTest") {
    group = "verification"
    description = "Exécute les tests des modules JVM purs."
    dependsOn(
        ":emulation-api:test",
        ":gameboy-core:test",
        ":rom-library:test",
    )
}
