# Passerelle native RavenEmu

Le même SDK C de [RavenCore](https://github.com/GhostPunishR/RavenCore) est utilisé sur Android et iOS. Le code C++ des moteurs n'est pas copié dans RavenEmu.

Le projet CMake télécharge les cœurs au **commit SHA précis** de la version [0.3.0](https://github.com/GhostPunishR/RavenCore/releases/tag/v0.3.0) de RavenCore ([`a68481e`](https://github.com/GhostPunishR/RavenCore/commit/a68481e79bd9dbd2c586117e30b558a895c65e59)) ; le tag lui-même n’est pas utilisé car il pourrait être déplacé. Le détail des changements est dans le [journal de RavenCore](https://github.com/GhostPunishR/RavenCore/blob/main/CHANGELOG.md). API C version 0, aux noms `ravenemu_*` ; formats d'états GB/GBC 11 et GBA 13 (les formats GBA 12 et 11 se rechargent). L'épinglage précédent était la 0.2.0 ([`5854e2c`](https://github.com/GhostPunishR/RavenCore/commit/5854e2c63155945dd9c78347ecdfd5b3a8a99795)).

- Android : `native/android/bridge_jni.cpp` fournit JNI à `NativeCore.kt` et rend des images ARGB8888 avec audio PCM16.
- iOS : `native/apple/module.modulemap` importe la même API C dans Swift, avec `RavenNativeCore.swift`. Le script `build-ios.sh` compile les bibliothèques C++ pour `iphoneos` ou `iphonesimulator`.
- Le dossier `sdk/` du dépôt RavenCore contient le header, l'implémentation C++ et les tests synthétiques.

Les adaptateurs natifs sont raccordés au lecteur Android RavenEmu (bibliothèque, rendu, audio, commandes, sauvegardes et RetroAchievements en validation). Le lecteur iOS complet reste à connecter.

Sur Mac (Xcode et CMake nécessaires) :

```sh
cd native
sh ./build-ios.sh
```

Android (SDK/NDK nécessaires) : `./gradlew :androidApp:assembleDebug`.

**Le code natif ne contient aucune ROM ni BIOS protégé.** Les appels à une même instance doivent être sérialisés. Le frontend est responsable de la sauvegarde et de la synchronisation audio.

## API C et vérification de liaison

Les adaptateurs appellent directement les fonctions `ravenemu_*` du header `sdk/include/ravenemu/bridge.h` de RavenCore, sans renommage. JNI l'inclut depuis le SDK épinglé ; pour Swift, CMake le copie avec `apple/module.modulemap` dans `include/` du dossier de build. La bibliothèque Android s'appelle `ravenemu_android`.

Le test hôte utilise une cartouche synthétique sans contenu de jeu et vérifie création des trois systèmes, vidéo, audio et sauvegarde/rechargement d'un état :

```sh
cmake -S native -B native/build-host -DCMAKE_BUILD_TYPE=Release -DRAVENEMU_BRIDGE_TESTS=ON
cmake --build native/build-host --target ravenemu_bridge_smoke --parallel 2
ctest --test-dir native/build-host --output-on-failure
```
