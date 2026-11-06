# RavenEmu

Bienvenue dans la documentation de **RavenEmu**, émulateur Game Boy, Game Boy Color et Game Boy Advance. L'application Android est native : une interface Java, un frontend C et les cores Libretro des moteurs C++20 du dépôt [RavenCore](https://github.com/GhostPunishR/RavenCore). Le projet se construit depuis la [racine du dépôt](https://github.com/GhostPunishR/RavenEmu).

Depuis le 6 novembre 2026, cette application remplace la bêta Kotlin/Compose, retirée du dépôt avec l'application iOS.

## Disponibilité

| Plateforme | État actuel |
|---|---|
| Android 8.0 et plus, ARM64 | En développement : bibliothèque, lecteur GB/GBC/GBA, son, commandes tactiles et manettes, skins Delta, câble link sur un téléphone, codes de triche, sauvegardes et RetroAchievements raccordés. APK de développement, sans version publique pour l'instant |
| PC (Windows, Linux, macOS) | Préversion, dans le dépôt [RavenEmuDSK](https://github.com/GhostPunishR/RavenEmuDSK) |

Pas encore dans l'application native : le câble link sans fil entre deux téléphones, l'avance rapide, le retour arrière et la capture d'écran. La compatibilité GBC/GBA, les performances sur téléphone et les déblocages RetroAchievements nécessitent encore des validations. Un build réussi n'atteste pas du fonctionnement de chaque jeu.

## Commencer

1. [Installer RavenEmu sur Android](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Installation.md)
2. [Importer un jeu et lancer une partie](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Premiers-pas.md)
3. [Sauvegarder et transférer sa progression](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Sauvegardes-et-etats.md)
4. [Régler l'image, le son et les commandes](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Affichage-et-audio.md)

## Explorer

- [Bibliothèque et ROM](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Bibliotheque-et-ROM.md)
- [Consoles et compatibilité](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Consoles-et-compatibilite.md)
- [Matrice des tests de jeux](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Compatibilite-des-jeux.md)
- [Jouer à deux (câble link) sur un téléphone](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Jouer-a-deux.md)
- [RetroAchievements](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/RetroAchievements.md)
- [Dépannage](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Depannage.md)
- [Compilation](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Compilation.md), [Architecture](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Architecture.md) et [Contribution](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Contribution.md)
- [Feuille de route](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Feuille-de-route.md)

## Version et données

L'application native n'a pas encore de version publique ; le [changelog](https://github.com/GhostPunishR/RavenEmu/blob/main/CHANGELOG.md) décrit son contenu. Son identifiant Android est `com.ravenemu.dev` : elle s'installe à côté de la bêta `com.ravenemu.preview` sans lire ses données. Exportez les sauvegardes de la bêta pour les importer dans l'application native.

## Liens officiels

- [Compilations Android (workflow RavenEmu / Applications)](https://github.com/GhostPunishR/RavenEmu/actions/workflows/ci-applications.yml?query=branch%3Amain+is%3Asuccess)
- [Site officiel](https://ghostpunishr.github.io/RavenEmu/)
- [Présentation du projet](https://github.com/GhostPunishR/RavenEmu/blob/main/README.md)
- [Issues](https://github.com/GhostPunishR/RavenEmu/issues)

Aucun jeu n'est fourni. Exportez les sauvegardes importantes avant de désinstaller ou d'effacer les données de RavenEmu.
