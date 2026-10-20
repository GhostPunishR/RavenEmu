# Compilation

Le projet est autonome : toutes les commandes partent de la racine du dépôt. Prérequis et procédures détaillées : [BUILDING.md](https://github.com/GhostPunishR/RavenEmu/blob/main/BUILDING.md).

## Android

JDK 21, SDK Android API 37 et outils natifs CMake/NDK :

```sh
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:assembleBeta
./gradlew :androidApp:testDebugUnitTest
./gradlew :policy:test
```

APK : `androidApp/build/outputs/apk/debug/` ou `androidApp/build/outputs/apk/beta/`. Les fichiers `TERMS.md`, `PRIVACY.md` et `LICENSE` de la racine sont intégrés au build partagé.

## iOS

Sur macOS, avec Xcode, JDK 21, CMake et XcodeGen :

```sh
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
sh native/build-ios.sh
cd iosApp
xcodegen generate
open RavenEmu.xcodeproj
```

La CI construit aussi l'application Xcode complète et exécute les XCTest sur simulateur. Sur Apple Silicon : `sh iosApp/ci-test.sh`. L'artefact `ravenemu-ios-simulator` est réservé au simulateur, sans IPA iPhone. Voir [le périmètre iOS](https://github.com/GhostPunishR/RavenEmu/blob/main/iosApp/README.md).

## RavenCore

Le commit `241f56c3564ca1389813753d5c13dd931771fe40` est épinglé dans `native/CMakeLists.txt` : RavenCore 0.7.1 avec correction RTC Pokémon et son API C `ravenemu_*`. Voir [la passerelle native](https://github.com/GhostPunishR/RavenEmu/blob/main/native/README.md).
