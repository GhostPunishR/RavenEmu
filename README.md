# RavenEmu

[![Build multiplateforme](https://github.com/GhostPunishR/RavenEmu/actions/workflows/multiplateforme.yml/badge.svg)](https://github.com/GhostPunishR/RavenEmu/actions/workflows/multiplateforme.yml)

**RavenEmu v0.1.0 — bêta** est un émulateur open source **Game Boy, Game Boy Color et Game Boy Advance**. La nouvelle version partage son interface entre Android et iOS et utilise les moteurs C++20 de [RavenCore](https://github.com/GhostPunishR/RavenCore).

[Site officiel](https://ghostpunishr.github.io/RavenEmu/) · [Wiki](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Home.md) · [Signaler un problème](https://github.com/GhostPunishR/RavenEmu/issues)

## État du projet

| Plateforme | Disponibilité |
|---|---|
| Android 8.0 et plus | Bêta avec bibliothèque, lecteur GB/GBC/GBA, audio et sauvegardes |
| iOS | En développement : interface partagée présente, lecteur jouable à connecter. Aucun binaire distribué |

La compatibilité dépend du jeu et de l'appareil. Les performances GBC/GBA et les déblocages RetroAchievements restent à valider sur téléphone.

## Fonctionnalités Android

- Import d'un jeu ou d'un dossier, bibliothèque persistante, recherche et vues grille/liste.
- Commandes tactiles, manettes Android et réglages de l'image et du son.
- Import et sélection de skins de contrôleur `.deltaskin` en portrait, sans skin fourni.
- Sauvegardes de cartouche, quatre emplacements d'état et transfert ZIP ou `.sav`.
- Jaquettes manuelles ou recherche en ligne facultative, désactivée par défaut.
- RetroAchievements Softcore intégré, avec connexion facultative. Hardcore désactivé.

Aucun jeu n'est fourni. RavenEmu fonctionne sans publicité ni télémétrie et ne téléverse pas vos ROM ou sauvegardes. Les échanges des fonctions en ligne sont décrits dans [PRIVACY.md](PRIVACY.md).

## Installer la bêta

1. Ouvrez les [builds officiels de `main`](https://github.com/GhostPunishR/RavenEmu/actions/workflows/multiplateforme.yml?query=branch%3Amain+is%3Asuccess) et choisissez une exécution réussie.
2. Téléchargez l'artefact **`ravenemu-android-beta`**, puis extrayez l'APK du ZIP.
3. Installez l'APK sur Android et importez vos jeux avec **+** ou **Importer un dossier**.

Un compte GitHub connecté est requis ; les artefacts sont conservés 7 jours. Exportez vos sauvegardes avant toute désinstallation. Voir le [guide d'installation](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Installation.md).

## Compiler

Avec JDK 21, le SDK Android API 37 et les outils natifs Android :

```sh
git clone https://github.com/GhostPunishR/RavenEmu.git
cd RavenEmu
./gradlew :androidApp:assembleDebug
```

Les prérequis détaillés, tests et instructions iOS sont dans le [guide de compilation](https://github.com/GhostPunishR/RavenEmu/blob/main/BUILDING.md).

Le projet autonome se construit depuis la racine. Voir [BUILDING.md](BUILDING.md), [ARCHITECTURE.md](ARCHITECTURE.md) et le [changelog v0.1.0 — bêta](CHANGELOG.md).

## Contribuer et licence

Consultez le [guide de contribution](CONTRIBUTING.md) et le [code de conduite](CODE_OF_CONDUCT.md). Pour une vulnérabilité, suivez la [politique de sécurité](SECURITY.md).

Licence **[AGPL-3.0](LICENSE)**. © 2026 GhostPunishR.

Game Boy, Game Boy Color et Game Boy Advance sont des marques de Nintendo. RavenEmu n'est ni affilié à Nintendo, ni approuvé par Nintendo.
