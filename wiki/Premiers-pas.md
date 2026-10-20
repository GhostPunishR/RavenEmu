# Premiers pas

Les étapes suivantes concernent la **bêta Android multiplateforme**. La première intégration iOS est décrite dans [son guide](https://github.com/GhostPunishR/RavenEmu/blob/main/iosApp/README.md), avec les essais restants sur appareil.

## 1. Importer un jeu

1. Ouvrez RavenEmu.
2. Touchez **+** dans la bibliothèque.
3. Choisissez un fichier `.gb`, `.gbc` ou `.gba` avec le sélecteur Android.
4. Attendez la validation et l'ajout du jeu.

L'application conserve une copie privée persistante de la ROM, identifiée par SHA-256. Vous n'avez pas à sélectionner de nouveau le fichier d'origine au lancement suivant.

Pour plusieurs jeux, ouvrez **⋮ → Importer un dossier**. Les sous-dossiers sont parcourus dans des limites définies et un récapitulatif indique les ajouts, doublons et fichiers refusés. Voir [Bibliothèque et ROM](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Bibliotheque-et-ROM.md).

## 2. Lancer une partie

Touchez le jeu dans la grille ou la liste, puis **Jouer** dans sa fiche. Les pages de console et leurs pictogrammes permettent de naviguer dans la bibliothèque.

La console Game Boy ou Game Boy Color est choisie d'après l'en-tête de la ROM, et non sa seule extension. Les fichiers GBA sont chargés dans le moteur dédié.

## 3. Régler l'expérience

Dans **Paramètres** :

- **Audio** : activation du son et volume ;
- **Vidéo** : profil Original, DMG vert ou Pocket gris, luminosité et contraste ;
- **Interface** : retour haptique, taille, espacement et style des commandes ;
- **Général** : jaquettes en ligne facultatives et diagnostic de performance.

Les profils DMG et Pocket s'appliquent uniquement aux jeux monochromes. Voir [Affichage et audio](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Affichage-et-audio.md) et [Commandes](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Commandes.md).

## 4. Conserver sa progression

Utilisez la sauvegarde normale du jeu lorsque celui-ci en possède une. Le menu **⋮** du lecteur propose aussi quatre emplacements d'état avec miniature et date.

Quittez avec les commandes de l'application. Le jeu et l'audio sont suspendus en arrière-plan ; la mémoire de sauvegarde modifiée est persistée périodiquement et aux transitions prévues. Évitez de forcer l'arrêt immédiatement après une sauvegarde.

## 5. Faire une copie externe

Depuis la fiche du jeu, utilisez **Exporter toutes les sauvegardes (.zip)** ou **Exporter .sav**. Conservez l'export hors de l'espace privé de l'application avant de changer d'installation.

Voir [Sauvegardes et états](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Sauvegardes-et-etats.md) et [Dépannage](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Depannage.md).
