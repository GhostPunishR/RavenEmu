# Bibliothèque et ROM

Cette page décrit l'application Android native.

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
- fiche de jeu avec **Jouer**, jaquette, export `.sav`/`.srm` et import de sauvegarde ;
- menu **⋮** : vue grille ou liste, **Importer un dossier**, **Actualiser la bibliothèque de jeux** (relit la bibliothèque et ses jaquettes) et **Exporter toutes les sauvegardes (.zip)**. **À propos** se trouve dans **Paramètres**.

Ne supposez pas que les tris, surveillances de dossiers ou options de la bêta retirée sont présents dans cette version.

## Jaquettes

Dans la fiche, **Image manuelle (facultatif)** ou **Changer l'image** permet de choisir une image. Elle est redimensionnée et copiée dans le stockage privé ; elle reste visible après redémarrage. **Retirer la jaquette** ne supprime ni ROM ni sauvegarde.

L'application native ne télécharge aucune jaquette : la recherche en ligne de la bêta n'est pas reprise. Sans image, le jeu montre le pictogramme et le nom de sa console. Aucun téléchargement de ROM n'est effectué. Voir [Légalité et confidentialité](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Legalite-et-confidentialite.md).

## Conservation

La copie de la ROM, l'index, les jaquettes et les sauvegardes internes disparaissent après désinstallation ou effacement des données. Sauvegardez vos originaux et [exportez votre progression](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Sauvegardes-et-etats.md).

Source : [bibliothèque Android](https://github.com/GhostPunishR/RavenEmu/blob/main/android/src/main/java/com/ravenemu/app/data/library/RomLibrary.java).
