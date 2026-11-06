## Changement

Décrivez le problème et le comportement obtenu. Ajoutez l'issue associée si elle existe.

## Périmètre

Cochez uniquement les parties touchées. Le projet est à la racine ; voir [ARCHITECTURE.md](https://github.com/GhostPunishR/RavenEmu/blob/main/ARCHITECTURE.md).

- [ ] `android/src/main/java/` : interface et services Android (Java)
- [ ] `android/src/main/cpp/` : pont JNI et pilotes natifs (C)
- [ ] `frontend/` : frontend C, hôte Libretro et révision RavenCore épinglée
- [ ] `tests/` ou `scripts/` : tests et contrôles natifs
- [ ] `policy/`, Gradle ou GitHub Actions
- [ ] Documentation, site ou wiki

Une correction du moteur relève de RavenCore. Si sa révision épinglée change, liez la PR ou le commit RavenCore et décrivez la validation de l'intégration.

## Validation

Listez les commandes **réellement exécutées**, leurs résultats et les essais manuels. Choisissez selon le périmètre :

```sh
./gradlew :android:testDebugUnitTest
./gradlew :android:assembleDebug
./gradlew :policy:test
cmake -S frontend -B build/native-frontend -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-frontend --parallel 2
ctest --test-dir build/native-frontend --output-on-failure
```

Pour une validation sur appareil, précisez le modèle, le système et le scénario. Pour une modification visuelle, ajoutez une capture sans données privées si possible.

Vérifications non exécutées et raison :

## Compatibilité et limites

Décrivez les risques ou cas non testés et les effets sur les sauvegardes, formats, réglages et identifiants. Une compilation réussie ne suffit pas à annoncer un jeu jouable sur téléphone ou un succès RetroAchievements synchronisé.

Références techniques publiques utilisées, si nécessaire :

## Avant envoi

- [ ] La contribution respecte le [guide](https://github.com/GhostPunishR/RavenEmu/blob/main/CONTRIBUTING.md) et le code de conduite.
- [ ] Aucun code d'un autre émulateur n'est copié, traduit ou adapté.
- [ ] Aucun contenu protégé, secret ou fichier personnel n'est ajouté ; les données de test sont synthétiques ou redistribuables.
- [ ] La documentation est actualisée lorsque le comportement visible change.
