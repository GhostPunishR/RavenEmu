# Passerelle native RavenEmu

Le même SDK C de [RavenCore](https://github.com/GhostPunishR/RavenCore) est utilisé sur Android et iOS. Le code C++ des moteurs n'est pas copié dans RavenEmu.

Le projet CMake télécharge les cœurs au **commit SHA précis** [`15675cc3af4f9b07f2e10cc5a7ca6234ea1c77cc`](https://github.com/GhostPunishR/RavenCore/commit/15675cc3af4f9b07f2e10cc5a7ca6234ea1c77cc) de RavenCore, la version 0.14.0 ([#36](https://github.com/GhostPunishR/RavenCore/pull/36)). Son API C gagne les codes de triche (`ravenemu_check_cheat`, `ravenemu_set_cheats`), la GBC d'une Game Boy Advance (`RAVENEMU_GBC_ON_GBA`), les couleurs des jeux Game Boy sur GBC (`ravenemu_set_gbc_colors`) et un vrai BIOS GBA facultatif (`ravenemu_set_gba_bios`) ; l'application ne s'en sert pas encore. La 0.13.0 a ajouté le MBC30 (*Pocket Monsters Crystal Version*). Sur un réseau lent, la GBA qui ne cadence pas les transferts garde le pas de l'autre depuis la 0.11.0 : elle ne prend plus d'images d'avance, ce qui coupait les échanges Pokémon par Wi-Fi ([documentation](https://github.com/GhostPunishR/RavenCore/blob/15675cc3af4f9b07f2e10cc5a7ca6234ea1c77cc/docs/remote_link.md)). Le protocole reste en version 2, celui de la 0.10.0, qui a rendu possibles les échanges Pokémon ([#32](https://github.com/GhostPunishR/RavenCore/pull/32)). La 0.9.0 avait ajouté ce câble, qui relie une console à celle d'un autre appareil (`ravenemu_remote_link_*`), et `ravenemu_link_wanted`, qui signale qu'un jeu se sert de son port link ([#31](https://github.com/GhostPunishR/RavenCore/pull/31)). Elle garde la carte mémoire RetroAchievements Game Boy et Game Boy Color complète de la 0.8.0 ([#29](https://github.com/GhostPunishR/RavenCore/pull/29)) et la [correction RTC Pokémon/CFRU](https://github.com/GhostPunishR/RavenCore/pull/28). L'épinglage précédent était le commit [`abe53ae`](https://github.com/GhostPunishR/RavenCore/commit/abe53ae06ccdbdba283427ac1b1e961ddd58f64d) (0.11.0). Une branche ou un tag mobile n'est pas utilisé.

L'API C reste en version 0, aux noms `ravenemu_*`, câbles link `ravenemu_link_*` et `ravenemu_remote_link_*` compris ; formats d'états GB/GBC 11 et GBA 17 (les formats GBA 16 à 11 se rechargent ; un état GBA au format 17 ne se recharge pas dans une version épinglée sur RavenCore 0.13 ou avant). La détection reconnaît désormais certains pilotes d'horloge Pokémon sans marqueur Seiko ; le protocole RTC et la gestion des dates sont aussi corrigés. Le bloc RTC batterie garde ses 16 octets et les anciennes sauvegardes Flash restent utilisables. Un ancien état d'un hack créé sans RTC peut être incompatible avec la cartouche désormais détectée avec RTC : reprendre depuis la sauvegarde normale du jeu. Voir les [preuves et limites de RavenCore](https://github.com/GhostPunishR/RavenCore/blob/15675cc3af4f9b07f2e10cc5a7ca6234ea1c77cc/cores/gba/tests/rtc_compatibility.md).

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
