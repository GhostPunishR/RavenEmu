# RavenEmu

Bienvenue dans la documentation de la **nouvelle version multiplateforme de RavenEmu**. Le projet autonome se construit depuis la [racine du dépôt](https://github.com/GhostPunishR/RavenEmu) et utilise les moteurs C++20 du dépôt [RavenCore](https://github.com/GhostPunishR/RavenCore).

L'application installée s'appelle **RavenEmu**. Les paquets Kotlin et les noms des fichiers sont désormais sans marqueur de génération.

## Disponibilité

| Plateforme | État actuel |
|---|---|
| Android 8.0 et plus | Bêta : bibliothèque, lecteur GB/GBC/GBA, audio, commandes tactiles et sauvegardes raccordés |
| iOS | Interface partagée et passerelle native présentes ; import, persistance et lecteur jouable à connecter. Aucun binaire signé distribué |

La compatibilité GBC/GBA, les performances sur téléphone et les déblocages RetroAchievements nécessitent encore des validations. Un build réussi n'atteste pas du fonctionnement de chaque jeu.

## Commencer

1. [Installer la bêta Android](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Installation.md)
2. [Importer un jeu et lancer une partie](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Premiers-pas.md)
3. [Sauvegarder et transférer sa progression](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Sauvegardes-et-etats.md)
4. [Régler l'image, le son et les commandes](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Affichage-et-audio.md)

## Explorer

- [Bibliothèque et ROM](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Bibliotheque-et-ROM.md)
- [Consoles et compatibilité](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Consoles-et-compatibilite.md)
- [Matrice des tests de jeux](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Compatibilite-des-jeux.md)
- [Jouer à deux sur un téléphone (câble link)](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Jouer-a-deux.md)
- [RetroAchievements](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/RetroAchievements.md)
- [Dépannage](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Depannage.md)
- [Compilation](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Compilation.md), [Architecture](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Architecture.md) et [Contribution](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Contribution.md)
- [Feuille de route](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Feuille-de-route.md)

## Version et données

La version initiale de ce dépôt est **v0.1.0 - bêta**. Le [changelog](https://github.com/GhostPunishR/RavenEmu/blob/main/CHANGELOG.md) décrit son contenu complet. L'identifiant Android reste `com.ravenemu.preview` ; le déplacement ne change pas les clés de stockage. Une mise à jour d'APK exige aussi le même certificat de signature : exportez vos sauvegardes avant une désinstallation.

## Liens officiels

- [Bêta Android : workflow multiplateforme](https://github.com/GhostPunishR/RavenEmu/actions/workflows/multiplateforme.yml?query=branch%3Amain+is%3Asuccess)
- [Site officiel](https://ghostpunishr.github.io/RavenEmu/)
- [État détaillé du projet](https://github.com/GhostPunishR/RavenEmu/blob/main/README.md)
- [Issues](https://github.com/GhostPunishR/RavenEmu/issues)

Aucun jeu n'est fourni. Exportez les sauvegardes importantes avant de désinstaller ou d'effacer les données de RavenEmu.

Voir [Installation](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Installation.md).
