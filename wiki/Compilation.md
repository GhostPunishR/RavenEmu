# Compilation

Toutes les commandes partent de la racine du dépôt. Prérequis et procédures détaillées : [BUILDING.md](https://github.com/GhostPunishR/RavenEmu/blob/main/BUILDING.md).

## Android

JDK 21, SDK Android API 37, NDK et CMake :

```sh
./gradlew :android:assembleDebug
./gradlew :android:testDebugUnitTest
./gradlew :android:lintDebug
./gradlew :policy:test
```

APK : `android/build/outputs/apk/debug/android-debug.apk` (ARM64, identifiant `com.ravenemu.dev`). La compilation construit aussi, par CMake, le frontend C, les pilotes natifs et les trois cores Libretro de RavenCore. Les fichiers `TERMS.md` et `PRIVACY.md` de la racine sont copiés dans l'application, qui les affiche dans **Paramètres → À propos**.

## Frontend C et tests sur ordinateur

Sur Linux, avec un compilateur C/C++20, CMake, les bibliothèques EGL et GLES et un JDK 21 :

```sh
cmake -S frontend -B build/native-frontend -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-frontend --parallel 2
ctest --test-dir build/native-frontend --output-on-failure
```

## RavenCore

[frontend/CMakeLists.txt](https://github.com/GhostPunishR/RavenEmu/blob/main/frontend/CMakeLists.txt) épingle RavenCore au commit `eb5e793b0d612f50fbc6aad2ddbfee1050125cf6`, la version 0.15.1, et en construit les cores Libretro. Aucun code moteur n'est copié dans RavenEmu.
