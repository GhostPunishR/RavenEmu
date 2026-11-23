# Politique de confidentialité de RavenEmu

Date d’entrée en vigueur : 23 novembre 2026

## Champ d’application

Cette politique décrit le fonctionnement de l’application Android RavenEmu (`com.ravenemu`), de son APK de développement (`com.ravenemu.dev`), du site officiel et des ressources GitHub du projet. Les versions publiques sont distribuées dans les Releases GitHub du projet.

## Application Android

RavenEmu fonctionne sans compte, sans télémétrie et sans publicité. Il propose une connexion facultative au service tiers RetroAchievements, et une synchronisation facultative des sauvegardes de cartouche avec un dossier WebDAV que l’utilisateur choisit. Sans connexion, aucun échange avec ces services n’est effectué. L’application ne téléverse jamais les ROM, BIOS, états instantanés ni pochettes ; les sauvegardes de cartouche ne quittent l’appareil que vers le dossier que l’utilisateur a lui-même connecté.

Les données suivantes peuvent être créées ou analysées localement sur l’appareil :

- chemins de dossiers autorisés par l’utilisateur ;
- métadonnées et empreintes de jeux ;
- sauvegardes et états instantanés ;
- copies privées des ROM et du BIOS importés par l’utilisateur ;
- pochettes choisies par l’utilisateur ou générées localement ;
- packs de shaders et skins importés par l’utilisateur ;
- codes de triche saisis par l’utilisateur ;
- préférences d’affichage, d’audio et de commandes.

Ces données restent sous le contrôle de l’utilisateur et peuvent être supprimées en retirant les fichiers concernés, en effaçant les données de l’application ou en désinstallant RavenEmu. Elles sont exclues des sauvegardes Android de l’appareil.

## Permissions Android

RavenEmu ne demande que deux permissions, accordées à l’installation :

- **Internet**, pour RetroAchievements et la synchronisation des sauvegardes, seulement quand l’utilisateur les a connectés ;
- **Vibration**, pour les cartouches à vibreur (Game Boy MBC5 Rumble, Drill Dozer, WarioWare: Twisted!).

Android affiche aussi une permission interne propre à RavenEmu, `com.ravenemu.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, ajoutée par la bibliothèque AndroidX : elle empêche les autres applications d’envoyer des messages internes à RavenEmu et ne donne accès à aucune donnée. RavenEmu ne demande ni la localisation, ni les contacts, ni l’appareil photo, ni le micro, ni un accès global au stockage.

## RetroAchievements (facultatif)

Lors de la connexion choisie par l’utilisateur, le nom de compte et le mot de passe sont envoyés à `https://retroachievements.org` via HTTPS. Le mot de passe n’est pas conservé. Le jeton retourné est chiffré avec Android Keystore dans un fichier exclu des sauvegardes Android. La déconnexion efface ce fichier ; depuis le menu en jeu, elle ferme le client de la partie en cours dès la fermeture du dialogue du compte. Les succès déjà transmis ne sont pas supprimés.

Pendant une partie connectée, le SDK officiel transmet l’empreinte du jeu, les identifiants de jeu et de succès, les informations de session et de présence prévues par le jeu, ainsi que le nom/jeton du compte. Le service reçoit aussi les informations techniques d’une connexion HTTPS, dont l’adresse IP et l’identifiant du client (User-Agent) : versions de RavenEmu, d’Android, du moteur RavenCore et du SDK, et architecture du processeur. Les échanges servent à reconnaître la ROM, télécharger les conditions des succès et enregistrer leur obtention. La mémoire et les fichiers du jeu ne sont pas téléversés. La consultation volontaire d’un classement demande ses scores au même service, avec les informations de session du compte. Les noms publics, scores et rangs retournés sont affichés dans l’application ; aucune demande de classement périodique n’est ajoutée. Le texte de présence déjà prévu par le SDK est aussi affiché localement.

Les badges des succès, l’image du jeu et le portrait du compte sont des images publiques, téléchargées en HTTPS depuis `https://media.retroachievements.org`. Pour RetroAchievements, c’est le seul autre hôte contacté, et l’application refuse toute image venant d’ailleurs ; aucune donnée n’y est envoyée au-delà de ce que comporte une requête HTTPS, avec le même identifiant du client, et les images sont conservées dans le cache de l’application, que le système peut vider et qu’effacer les données supprime.

