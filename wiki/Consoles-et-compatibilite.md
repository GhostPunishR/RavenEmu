# Consoles et compatibilité

La version multiplateforme utilise les moteurs GB, GBC et GBA de [RavenCore](https://github.com/GhostPunishR/RavenCore), récupérés à une **révision épinglée** dans la [configuration native](https://github.com/GhostPunishR/RavenEmu/blob/main/native/CMakeLists.txt).

Les possibilités d'un moteur dans RavenCore ne sont pas toutes exposées dans l'application. Les anciennes descriptions matérielles de l'ancienne application ne constituent pas une liste de fonctions disponibles dans ce lecteur.

## Consoles dans le lecteur Android

| Console | Import | Image native | Commandes |
|---|---|---|---|
| Game Boy | `.gb`, après validation | 160 × 144 | Croix, A/B, Start/Select |
| Game Boy Color | `.gbc` ou ROM couleur reconnue par en-tête | 160 × 144, couleur | Croix, A/B, Start/Select |
| Game Boy Advance | `.gba`, après validation | 240 × 160 | Les mêmes, plus L/R |

Bibliothèque, rendu, audio et sauvegardes sont raccordés aux moteurs sur Android. La compatibilité et les performances restent à vérifier **jeu par jeu et appareil par appareil**, en particulier pour GBC et GBA.

## Choix GB ou GBC

L'extension seule ne détermine pas le mode d'une cartouche GB/GBC. L'en-tête à l'octet `0x143` est interprété ainsi :

| Valeur | Mode choisi par cette application |
|---|---|
| `0x80` | Game Boy Color, cartouche compatible couleur |
| `0xC0` | Game Boy Color, cartouche exigeant la couleur |
| Autre | Game Boy monochrome |

Un `.gb` peut donc apparaître dans la page GBC. Les profils DMG vert et Pocket gris ne recolorent pas les jeux GBC.

Lors de la reclassification des anciennes importations de cette nouvelle version, la sauvegarde de cartouche reste associée au hash. Un état enregistré sur GB ne se recharge pas automatiquement sur GBC. Voir [Sauvegardes et états](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Sauvegardes-et-etats.md).

## Game Boy Advance

Le lecteur accepte jusqu'à 32 Mio, adapte les dimensions de l'image et affiche L/R. Le type de sauvegarde et l'horloge de cartouche sont détectés par RavenCore. Aucun fichier BIOS externe n'est demandé à l'utilisateur par ce lecteur.

Une ROM acceptée et un build réussi ne garantissent pas une partie jouable ou une performance suffisante.

## Fonctions à ne pas confondre

- Le câble link relie deux jeux du même téléphone ([Jouer à deux](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Jouer-a-deux.md)). Le jeu entre deux téléphones, l'infrarouge et l'adaptateur sans fil ne sont pas raccordés.
- Les skins `.deltaskin` en portrait sont intégrés sur Android et restent à valider sur appareil ; le déplacement libre des commandes n’est pas proposé.
- La première intégration du lecteur iOS est raccordée ; les essais de compatibilité sur appareil restent à effectuer.
- [RetroAchievements](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/RetroAchievements.md) fonctionne en Softcore dans l'intégration, avec validation réelle des déblocages encore nécessaire. Hardcore est désactivé.

## Résultats par jeu

La [matrice de compatibilité](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Compatibilite-des-jeux.md) rassemble les résultats reproductibles pour une ROM exacte, un commit et un appareil. Aucun résultat ne doit être extrapolé depuis l'ancienne application ou depuis la dernière branche de RavenCore sans tester la révision réellement livrée.
