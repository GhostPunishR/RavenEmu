# Skins de contrôleur

RavenEmu intègre un gestionnaire de skins `.deltaskin` sur Android, dans **Paramètres > Interface**. Aucun skin tiers n'est fourni ; importez vos propres fichiers redistribuables ou autorisés.

## Fonctions intégrées

- Import d'une archive via le sélecteur Android, validation du manifeste et des chemins, limites de taille et stockage privé.
- Sélection d'un skin pour chaque console ; GB/GBC partagent la sélection.
- Rendu des représentations portrait et des commandes, avec prise en charge des images et des PDF via l'adaptateur Android.
- Désélection et suppression ; repli sur les commandes natives si un skin ne peut plus être chargé.

Le déplacement libre des boutons et un éditeur complet ne sont pas proposés. Les archives et le rendu doivent être vérifiés sur appareil ; tous les skins conçus pour d'autres applications ne sont pas garantis compatibles. RavenEmu n'est ni affilié à Delta ni approuvé par ses auteurs.

Voir [Commandes](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Commandes.md) et le [code Android des skins](https://github.com/GhostPunishR/RavenEmu/tree/main/androidApp/src/main/kotlin/com/ravenemu/android/deltaskin).
