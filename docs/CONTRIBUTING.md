# RavenEmu — Règles de contribution

RavenEmu est un projet propriétaire : toute contribution doit respecter les
contraintes du cahier des charges (`docs/CAHIER_DES_CHARGES.md`), en
particulier :

1. **Aucun code d'émulateur existant** — ni copie, ni adaptation, ni
   traduction. Le comportement matériel s'implémente depuis la documentation
   technique publique (Pan Docs, schémas publiés).
2. **Aucun contenu protégé** — pas de BIOS, ROM, pochette ou ROM de test
   tierce dans le dépôt. Les tests utilisent des ROM synthétiques générées
   par le code de test (`TestRoms`).
3. **Dépendances officielles uniquement** — SDK Android, Kotlin, Gradle,
   AndroidX/Material, kotlinx. Toute autre dépendance doit être remplacée par
   une implémentation interne.
4. **`gameboy-core` sans Android** — le moteur reste pur JVM et testable via
   `./gradlew jvmTest`.

## Qualité attendue

- Chaque fonctionnalité livrée avec ses tests, ou ses limites documentées.
- Code lisible et documenté (KDoc pour les contrats et les comportements
  matériels non évidents).
- Déterminisme du moteur préservé (aucune source de temps ou d'aléa non
  injectée dans `gameboy-core`).
- Pas d'allocation dans les chemins chauds de la boucle d'émulation.
- Sécurité : validation des fichiers entrants, aucune E/S réseau, permissions
  minimales, SAF plutôt que permissions globales de stockage.

## Cycle de développement

```bash
./gradlew test          # doit rester vert
./gradlew lint          # rapport publié en CI
./gradlew assembleDebug # vérification d'intégration Android
```

Les PR doivent décrire : le comportement matériel visé (avec référence
documentaire), les tests ajoutés et les limites restantes.
