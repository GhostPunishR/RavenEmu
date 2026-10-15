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
- **Taille des commandes** : de 80 à 115 % ;
- **Espacement vertical** : de 20 à 80 dp ;
- **Style des boutons** : Classique ou Contraste élevé ;
- **Réinitialiser les commandes** : retour aux réglages par défaut.

Les réglages sont conservés sur Android. Le déplacement libre des boutons et les profils avancés ne sont pas proposés. Le menu **⋮** du lecteur donne accès aux états, au diagnostic audio et à RetroAchievements.

## Manettes et skins

Le lecteur accepte les touches et axes des manettes Android avec un mappage par défaut. L'utilisation d'une manette masque les commandes tactiles ; toucher l'écran les réaffiche. Aucun éditeur complet de mappage n'est annoncé.

L'import, la sélection et le rendu portrait des skins `.deltaskin` sont intégrés. Voir [Skins de contrôleur](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Skins-de-controleur.md). Aucune avance rapide n'est exposée dans les réglages actuels.

## Pause

La pause automatique est fixe : le jeu et le son sont suspendus en arrière-plan et pendant l'ouverture du panneau de jeu. Elle n'est pas désactivable dans les paramètres.

Source : [réglages partagés](https://github.com/GhostPunishR/RavenEmu/blob/main/shared/src/commonMain/kotlin/com/ravenemu/shared/SettingsScreen.kt).
