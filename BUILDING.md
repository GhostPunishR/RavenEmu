# Compiler RavenEmu

Le projet Gradle autonome se trouve à la racine. L'interface Compose Multiplatform est partagée ; Android raccorde le lecteur GB/GBC/GBA, iOS dispose de l'hôte SwiftUI et de l'adaptateur natif. Voir [README.md](README.md), [ARCHITECTURE.md](ARCHITECTURE.md) et [CHANGELOG.md](CHANGELOG.md) pour les fonctions et les limites de la bêta.

## Android

Prérequis : JDK 21, SDK Android API 37, CMake 3.22.1 minimum et NDK. Utilisez le Wrapper versionné :

```sh
git clone https://github.com/GhostPunishR/RavenEmu.git
cd RavenEmu
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:testDebugUnitTest
./gradlew :policy:test
```

APK Debug : `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

La CI publie la variante bêta non débogable, plus rapide sur téléphone, signée comme le build Debug :

```sh
./gradlew :androidApp:assembleBeta
```

APK bêta : `androidApp/build/outputs/apk/beta/androidApp-beta.apk`. Voir [RELEASING.md](RELEASING.md) pour la signature et le transfert des données.

Les tâches du module commun se découvrent avec `./gradlew :shared:tasks`. La génération des textes légaux lit `TERMS.md`, `PRIVACY.md` et `LICENSE` à la racine ; ces fichiers sont nécessaires à la compilation.

## iOS (macOS)

Prérequis : Xcode, JDK 21, CMake et [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
cd iosApp
xcodegen generate
open RavenEmu.xcodeproj
```

Le script Xcode construit les archives natives et intègre `RavenShared` via `../gradlew`, situé à la racine du dépôt. Pour vérifier seulement le framework depuis cette racine :

```sh
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
sh native/build-ios.sh
```

Le SDK Apple utilise `PLATFORM_NAME` (`iphonesimulator` par défaut, ou `iphoneos`). Le lecteur iOS complet, les imports et la persistance restent à raccorder ; aucun binaire signé n'est distribué.

## Liaison RavenCore

La configuration [native/CMakeLists.txt](native/CMakeLists.txt) récupère RavenCore au commit `453f9b097c91de67d6fa4a7179e9f2ac5268e7f6` : version 0.5.0, API C `ravenemu_*` avec le câble link `ravenemu_link_*`. Les états sont au format GB/GBC 11 et GBA 14 ; le format GBA 14 relit les formats 13, 12 et 11. Le SDK rcheevos est épinglé séparément au commit `1433173220a7eaede6a9ed7a18e94117be1821e0`.

Le test hôte vérifie la liaison, la création des trois systèmes, une frame et de l'audio synthétiques ainsi que l'enregistrement/rechargement d'un état :

```sh
cmake -S native -B native/build-host -DCMAKE_BUILD_TYPE=Release -DRAVENEMU_BRIDGE_TESTS=ON
cmake --build native/build-host --target ravenemu_bridge_smoke --parallel 2
ctest --test-dir native/build-host --output-on-failure
```

Les adaptateurs JNI et Swift utilisent le même header `ravenemu/bridge.h` de RavenCore. Aucun code moteur n'est copié dans ce dépôt. Les appels à une même instance sont sérialisés.

## Vérifications CI et appareil

Le workflow [multiplateforme.yml](.github/workflows/multiplateforme.yml) vérifie le Wrapper, les règles du dépôt, le test de liaison native, l'APK bêta et ses tests, le framework/SDK natif iOS et les adaptateurs Swift. Une compilation réussie ne remplace pas les essais visuels, audio, sauvegardes et RetroAchievements sur un appareil réel.
