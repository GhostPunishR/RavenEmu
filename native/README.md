# Passerelle native RavenEmu

Le même SDK C de [RavenCore](https://github.com/GhostPunishR/RavenCore) est utilisé sur Android et iOS. Le code C++ des moteurs n'est pas copié dans RavenEmu.

Le projet CMake télécharge les cœurs au **commit SHA précis** [`241f56c`](https://github.com/GhostPunishR/RavenCore/commit/241f56c3564ca1389813753d5c13dd931771fe40) de RavenCore : base 0.7.1 avec la [correction RTC Pokémon/CFRU](https://github.com/GhostPunishR/RavenCore/pull/28), postérieure au tag `v0.7.1` et sans nouvelle version publiée. L'épinglage précédent était le commit [`8f682a1`](https://github.com/GhostPunishR/RavenCore/commit/8f682a121b0ce2dc1d5c4777fc602e8f449110b5) du tag `v0.7.1`. Une branche ou un tag mobile n'est pas utilisé.

L'API C reste en version 0, aux noms `ravenemu_*`, câble link `ravenemu_link_*` compris ; formats d'états GB/GBC 11 et GBA 16 (les formats GBA 15, 14, 13, 12 et 11 se rechargent). La détection reconnaît désormais certains pilotes d'horloge Pokémon sans marqueur Seiko ; le protocole RTC et la gestion des dates sont aussi corrigés. Le bloc RTC batterie garde ses 16 octets et les anciennes sauvegardes Flash restent utilisables. Un ancien état d'un hack créé sans RTC peut être incompatible avec la cartouche désormais détectée avec RTC : reprendre depuis la sauvegarde normale du jeu. Voir les [preuves et limites de RavenCore](https://github.com/GhostPunishR/RavenCore/blob/241f56c3564ca1389813753d5c13dd931771fe40/cores/gba/tests/rtc_compatibility.md).

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

Le test hôte utilise une cartouche synthétique sans contenu de jeu et vérifie création des trois systèmes, vidéo, audio, sauvegarde/rechargement d'un état et câble link. Il vérifie aussi la détection RTC GBA automatique sans marqueur Seiko, la conservation des deux banques Flash d'une ancienne sauvegarde, sa réouverture, son reset et un aller-retour d'état avec RTC :

```sh
cmake -S native -B native/build-host -DCMAKE_BUILD_TYPE=Release -DRAVENEMU_BRIDGE_TESTS=ON
cmake --build native/build-host --target ravenemu_bridge_smoke ravenemu_achievements_smoke --parallel 2
ctest --test-dir native/build-host --output-on-failure
```

Le test `ravenemu_achievements_smoke` compile aussi l'adaptateur RetroAchievements de production sur l'hôte avec les en-têtes JNI du JDK (`JAVA_HOME`). Il vérifie les identifiants des événements, la copie des résultats de classement, les erreurs de consultation et les bilans Softcore/Hardcore avec des données synthétiques, sans compte ni requête réseau. Le SDK rcheevos reste épinglé au même SHA que sur Android.
