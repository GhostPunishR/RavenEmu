## Changement

Décrivez le problème et le comportement obtenu. Ajoutez l'issue associée si elle existe.

## Périmètre

Cochez uniquement les parties touchées. Le projet est à la racine ; voir [ARCHITECTURE.md](https://github.com/GhostPunishR/RavenEmu/blob/main/ARCHITECTURE.md).

- [ ] `shared/` : interface et logique communes
- [ ] `androidApp/` : hôte et lecteur Android
- [ ] `iosApp/` : hôte Apple
- [ ] `native/` : ponts et révisions épinglées
- [ ] `policy/`, Gradle ou GitHub Actions
- [ ] Documentation, site ou wiki

Une correction du moteur relève de RavenCore. Si sa révision épinglée change, liez la PR ou le commit RavenCore et décrivez la validation de l'intégration.

## Validation

Listez les commandes **réellement exécutées**, leurs résultats et les essais manuels. Choisissez selon le périmètre :

```sh
./gradlew :androidApp:testDebugUnitTest
./gradlew :androidApp:assembleDebug
./gradlew :policy:test
```

Sur macOS, si concerné :

```sh
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
sh native/build-ios.sh
```

Pour une validation sur appareil, précisez le modèle, le système et le scénario. Pour une modification visuelle, ajoutez une capture sans données privées si possible.

Vérifications non exécutées et raison :

## Compatibilité et limites

Décrivez les risques ou cas non testés et les effets sur les sauvegardes, formats, réglages et identifiants. Une compilation réussie ne suffit pas à annoncer un lecteur iOS jouable ou un succès RetroAchievements synchronisé.

Références techniques publiques utilisées, si nécessaire :

## Avant envoi

- [ ] La contribution respecte le [guide](https://github.com/GhostPunishR/RavenEmu/blob/main/CONTRIBUTING.md) et le code de conduite.
- [ ] Aucun code d'un autre émulateur n'est copié, traduit ou adapté.
- [ ] Aucun contenu protégé, secret ou fichier personnel n'est ajouté ; les données de test sont synthétiques ou redistribuables.
- [ ] La documentation est actualisée lorsque le comportement visible change.
