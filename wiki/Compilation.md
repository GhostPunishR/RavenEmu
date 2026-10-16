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

La CI construit le framework et le SDK natif pour simulateur et vérifie les adaptateurs Swift. Le lecteur iOS complet reste à raccorder.

## RavenCore

Le commit `41fc01a376367394146bf7e13cf38c80a4287695` est épinglé dans `native/CMakeLists.txt` : RavenCore 0.5.0 et son API C `ravenemu_*`. Voir [la passerelle native](https://github.com/GhostPunishR/RavenEmu/blob/main/native/README.md).
