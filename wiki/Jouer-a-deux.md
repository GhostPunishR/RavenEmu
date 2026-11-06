# Jouer à deux

RavenEmu relie deux jeux par un câble link **sur un même téléphone** : deux jeux tournent en même temps. Le cas typique : échanger des Pokémon entre vos propres sauvegardes, par exemple entre Rubis et Saphir, ou faire évoluer un Pokémon par échange. Le câble entre deux téléphones n'est pas encore dans l'application native ([voir plus bas](#entre-deux-téléphones--pas-encore-disponible)).

## Sur un téléphone

### Lancer une partie à deux

1. Dans la bibliothèque, touchez le premier jeu.
2. Choisissez **Jouer à deux (câble link)**.
3. Choisissez le second jeu dans la liste.

Le premier jeu est le joueur 1. Sur Game Boy Advance, c'est aussi la console parente des jeux multijoueurs.

### Jeux compatibles

| Premier jeu | Second jeu possible |
|---|---|
| Game Boy ou Game Boy Color | Game Boy ou Game Boy Color |
| Game Boy Advance | Game Boy Advance |

Les deux jeux doivent être différents : une cartouche n'a qu'une sauvegarde, et deux copies d'un même jeu se l'écraseraient. Le câble Game Boy Advance vers Game Boy Color n'existe pas sur console, et RavenEmu ne le propose pas non plus.

### Pendant la partie

- **Affichage** : un seul jeu est affiché, en grand, comme lorsqu'il tourne seul. La barre du haut indique lequel.
- **Changer de jeu** : le bouton **Joueur 2** (ou **Joueur 1**), à deux flèches, en haut à droite, affiche l'autre jeu. Celui qu'on quitte continue de tourner : la connexion entre les deux reste établie.
- **Commandes** : les commandes tactiles et une manette pilotent le jeu affiché.
- **Son** : celui du joueur 1, quel que soit le jeu affiché.
- **Sauvegardes** : chaque jeu enregistre sa sauvegarde interne, comme lorsqu'il tourne seul. Elle est écrite régulièrement, en arrière-plan et à la sortie.
- **Menu du jeu** : il n'est pas proposé à deux, donc ni états instantanés, ni codes de triche, ni RetroAchievements, ni skin Delta. Sauvegardez dans chaque jeu.
- **Quitter** : la flèche de retour ramène à la bibliothèque. Chaque jeu se relance ensuite seul, avec sa sauvegarde.

### Limites

- Faire tourner deux consoles demande deux fois plus de calcul. Sur un téléphone modeste, deux jeux Game Boy Advance peuvent ralentir.
- Le câble Game Boy Advance suit le matériel au cycle près pour les échanges multijoueurs (Pokémon, Mario Kart, Four Swords…). Le mode normal, plus rare, est approché.
- Les combats et échanges en ligne de Pokémon (Union Room, adaptateur sans fil) ne sont pas émulés.

## Entre deux téléphones : pas encore disponible

Le câble link sans fil de la bêta, entre deux téléphones du même réseau Wi-Fi, n'est pas encore dans l'application native. Il reviendra avec le même protocole que la bêta et la version PC, décrit dans [ARCHITECTURE.md](https://github.com/GhostPunishR/RavenEmu/blob/main/ARCHITECTURE.md#câble-link-sans-fil--protocole-à-reprendre). En attendant, deux jeux se relient sur un même téléphone.
