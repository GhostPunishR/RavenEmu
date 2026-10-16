# RavenEmu

[![Build](https://github.com/GhostPunishR/RavenEmu/actions/workflows/multiplateforme.yml/badge.svg)](https://github.com/GhostPunishR/RavenEmu/actions/workflows/multiplateforme.yml)

Émulateur open source **Game Boy, Game Boy Color et Game Boy Advance**, propulsé par les moteurs [RavenCore](https://github.com/GhostPunishR/RavenCore).

**v0.1.0 - bêta · Android 8.0 et plus.** La version iOS est en développement ; aucune application iOS n’est encore distribuée.

[Documentation](wiki/Home.md) · [Changelog](CHANGELOG.md) · [Signaler un problème](https://github.com/GhostPunishR/RavenEmu/issues)

## Fonctionnalités

- **Bibliothèque** : import de jeux ou de dossiers, recherche, vues grille/liste et jaquettes.
- **Commandes** : boutons tactiles, manettes Android et skins `.deltaskin` en portrait.
- **Sauvegardes** : sauvegarde de cartouche, quatre emplacements d’état et transfert ZIP ou `.sav`.
- **Réglages** : image, son, disposition des commandes, vibration et capteurs de cartouche (gyroscope, inclinaison, lumière).
- **RetroAchievements** : intégration Softcore avec connexion facultative. Hardcore désactivé.

La bêta reste en cours de validation, notamment pour les performances GBC/GBA et RetroAchievements sur téléphone.

## Installer sur Android

1. Ouvrez un [build réussi de `main`](https://github.com/GhostPunishR/RavenEmu/actions/workflows/multiplateforme.yml?query=branch%3Amain+is%3Asuccess).
2. Téléchargez **`ravenemu-android-beta`** et extrayez l’APK du ZIP.
3. Installez l’APK, puis importez vos jeux depuis l’application.

Un compte GitHub est nécessaire ; les artefacts sont conservés **7 jours**. Exportez vos sauvegardes avant toute désinstallation. [Guide d’installation](wiki/Installation.md).

## Développer

Depuis la racine du dépôt, avec JDK 21, le SDK Android API 37 et les outils CMake/NDK :

```sh
./gradlew :androidApp:assembleDebug
```

[Compiler et tester](BUILDING.md) · [Architecture](ARCHITECTURE.md) · [Contribuer](CONTRIBUTING.md)

---

Aucun jeu fourni. Sans publicité ni télémétrie. [Confidentialité](PRIVACY.md) · [Sécurité](SECURITY.md)

**[Licence AGPL-3.0](LICENSE)** · © 2026 GhostPunishR. Projet indépendant, non affilié à Nintendo.
