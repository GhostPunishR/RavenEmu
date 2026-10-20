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

Le popup affiche un seul succès à la fois. Lorsque plusieurs mesures progressent, leurs notifications passent à tour de rôle, pendant deux secondes chacune par défaut. Les mises à jour d'un même succès sont regroupées avec sa valeur la plus récente, sans repousser le tour des autres.

Les notifications s'appuient sur les mesures de la liste complète du SDK : son événement de progression ne signale que le succès le plus proche de son objectif lorsque plusieurs mesures changent dans la même image. Une valeur déjà atteinte dans la session ne relance pas le popup à chaque oscillation du compteur. Les valeurs présentes au chargement servent de référence et les notifications anciennes sont abandonnées.

Seuls les succès qui possèdent une mesure définie par leur auteur peuvent produire ces notifications. Les mesures sont relevées au rythme du tableau de bord, environ toutes les 1,2 seconde ; plusieurs hausses rapprochées peuvent être regroupées. La notification ne signifie pas que le succès est débloqué ou synchronisé.

Références officielles : [intégration de l'indicateur de progression](https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration#progress-indicator) et [fonctionnement de Measured](https://github.com/RetroAchievements/docs/blob/main/docs/developer-docs/flags/measured.md).

## Tableau de bord et synchronisation

La liste permet de rechercher dans le titre et la description, de filtrer les succès à obtenir ou obtenus et de changer le tri : ordre du jeu, progression mesurée, date de déblocage. Les dates utilisent le fuseau et le format de l'appareil. Le texte d'activité du jeu (Rich Presence), s'il existe, figure sous son titre.

Chaque succès distingue **débloqué localement**, **en attente d'envoi** et **confirmé par RetroAchievements**. Une réponse HTTP seule ne suffit pas : le corps de la réponse doit confirmer la réussite. Les succès déjà obtenus dans la liste chargée du serveur sont confirmés. Le compteur d'attente concerne le compte connecté sur cet appareil, tous jeux confondus ; il ne modifie jamais les conditions ou les attributions du SDK.

Les demandes légitimes du SDK restent dans le journal chiffré jusqu'à confirmation. À la connexion suivante au même compte, elles sont retentées. Une erreur de stockage affiche un avertissement : garder le jeu ouvert jusqu'à la synchronisation. Se déconnecter explicitement efface ce journal local.

## Réglages des notifications

Dans **Paramètres → RetroAchievements**, les popups de déblocage et de fin de jeu et ceux de progression ont des interrupteurs distincts. Leur position peut être en haut ou en bas. La durée des déblocages va de 2 à 10 secondes, celle des progressions de 1 à 5 secondes ; les nouvelles durées s'appliquent aux prochains tours. Le son des succès est facultatif, généré localement et désactivé par défaut. Les erreurs et avis de connexion ne sont pas masqués par l'interrupteur des succès.

Une fin de jeu affiche son image, le compte, le nombre de succès et les points. **Jeu terminé** correspond au Softcore ; **Jeu maîtrisé** est réservé au Hardcore. Ce bilan suit l'événement du SDK et ne garantit pas à lui seul la synchronisation de tous les succès : consulter aussi les états d'envoi.

## Classements

Depuis le tableau de bord en jeu ou dans les paramètres, choisir un classement puis **Meilleurs scores** ou **Autour de moi**. Chaque demande charge au plus 20 entrées ; le lien vers le site ouvre le classement complet. Les scores ne sont demandés qu'après une action, avec un indicateur de chargement, une erreur et un bouton de réessai si nécessaire.

La consultation fonctionne en Softcore. Les tentatives et soumissions officielles nécessitent le Hardcore. Après soumission, le dernier résultat présente le score, le record personnel et le rang renvoyés par le SDK. Aucun score n'est inventé localement.

## Préparation du mode Hardcore

Le verrou `HardcoreAvailability.APPROVED` reste à `false`. Le réglage ne peut pas activer ce mode tant que RavenEmu n'a pas été validé par RetroAchievements. L'identifiant HTTP est `RavenEmu/0.1.0-beta (Android) rcheevos/12.5.0` ; le guide officiel demande une version numérique et une validation par [RAdmin](https://retroachievements.org/messages/create?to=RAdmin).

Le code prépare le redémarrage demandé par le SDK à l'activation, refuse le chargement d'un état sur le thread du SDK pour éviter une course avec un changement de mode, autorise toujours l'enregistrement des états et respecte `rc_client_can_pause` pour le menu. Si le redémarrage échoue, le Hardcore est désactivé. Les fonctions de triche, rewind et ralenti ne sont pas proposées dans cette application.

Avant de lever ce verrou, il reste à tester sur appareil le cycle complet d'activation/redémarrage, les pauses, le refus de restauration, un déblocage reconnu en Hardcore sur le site et une soumission de classement, puis obtenir la validation externe. Les tests synthétiques et de politique ne remplacent pas ces essais. Le lecteur à deux n'évalue pas les succès ; iOS reste à raccorder.

Références : [User-Agent et validation](https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration#user-agent-header), [Hardcore et restrictions](https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration#enabling-hardcore), [notification de jeu terminé](https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration#mastery-notification).

## Limites

- Hardcore reste désactivé.
- Les classements se consultent en Softcore ; les soumissions restent indisponibles tant que le Hardcore est verrouillé.
- Les déblocages et leur synchronisation nécessitent une validation réelle.
- La première session iOS raccorde le lecteur ; son service RetroAchievements reste à développer.

## Données et réseau

La connexion et la session utilisent HTTPS vers RetroAchievements. Le service reçoit les informations de compte et de session, l'identification du jeu et les données de succès prévues par le SDK. Les images de profil et badges sont téléchargées et conservées en cache.

Aucune ROM ni sauvegarde n'est téléversée. Pour les détails de conservation et de traitement, consultez la [politique de confidentialité de RavenEmu](https://github.com/GhostPunishR/RavenEmu/blob/main/PRIVACY.md) et celle de [RetroAchievements](https://retroachievements.org/privacy.php).

## Signaler un problème

Indiquez le commit RavenEmu, l'appareil, la console, le jeu/région, l'étape qui échoue et le message visible. Ne publiez ni mot de passe, ni jeton, ni ROM. Voir [Dépannage](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Depannage.md).

Source : [contrôleur Android](https://github.com/GhostPunishR/RavenEmu/blob/main/androidApp/src/main/kotlin/com/ravenemu/android/AchievementsController.kt).
