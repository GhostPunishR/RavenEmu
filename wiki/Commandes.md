# Commandes

Cette page décrit le lecteur Android de la version multiplateforme.

## Boutons disponibles

| Console | Commandes tactiles |
|---|---|
| Game Boy et Game Boy Color | Croix directionnelle, A, B, Start et Select |
| Game Boy Advance | Les mêmes commandes, avec L et R |

La croix prend en charge le glissement et les diagonales. Les boutons sont relâchés quand le lecteur est fermé ou que l'application passe en arrière-plan.

## Réglages actuels

Ouvrez **Paramètres → Interface** :

- **Retour haptique** : vibration à la pression ;
- **Vibration de la cartouche** : le téléphone vibre avec les jeux équipés d'un moteur ;
- **Taille des commandes** : de 80 à 115 % ;
- **Espacement vertical** : de 20 à 80 dp ;
- **Style des boutons** : Classique ou Contraste élevé ;
- **Réinitialiser les commandes** : retour aux réglages par défaut.

Les réglages sont conservés sur Android.

## Capteurs de la cartouche

Certaines cartouches contiennent un capteur. Le téléphone le remplace par le sien pendant la partie :

| Jeux | Capteur de la cartouche | Capteur du téléphone |
|---|---|---|
| WarioWare: Twisted! | Gyroscope | Gyroscope : tournez le téléphone dans le plan de l'écran |
| Yoshi Topsy-Turvy, Koro Koro Puzzle, cartouches Game Boy MBC7 (Kirby Tilt 'n' Tumble) | Inclinaison | Accéléromètre : penchez le téléphone ; les axes suivent l'orientation de l'écran |
| Boktai 1 à 3 | Capteur solaire | Capteur de lumière, ou niveau de soleil manuel |

Dans **Paramètres → Interface → Capteurs de la cartouche** :

- **Capteurs du téléphone** : active ou coupe les trois capteurs ;
- **Soleil automatique (Boktai)** : le capteur de lumière règle le soleil. Comme sur la vraie cartouche, il faut la lumière du jour : l'éclairage d'une pièce ne donne rien ;
- **Soleil (Boktai)** : niveau manuel de 0 à 100 %, utilisé quand le soleil automatique est coupé ou que le téléphone n'a pas de capteur de lumière.

Comme sur la console, l'inclinaison a pour repère la gravité : posé à plat, le téléphone est au repos. Il n'y a pas encore de recentrage sur la position de jeu. Le déplacement libre des boutons et les profils avancés ne sont pas proposés. Le menu **⋮** du lecteur donne accès aux états, à l'avance rapide, à la capture d'écran, au diagnostic audio et à RetroAchievements.

## Manettes et skins

Le lecteur accepte les touches et axes des manettes Android avec un mappage par défaut. L'utilisation d'une manette masque les commandes tactiles ; toucher l'écran les réaffiche. Aucun éditeur complet de mappage n'est annoncé.

L'import, la sélection et le rendu portrait des skins `.deltaskin` sont intégrés. Voir [Skins de contrôleur](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Skins-de-controleur.md).

## Avance rapide

Le jeu tourne jusqu'à 4 fois plus vite que la console. Pour l'activer ou la couper :

- le bouton **»** en haut du lecteur ;
- l'interrupteur **Avance rapide** du menu **⋮** ;
- le clic du stick droit d'une manette (R3), libre sur la console.

Pendant l'avance rapide, **» ×4** s'affiche sur l'image. Seule la dernière image de chaque groupe de quatre est affichée et entendue : le son garde sa hauteur et passe par morceaux.

L'avance rapide est permise en mode Hardcore de RetroAchievements. Elle reste sans effet tant que le câble sans fil est relié, car l'autre téléphone ne suivrait pas, ainsi que pour deux jeux reliés sur un même téléphone. Elle s'arrête en quittant le jeu. Les boutons `fastForward` des skins Delta ne sont pas encore pris en charge.

## Pause

La pause automatique est fixe : le jeu et le son sont suspendus en arrière-plan et pendant l'ouverture du panneau de jeu. Elle n'est pas désactivable dans les paramètres.

Source : [réglages partagés](https://github.com/GhostPunishR/RavenEmu/blob/main/shared/src/commonMain/kotlin/com/ravenemu/shared/settings/SettingsScreen.kt).