Les informations enregistrées par RetroAchievements relèvent de sa [politique de confidentialité](https://retroachievements.org/privacy.php). RavenEmu ne stocke pas ces informations sur un serveur du projet. Les requêtes Softcore légitimes émises par le SDK sont désormais conservées localement dans un journal chiffré via Android Keystore et exclu des sauvegardes Android. Ce journal contient les paramètres de la requête d'origine, y compris le jeton du compte ; il est limité à 256 demandes et les nouvelles tentatives ne sont effectuées qu'après authentification du même compte, avec le jeton de cette connexion. Les échecs sont retentés pendant la session avec un délai progressif de 5 à 120 secondes. Les entrées sont retirées après confirmation de réussite et toute la file est effacée lors d'une déconnexion explicite ou de la suppression des données de l'application. Un journal qui ne peut plus être déchiffré est mis de côté sur l'appareil, tel quel, toujours exclu des sauvegardes et jamais envoyé ; il est effacé de la même façon. RavenEmu n'héberge pas ces données sur un serveur.

## Synchronisation des sauvegardes (facultatif)

Dans **Paramètres → Sauvegardes cloud**, l’utilisateur peut indiquer l’adresse d’un **dossier WebDAV** (Nextcloud, ownCloud, kDrive, Koofr, pCloud, NAS…), un identifiant et un mot de passe, de préférence un mot de passe d’application. Sans cette configuration, aucun échange n’a lieu. Le projet ne fournit ni n’héberge ce stockage : il appartient à l’utilisateur, ou au service qu’il a choisi, dont les conditions et la politique de confidentialité s’appliquent.

- **Ce qui est envoyé.** Seules les sauvegardes de cartouche (`.srm`) des jeux de la bibliothèque, et une liste `ravenemu-saves.json`. Pour chaque jeu, elle donne l’empreinte SHA-256 qui l’identifie dans RavenEmu, l’empreinte et la taille de sa sauvegarde, la date d’envoi, le nom de l’appareil qui l’a envoyée (fabricant et modèle Android, par exemple « Google Pixel 8 ») et les empreintes des deux versions précédentes. Aucun nom de jeu, aucune ROM, aucun BIOS, aucun état instantané ni aucune jaquette n’est envoyé.
- **Connexion.** Uniquement en HTTPS, vers l’hôte du dossier ; une redirection n’est jamais suivie. Le serveur reçoit les informations d’une connexion HTTPS, dont l’adresse IP, et l’identifiant du client (User-Agent) « RavenEmu/version (Android) ».
- **Quand.** Au lancement de l’application et à la fermeture d’un jeu si **Synchroniser automatiquement** est activé, et quand l’utilisateur touche **Synchroniser maintenant** ou règle un conflit. Après un refus du serveur (mot de passe refusé, accès retiré, connexions bloquées), les synchronisations automatiques s’arrêtent jusqu’à une synchronisation demandée par l’utilisateur ou une nouvelle connexion.
- **Sur l’appareil.** L’adresse, l’identifiant et le mot de passe du dossier sont chiffrés avec Android Keystore dans un fichier exclu des sauvegardes Android : le mot de passe sert à chaque synchronisation et n’est conservé nulle part ailleurs. Un fichier d’état garde les empreintes de la dernière synchronisation et les conflits à régler. Une sauvegarde remplacée par celle du cloud est d’abord copiée dans le stockage privé de l’application, cinq versions par jeu.
- **Fin.** La déconnexion efface l’adresse, le mot de passe et l’état de synchronisation de l’appareil. Elle ne supprime ni les sauvegardes du téléphone ni les fichiers du dossier, que l’utilisateur gère lui-même.

**Google Drive** n’est pas encore proposé. L’application contient la bibliothèque des services Google Play pour cette option à venir ; elle n’est jamais appelée tant que l’option n’est pas proposée et que l’utilisateur n’a pas connecté son compte. Quand l’option sera proposée, cette politique décrira ce qui est échangé avec Google avant sa mise à disposition.

## Fonctions de la bêta retirée

La bêta retirée (`com.ravenemu.preview`) proposait aussi des jaquettes en ligne, téléchargées depuis `thumbnails.libretro.com`, et un câble link sans fil entre téléphones du même réseau local. L’application actuelle n’a aucune de ces deux fonctions : elle ne télécharge aucune jaquette et ne s’annonce pas sur le réseau local. Si l’une d’elles revient, cette politique sera mise à jour avant sa publication.

## Accès aux fichiers

RavenEmu utilise le sélecteur de documents Android afin que l’utilisateur choisisse explicitement les dossiers accessibles. Le projet ne demande pas un accès global au stockage lorsque le sélecteur Android suffit.

L’utilisateur reste responsable des ROM, BIOS et autres fichiers qu’il choisit d’ouvrir. RavenEmu ne fournit aucun contenu protégé.

## Site et services GitHub

Le site officiel est publié avec GitHub Pages. Le dépôt, les issues, les téléchargements de l’application (Releases) et les avis de sécurité sont fournis par GitHub. Ces services peuvent traiter des données techniques, dont l’adresse IP d’un téléchargement, conformément à leurs propres politiques.

RavenEmu n’ajoute aucun outil d’analyse publicitaire ou de suivi au site officiel.

## Conservation et partage

Le projet ne possède pas de base de données d’utilisateurs et ne vend aucune donnée personnelle. Les informations publiées volontairement dans une issue, une pull request ou une discussion deviennent visibles selon les règles de GitHub.

Ne publiez jamais de chemin personnel, ROM, BIOS, sauvegarde, clé, secret ou jeton dans un rapport public.

## Enfants

RavenEmu ne cherche pas à collecter de données relatives aux enfants et ne crée aucun compte propre au projet. La connexion facultative RetroAchievements utilise un compte tiers existant.

## Évolution de cette politique

Cette politique peut être mise à jour si les fonctions de l’application, le mode de distribution ou les services utilisés changent. La date d’entrée en vigueur sera alors actualisée.

## Contact

Pour une question générale, utilisez les issues sans publier de donnée sensible.

Pour un problème de confidentialité nécessitant un échange privé, utilisez [un avis de sécurité privé](https://github.com/GhostPunishR/RavenEmu/security/advisories/new) et précisez qu’il s’agit d’une demande de confidentialité.


