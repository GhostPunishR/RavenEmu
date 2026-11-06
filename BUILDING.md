# Compiler RavenEmu

Le dépôt contient l'application Android native (`android/`, Java + JNI + C) et son frontend C (`frontend/`), qui charge les cores Libretro de [RavenCore](https://github.com/GhostPunishR/RavenCore). Voir [ARCHITECTURE.md](ARCHITECTURE.md) pour les responsabilités de chaque couche et [docs/MIGRATION_NATIVE.md](docs/MIGRATION_NATIVE.md) pour les écarts de parité restants.

Toutes les commandes partent de la racine du dépôt.

## Android

Prérequis : JDK 21, SDK Android API 37, NDK, CMake 3.22.1 minimum. Utilisez le Wrapper Gradle versionné :

```sh
git clone https://github.com/GhostPunishR/RavenEmu.git
cd RavenEmu
./gradlew :android:assembleDebug
./gradlew :android:testDebugUnitTest
./gradlew :android:lintDebug
./gradlew :policy:test
```

APK de développement : `android/build/outputs/apk/debug/android-debug.apk`, identifiant `com.ravenemu.dev`, ARM64 (`arm64-v8a`), Android 8.0 (API 26) minimum. Il s'installe à côté de la bêta `com.ravenemu.preview` sans lire ses données.

La compilation de l'APK construit aussi, par CMake, le frontend C, les pilotes natifs (GLES2, AAudio, entrées) et les trois cores Libretro de RavenCore. Le code natif est optimisé même dans l'APK Debug ; `scripts/check_native_optimization.py` le vérifie après une compilation.

## Frontend C et tests hôtes

Le frontend et ses pilotes se testent sur un ordinateur Linux, sans téléphone. Prérequis : un compilateur C/C++20, CMake, les bibliothèques EGL et GLES (`libegl-dev`, `libgles-dev`) et un JDK 21 pour le test du pont JNI RetroAchievements.

```sh
cmake -S frontend -B build/native-frontend -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-frontend --parallel 2
ctest --test-dir build/native-frontend --output-on-failure
```

`-DRAVEN_VIDEO_PIXEL_TESTS=ON` ajoute les tests de rendu au pixel près, comme dans la CI.

## Liaison RavenCore

[frontend/CMakeLists.txt](frontend/CMakeLists.txt) récupère RavenCore au SHA complet d'un commit (`RAVEN_CORE_REVISION`) et en construit les cores Libretro GB, GBC et GBA. Aucun code moteur n'est copié dans ce dépôt ; publier une version de RavenCore ne change pas la révision utilisée. Le SDK rcheevos est épinglé séparément au commit `1433173220a7eaede6a9ed7a18e94117be1821e0` ([frontend/cmake/rcheevos.cmake](frontend/cmake/rcheevos.cmake)).

Après un changement de révision, mettre à jour les licences embarquées (`android/src/main/assets/licenses/`), qui nomment le commit utilisé.

## Vérifications CI et appareil

Le workflow [RavenEmu / Applications](.github/workflows/ci-applications.yml) vérifie le Wrapper et les règles du dépôt, compile et teste le frontend C et ses pilotes, puis construit l'APK ARM64, exécute les tests Java (JVM et Robolectric) et Android Lint, et archive l'APK de développement avec ses sources natives. Une compilation réussie ne remplace pas les essais visuels, audio, sauvegardes et RetroAchievements sur un appareil réel.
