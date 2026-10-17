# Architecture de RavenEmu

RavenEmu partage une interface Compose Multiplatform entre Android et iOS. Le projet Gradle est à la racine ; les moteurs C++20 vivent dans [RavenCore](https://github.com/GhostPunishR/RavenCore).

| Module | Responsabilité |
| --- | --- |
| `shared/` | Interface Compose, modèles, navigation, réglages et logique Kotlin commune |
| `androidApp/` | Activité, session de jeu, audio, stockage privé, imports, manettes, skins et RetroAchievements |
| `iosApp/` | Hôte SwiftUI, spécification XcodeGen et adaptateur Swift |
| `native/` | CMake, pont JNI Android et module API C pour Apple |
| `policy/` | Tests JVM des règles de CI et des chemins du dépôt |
| `docs/` | Sources du site statique |
| `wiki/` | Documentation et sources du wiki |

`settings.gradle.kts` inclut `shared`, `androidApp` et `policy`. Toutes les commandes Gradle partent de la racine. Les documents `TERMS.md`, `PRIVACY.md` et `LICENSE` y sont lus pour générer les textes légaux intégrés au module partagé.

## Frontière native

La [configuration CMake](native/CMakeLists.txt) épingle RavenCore au commit `453f9b097c91de67d6fa4a7179e9f2ac5268e7f6` : version 0.5.0 et API C aux noms `ravenemu_*`. Mettre à jour le dépôt RavenCore ne modifie pas automatiquement cette révision.

Android utilise `NativeCore.kt` et `native/android/bridge_jni.cpp`. Swift utilise `RavenNativeCore.swift` et `native/apple/module.modulemap`. Les deux passent par la même API C : ROM, pixels ARGB8888, audio PCM16, boutons, mémoire et sauvegardes. Le header est celui du SDK RavenCore épinglé, utilisé tel quel. Libretro n'est pas l'interface d'émulation de l'application ; son catalogue de jaquettes reste un service facultatif.

RetroAchievements utilise le SDK officiel rcheevos, également épinglé par SHA, et le pont maintenu dans `native/android/`.

## Contrats à préserver

- Les moteurs ignorent l'interface et les services des plateformes.
- Les appels à une même instance native sont sérialisés.
- Android conserve bibliothèque, jaquettes et sauvegardes dans son espace privé, avec le SHA-256 de la ROM comme identité.
- Les imports utilisent le sélecteur Android sans accès global au stockage.
- Les ZIP de sauvegarde contiennent un manifeste et des empreintes, aucune ROM ; les archives historiques restent importables.
- Les paquets sont `com.ravenemu.android` et `com.ravenemu.shared`, l'identifiant Android `com.ravenemu.preview`. Le déplacement du dépôt ne change pas cette identité ni les clés de stockage.

## État des plateformes

Android raccorde le lecteur, le rendu, l'audio, les commandes, la bibliothèque, les skins et les sauvegardes. Les performances GBC/GBA et les déblocages RetroAchievements nécessitent encore des essais sur téléphone.

iOS dispose des écrans partagés et du pont natif ; l'import, la persistance et le lecteur jouable restent à connecter. Une compilation ne prouve pas une partie jouable. Voir [BUILDING.md](BUILDING.md), [CONTRIBUTING.md](CONTRIBUTING.md) et [CHANGELOG.md](CHANGELOG.md).
