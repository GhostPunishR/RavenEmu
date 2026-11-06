# Contribuer à RavenEmu

Les contributions de code, de documentation et de tests sont bienvenues. Ce dépôt contient l'application RavenEmu : les commandes partent de sa racine.

## Choisir le bon emplacement

| Sujet | Emplacement |
| --- | --- |
| Interface, stockage et services Android (Java) | `android/src/main/java/` |
| Pont JNI, pilotes vidéo, audio et entrées (C) | `android/src/main/cpp/` |
| Frontend C, hôte Libretro et révision RavenCore épinglée | `frontend/` |
| Tests des pilotes natifs | `tests/hardware/` |
| Règles de CI et contrôles du dépôt | `policy/`, `scripts/` et `.github/workflows/` |
| Provenance des sources adaptées | `legal/attributions/` |
| Site et documentation | `docs/` et `wiki/` |
| Moteurs d'émulation | [RavenCore](https://github.com/GhostPunishR/RavenCore) |

Voir [ARCHITECTURE.md](ARCHITECTURE.md). L'application est écrite en Java, JNI et C : pas de Kotlin ni de Compose dans `android/`. Une correction du moteur se fait dans RavenCore ; sa révision épinglée est mise à jour séparément avec une validation d'intégration.

## Avant de développer

Recherchez une issue ou une PR similaire. Décrivez les chantiers importants avant de les commencer, puis créez une branche depuis `main` et gardez un objectif clair par PR. Pour une vulnérabilité, suivez [SECURITY.md](SECURITY.md).

## Environnement et vérifications

Prérequis : JDK 21, Gradle Wrapper du dépôt, SDK Android API 37, NDK et CMake. Voir [BUILDING.md](BUILDING.md).

```sh
./gradlew :android:testDebugUnitTest
./gradlew :android:lintDebug
./gradlew :android:assembleDebug
./gradlew :policy:test
python3 scripts/check_native_layout.py
```

Frontend C et pilotes, sur Linux :

```sh
cmake -S frontend -B build/native-frontend -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-frontend --parallel 2
ctest --test-dir build/native-frontend --output-on-failure
```

Ajoutez une vérification de régression lorsque le changement modifie un comportement. Précisez séparément ce qui a été compilé, testé et observé sur appareil.

## Règles essentielles

- Respectez les frontières des modules et le style existant.
- Gardez les moteurs indépendants des plateformes et les appels à une instance native sérialisés.
- Préservez les données et identifiants des utilisateurs ; documentez toute migration ou incompatibilité.
- Utilisez du code original et des références techniques publiques. Ne copiez, traduisez ou adaptez pas le code d'un autre émulateur.
- N'ajoutez aucune ROM commerciale, aucun BIOS protégé, aucune clé ni donnée personnelle. Les données redistribuées doivent disposer d'une licence adaptée.
- Décrivez les fonctionnalités et les limites réellement présentes, sans annoncer des validations non effectuées.

Les contributions relèvent de la [licence AGPL-3.0](LICENSE) et du [code de conduite](CODE_OF_CONDUCT.md). Conservez les avis de licence des composants tiers.

## Pull request et documentation

Le [modèle de PR](.github/PULL_REQUEST_TEMPLATE.md) demande le problème, le résultat, le périmètre, les commandes exécutées et les limites. Pour une interface, ajoutez une capture sans donnée privée si possible. Actualisez [CHANGELOG.md](CHANGELOG.md) pour un changement notable.

Les sources du site sont dans `docs/`, les pages du wiki dans `wiki/`. Le workflow du wiki vérifie les pages et les synchronise si le Wiki GitHub est accessible et initialisé. Sinon, elles restent consultables dans le dépôt. N'utilisez pas de tiret cadratin dans les pages du wiki : le workflow le refuse.

Pour un bug ou un résultat de compatibilité, indiquez le commit ou le build exact, la plateforme, l'appareil, les étapes et les réglages. Voir [RELEASING.md](RELEASING.md) pour la distribution.

## Intégrité de la construction

Les actions GitHub et révisions natives sont épinglées par SHA. Le Wrapper est validé en CI et la distribution Gradle conserve son `distributionSha256Sum`. Vérifiez les références et empreintes officielles avant une mise à jour ; ne remplacez pas le JAR du Wrapper à la main et ne versionnez aucun secret.
