# Bibliothèque et ROM

La bibliothèque persistante décrite ici est raccordée à l'hôte Android de la version multiplateforme. La persistance iOS reste à implémenter.

## Import d'un fichier

Le bouton **+** ouvre le sélecteur de documents Android. Les extensions `.gb`, `.gbc` et `.gba` sont acceptées. Les fichiers vides, invalides ou de plus de **32 Mio** sont refusés.

Chaque ROM correctement chargée est copiée dans l'espace privé de l'application. L'index conserve ses métadonnées et sa console. Son empreinte **SHA-256** identifie le contenu : importer la même ROM ne crée pas de doublon, même si son nom change.

Le choix GB/GBC utilise l'octet `0x143` de l'en-tête : `0x80` ou `0xC0` sélectionnent Game Boy Color. Voir [Consoles et compatibilité](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Consoles-et-compatibilite.md).

## Import d'un dossier

Dans la bibliothèque, ouvrez **⋮ → Importer un dossier** et choisissez un répertoire. Les ROM sont validées avec un cœur temporaire sans remplacer le jeu actif.

Le parcours est borné à :

| Limite | Valeur |
|---|---|
| Éléments | 4 000 |
| Dossiers | 128 |
| Profondeur | 8 niveaux |
| ROM candidates | 256 |
| Données lues | 256 Mio |

Le bilan distingue jeux ajoutés, doublons, ROM invalides, éléments illisibles et fichiers ignorés. Une limite atteinte est signalée. Importez un sous-dossier séparément si nécessaire.

L'application n'obtient pas d'accès global au stockage ni d'autorisation permanente sur le dossier : elle garde ses propres copies. Déplacer ou supprimer l'original ne retire donc pas automatiquement le jeu importé.

## Parcourir les jeux

- recherche permanente ;
- vue grille ou liste ;
- pages pour les consoles qui contiennent des jeux ;
- navigation par balayage et pictogrammes de console ;
- fiche de jeu avec **Jouer**, jaquette et actions de transfert de sauvegardes.

Ne supposez pas que les tris, surveillances de dossiers ou options de l'ancienne application sont présents dans cette version.

## Jaquettes

Dans la fiche, **Image manuelle (facultatif)** ou **Changer l'image** permet de choisir une image. Elle est redimensionnée et copiée dans le stockage privé ; elle reste visible après redémarrage. **Retirer la jaquette** ne supprime ni ROM ni sauvegarde.

L'option **Paramètres → Général → Jaquettes en ligne** cherche les images manquantes sur `thumbnails.libretro.com`. Elle est **désactivée par défaut**. Une image manuelle est conservée ; une jaquette retirée n'est pas téléchargée de nouveau automatiquement pour ce jeu.

Sans correspondance ou sans réseau, le jeu conserve un emplacement neutre. Aucun téléchargement de ROM n'est effectué. Voir [Légalité et confidentialité](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Legalite-et-confidentialite.md).

## Conservation

La copie de la ROM, l'index, les jaquettes et les sauvegardes internes disparaissent après désinstallation ou effacement des données. Sauvegardez vos originaux et [exportez votre progression](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Sauvegardes-et-etats.md).

Source : [bibliothèque Android](https://github.com/GhostPunishR/RavenEmu/blob/main/androidApp/src/main/kotlin/com/ravenemu/android/GbRomLibrary.kt).
