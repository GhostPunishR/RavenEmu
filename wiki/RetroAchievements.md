# RetroAchievements

L'intégration de la version multiplateforme est raccordée sur **Android**, avec le SDK officiel **rcheevos 12.5.0** en mode **Softcore**. La connexion est facultative ; l'application peut être utilisée sans compte.

**La reconnaissance des jeux, l'affichage des badges et l'attribution synchronisée de succès restent à valider avec un compte et une ROM reconnue sur téléphone.** Un build réussi ne prouve pas un déblocage côté serveur.

## Se connecter

1. Ouvrez **Paramètres → RetroAchievements**, ou le tableau de bord depuis le menu du jeu.
2. Utilisez votre compte RetroAchievements existant.
3. Lancez une ROM reconnue par le service.
4. Consultez le profil, les points, le jeu identifié, les badges et les filtres de succès.

Une première connexion propre à cette application est nécessaire : le jeton de l'ancienne application n'est pas partagé. Le mot de passe n'est pas conservé ; le jeton est chiffré via Android Keystore.

## Console et identification

L'intégration transmet au SDK la console du jeu : Game Boy, Game Boy Color ou Game Boy Advance. La ROM est utilisée **localement** pour son identification. Un jeu modifié, une région ou une révision différente peut ne pas être reconnu.

Le SDK observe la mémoire fournie par l'API C de RavenCore. Les restaurations d'état réinitialisent les conditions temporaires des succès.

## Notifications de progression

Le popup affiche un seul succès à la fois. Lorsque plusieurs mesures progressent, leurs notifications passent à tour de rôle, pendant deux secondes chacune. Les mises à jour d'un même succès sont regroupées avec sa valeur la plus récente, sans repousser le tour des autres.

Les notifications s'appuient sur les mesures de la liste complète du SDK : son événement de progression ne signale que le succès le plus proche de son objectif lorsque plusieurs mesures changent dans la même image. Une valeur déjà atteinte dans la session ne relance pas le popup à chaque oscillation du compteur. Les valeurs présentes au chargement servent de référence et les notifications anciennes sont abandonnées.

Seuls les succès qui possèdent une mesure définie par leur auteur peuvent produire ces notifications. Les mesures sont relevées au rythme du tableau de bord, environ toutes les 1,2 seconde ; plusieurs hausses rapprochées peuvent être regroupées. La notification ne signifie pas que le succès est débloqué ou synchronisé.

Références officielles : [intégration de l'indicateur de progression](https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration#progress-indicator) et [fonctionnement de Measured](https://github.com/RetroAchievements/docs/blob/main/docs/developer-docs/flags/measured.md).

## Limites

- Hardcore reste désactivé.
- Aucun parcours de classement n'est annoncé dans cette version.
- Les déblocages et leur synchronisation nécessitent une validation réelle.
- L'hôte iOS n'a pas encore le lecteur et l'intégration complète raccordés.

## Données et réseau

La connexion et la session utilisent HTTPS vers RetroAchievements. Le service reçoit les informations de compte et de session, l'identification du jeu et les données de succès prévues par le SDK. Les images de profil et badges sont téléchargées et conservées en cache.

Aucune ROM ni sauvegarde n'est téléversée. Pour les détails de conservation et de traitement, consultez la [politique de confidentialité de RavenEmu](https://github.com/GhostPunishR/RavenEmu/blob/main/PRIVACY.md) et celle de [RetroAchievements](https://retroachievements.org/privacy.php).

## Signaler un problème

Indiquez le commit RavenEmu, l'appareil, la console, le jeu/région, l'étape qui échoue et le message visible. Ne publiez ni mot de passe, ni jeton, ni ROM. Voir [Dépannage](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Depannage.md).

Source : [contrôleur Android](https://github.com/GhostPunishR/RavenEmu/blob/main/androidApp/src/main/kotlin/com/ravenemu/android/AchievementsController.kt).
