# RavenEmu

[![Build](https://github.com/GhostPunishR/RavenEmu/actions/workflows/multiplateforme.yml/badge.svg)](https://github.com/GhostPunishR/RavenEmu/actions/workflows/multiplateforme.yml)
[![Anthropic](https://img.shields.io/badge/Anthropic-Claude-D97757?logo=anthropic&logoColor=white)](https://www.anthropic.com/)
[![Licence AGPL-3.0](https://img.shields.io/badge/Licence-AGPL--3.0-663399?logo=gnu&logoColor=white)](LICENSE)

Émulateur open source **Game Boy, Game Boy Color et Game Boy Advance**, propulsé par les moteurs [RavenCore](https://github.com/GhostPunishR/RavenCore).

**v0.1.0 - bêta · Android 8.0 et plus.** La première intégration iOS (import, lecteur, audio et sauvegardes) est [en validation](iosApp/README.md) ; aucune version iPhone signée n’est distribuée.

[Télécharger la bêta Android](https://github.com/GhostPunishR/RavenEmu/releases) · [Site officiel](https://ghostpunishr.github.io/RavenEmu/) · [Documentation](wiki/Home.md) · [Changelog](CHANGELOG.md) · [Signaler un problème](https://github.com/GhostPunishR/RavenEmu/issues)

## Fonctionnalités

- **Bibliothèque** : import de jeux ou de dossiers, recherche, vues grille/liste et jaquettes.
- **Commandes** : boutons tactiles, manettes Android et skins `.deltaskin` en portrait.
- **Sauvegardes** : sauvegarde de cartouche, quatre emplacements d’état et transfert `.sav`/`.srm` par jeu ou ZIP de toute la bibliothèque.
- **Jouer à deux** : deux jeux en même temps sur un téléphone, reliés par un câble link virtuel, pour échanger ou se battre entre ses propres sauvegardes.
- **Réglages** : image, son, disposition des commandes, vibration et capteurs de cartouche (gyroscope, inclinaison, lumière).
- **RetroAchievements** : intégration Softcore avec connexion facultative. Hardcore désactivé.

La bêta reste en cours de validation, notamment pour les performances GBC/GBA et RetroAchievements sur téléphone.

## Installer sur Android

1. Ouvrez les [préversions Android](https://github.com/GhostPunishR/RavenEmu/releases) et choisissez la bêta au numéro le plus élevé.
2. Dans les fichiers de la préversion, téléchargez **`ravenemu-beta.apk`**.
3. Installez l’APK, puis importez vos jeux depuis l’application.

Aucun compte GitHub ni extraction ZIP nécessaire. Chaque CI réussie de `main` publie une préversion avec son APK signé, conservé dans les Releases. Les nouvelles bêtas s’installent par-dessus les précédentes avec vos données. Si un ancien APK a une signature différente, exportez vos sauvegardes avant la première désinstallation. [Guide d’installation](wiki/Installation.md).

## Développer

Depuis la racine du dépôt, avec JDK 21, le SDK Android API 37 et les outils CMake/NDK :

```sh
./gradlew :androidApp:assembleDebug
```

[Compiler et tester](BUILDING.md) · [Architecture](ARCHITECTURE.md) · [Contribuer](CONTRIBUTING.md)

---

Aucun jeu fourni. Sans publicité ni télémétrie. [Confidentialité](PRIVACY.md) · [Sécurité](SECURITY.md)

**[Licence AGPL-3.0](LICENSE)** · © 2026 GhostPunishR. Projet indépendant, non affilié à Nintendo.
