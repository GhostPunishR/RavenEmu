// Module de vérification, sans code de production : il ne contient que des
// tests qui relisent les fichiers de configuration du dépôt (workflows
// GitHub Actions, build de l'application) et vérifient que les règles de
// sécurité de la chaîne de publication tiennent toujours. Module JVM pur :
// exécutable sur n'importe quelle machine, sans SDK Android.
plugins {
    id("ravenemu.jvm")
}

tasks.test {
    // Ces tests lisent des fichiers situés hors du module (workflows, build de
    // l'application, arborescence du dépôt). Gradle ne peut pas savoir qu'ils
    // ont changé : sans cela, modifier un workflow laisserait la tâche « à
    // jour » et la vérification ne s'exécuterait tout simplement pas. La suite
    // tient en moins d'une seconde, on la rejoue donc systématiquement.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}

