# Architecture de RavenEmu

RavenEmu partage une interface Compose Multiplatform entre Android et iOS. Le projet Gradle est à la racine ; les moteurs C++20 vivent dans [RavenCore](https://github.com/GhostPunishR/RavenCore).

| Module | Responsabilité |
| --- | --- |
| `shared/` | Interface Compose, modèles, navigation, réglages et logique Kotlin commune |
| `androidApp/` | Activité, session de jeu, audio, stockage privé, imports, manettes, skins et RetroAchievements |
| `iosApp/` | Hôte SwiftUI, session série, stockage, audio, spécification XcodeGen et adaptateur Swift |
| `native/` | CMake, pont JNI Android et module API C pour Apple |
| `policy/` | Tests JVM des règles de CI et des chemins du dépôt |
| `docs/` | Sources du site statique |
| `wiki/` | Documentation et sources du wiki |

`settings.gradle.kts` inclut `shared`, `androidApp` et `policy`. Toutes les commandes Gradle partent de la racine. Les documents `TERMS.md`, `PRIVACY.md` et `LICENSE` y sont lus pour générer les textes légaux intégrés au module partagé.

## Frontière native

La [configuration CMake](native/CMakeLists.txt) épingle RavenCore au commit `9fd041c6bc0b2a49357c93dd5fae707395c44d63` (version 0.8.0) et à son API C aux noms `ravenemu_*`. Mettre à jour le dépôt RavenCore ne modifie pas automatiquement cette révision.

Android utilise `NativeCore.kt` et `native/android/bridge_jni.cpp`. Swift utilise `RavenNativeCore.swift` et `native/apple/module.modulemap`. Les deux passent par la même API C : ROM, pixels ARGB8888, audio PCM16, boutons, mémoire et sauvegardes. Le header est celui du SDK RavenCore épinglé, utilisé tel quel. Libretro n'est pas l'interface d'émulation de l'application ; son catalogue de jaquettes reste un service facultatif.

RetroAchievements utilise le SDK officiel rcheevos, également épinglé par SHA, et le pont maintenu dans `native/android/`.

## Câble link sans fil (Android)

Deux téléphones du même réseau local se relient par le câble distant de RavenCore (`ravenemu_remote_link_*`, [docs/remote_link.md de RavenCore](https://github.com/GhostPunishR/RavenCore/blob/main/docs/remote_link.md)). RavenCore échange les données du câble ; l'application les transporte et décide quand brancher.

| Pièce | Rôle |
| --- | --- |
| `NativeRemoteLink.kt` | Le câble natif rattaché au jeu en cours ; `AndroidGbSession` le crée et le détruit entre deux images. |
| `WirelessLink.kt` | Décide de tout, sur un seul fil : recherche, choix, liaison, pause, débranchement. |
| `WirelessLinkDiscovery.kt` | Annonce et recherche DNS-SD (`NsdManager`, service `_ravenemulink._tcp`). |
| `WirelessLinkConnection.kt` | Une connexion TCP : un fil lit, un autre écrit, battement de cœur. |
| `WirelessLinkProtocol.kt` | Trames, messages de contrôle et règle de choix, testés sur la JVM. |

- **Quand.** `ravenemu_link_wanted` dit si le jeu s'est servi de son port link dans les trois dernières secondes. Tant que c'est le cas (plus dix secondes de marge), le téléphone s'annonce et cherche. Le reste du temps, il n'annonce rien.
- **Qui.** Un seul partenaire compatible (même version, même famille de consoles) : le téléphone au plus petit identifiant l'appelle. Plusieurs : le joueur choisit. Deux connexions entre les mêmes téléphones : les deux côtés gardent celle qu'a ouverte le plus petit identifiant, qui est aussi le joueur 0 (parent GBA).
- **Trames TCP.** `[canal u8][taille u8][contenu]`. Canal 0 : les messages de RavenCore, tels quels. Canal 1 : présentation (version, famille, identifiant, pause, modèle, jeu), pause, battement de cœur (chaque seconde sans autre envoi) et départ (avec ou sans refus). Cinq secondes sans rien recevoir ferment la connexion.
- **Fils.** RavenCore envoie depuis le fil d'émulation : l'envoi ne fait que remplir une file. Les messages reçus vont au câble natif depuis le fil de lecture, puis un fil de service les fait lire à la console entre deux images, ou pendant une pause, sous le verrou de la session.
- **Une image peut attendre l'autre console.** Le fil de l'interface ne prend donc plus le verrou natif : les boutons s'appliquent avant l'image suivante, les caractéristiques du jeu sont gardées à part, et la sauvegarde de `onPause` abandonne après 300 ms. `onStop` débranche le câble, ce qui libère une image en attente, puis sauvegarde.
- **Pause.** Le menu du jeu, ou une interruption, envoie une pause à l'autre téléphone tant que le câble sert. Celui-ci n'avance plus d'image, mais sa console répond toujours aux transferts.
- **Interdits.** Charger un état est refusé tant que le câble est branché, dans l'interface et dans la session.

## Contrats à préserver

- Les moteurs ignorent l'interface et les services des plateformes.
- Les appels à une même instance native sont sérialisés.
- Android conserve bibliothèque, jaquettes et sauvegardes dans son espace privé, avec le SHA-256 de la ROM comme identité.
- Les imports utilisent le sélecteur Android sans accès global au stockage.
- Les ZIP de sauvegarde contiennent un manifeste et des empreintes, aucune ROM ; les archives historiques restent importables.
- Les paquets sont `com.ravenemu.android` et `com.ravenemu.shared`, l'identifiant Android `com.ravenemu.preview`. Le déplacement du dépôt ne change pas cette identité ni les clés de stockage.

## État des plateformes

Android raccorde le lecteur, le rendu, l'audio, les commandes, la bibliothèque, les skins et les sauvegardes. Les performances GBC/GBA et les déblocages RetroAchievements nécessitent encore des essais sur téléphone.

iOS raccorde une première session : `IosHost` détient les états Compose sur le thread principal, `RavenRuntime` détient les handles sur une file série, `RavenLibraryStore` conserve les copies et écrit atomiquement, `RavenAudioOutput` borne la file audio. Les frames immuables passent par NSData vers Compose ; un seul envoi d'image peut attendre le thread principal. Les réglages des services non raccordés sont masqués. Voir [la première tranche iOS](iosApp/README.md) ; les essais réels et la distribution iPhone restent à effectuer. Une compilation ne prouve pas une partie jouable sur appareil. Voir [BUILDING.md](BUILDING.md), [CONTRIBUTING.md](CONTRIBUTING.md) et [CHANGELOG.md](CHANGELOG.md).
