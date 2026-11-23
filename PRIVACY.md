# Politique de confidentialité de RavenEmu

Date d’entrée en vigueur : 23 novembre 2026

## Champ d’application

Cette politique décrit le fonctionnement de l’application Android RavenEmu, du site officiel et des ressources GitHub du projet.

## Application Android

RavenEmu fonctionne sans compte, sans télémétrie et sans publicité. Il propose une connexion facultative au service tiers RetroAchievements, et une synchronisation facultative des sauvegardes de cartouche avec le Google Drive de l’utilisateur ou un dossier WebDAV qu’il choisit. Sans connexion, aucun échange avec ces services n’est effectué. L’application ne téléverse jamais les ROM, BIOS, états instantanés ni pochettes ; les sauvegardes de cartouche ne quittent l’appareil que vers le Google Drive ou le dossier WebDAV que l’utilisateur a lui-même connecté.

Les données suivantes peuvent être créées ou analysées localement sur l’appareil :

- chemins de dossiers autorisés par l’utilisateur ;
- métadonnées et empreintes de jeux ;
- sauvegardes et états instantanés ;
- pochettes choisies ou générées localement ;
- packs de shaders importés par l’utilisateur ;
- préférences d’affichage, d’audio et de commandes ;
- mesures de performance affichées dans l’APK Debug.

Ces données restent sous le contrôle de l’utilisateur et peuvent être supprimées en retirant les fichiers concernés, en effaçant les données de l’application ou en désinstallant RavenEmu.

## RetroAchievements (facultatif)

Lors de la connexion choisie par l’utilisateur, le nom de compte et le mot de passe sont envoyés à `https://retroachievements.org` via HTTPS. Le mot de passe n’est pas conservé. Le jeton retourné est chiffré avec Android Keystore dans un fichier exclu des sauvegardes Android. La déconnexion efface ce fichier ; depuis le menu en jeu, elle ferme le client de la partie en cours dès la fermeture du dialogue du compte. Les succès déjà transmis ne sont pas supprimés.

Pendant une partie connectée, le SDK officiel transmet l’empreinte du jeu, les identifiants de jeu et de succès, les informations de session et de présence prévues par le jeu, ainsi que le nom/jeton du compte. Le service reçoit aussi les informations techniques d’une connexion HTTPS, dont l’adresse IP et l’identifiant du client (User-Agent) : versions de RavenEmu, d’Android, du moteur RavenCore et du SDK, et architecture du processeur. Les échanges servent à reconnaître la ROM, télécharger les conditions des succès et enregistrer leur obtention. La mémoire et les fichiers du jeu ne sont pas téléversés. La consultation volontaire d’un classement demande ses scores au même service, avec les informations de session du compte. Les noms publics, scores et rangs retournés sont affichés dans l’application ; aucune demande de classement périodique n’est ajoutée. Le texte de présence déjà prévu par le SDK est aussi affiché localement.

Les badges des succès, l’image du jeu et le portrait du compte sont des images publiques, téléchargées en HTTPS depuis `https://media.retroachievements.org`. Pour RetroAchievements, c’est le seul autre hôte contacté, et l’application refuse toute image venant d’ailleurs ; aucune donnée n’y est envoyée au-delà de ce que comporte une requête HTTPS, avec le même identifiant du client, et les images sont conservées dans le cache de l’application, que le système peut vider et qu’effacer les données supprime.

Les informations enregistrées par RetroAchievements relèvent de sa [politique de confidentialité](https://retroachievements.org/privacy.php). RavenEmu ne stocke pas ces informations sur un serveur du projet. Les requêtes Softcore légitimes émises par le SDK sont désormais conservées localement dans un journal chiffré via Android Keystore et exclu des sauvegardes Android. Ce journal contient les paramètres de la requête d'origine, y compris le jeton du compte ; il est limité à 256 demandes et les nouvelles tentatives ne sont effectuées qu'après authentification du même compte, avec le jeton de cette connexion. Les échecs sont retentés pendant la session avec un délai progressif de 5 à 120 secondes. Les entrées sont retirées après confirmation de réussite et toute la file est effacée lors d'une déconnexion explicite ou de la suppression des données de l'application. Un journal qui ne peut plus être déchiffré est mis de côté sur l'appareil, tel quel, toujours exclu des sauvegardes et jamais envoyé ; il est effacé de la même façon. RavenEmu n'héberge pas ces données sur un serveur.

## Synchronisation des sauvegardes (facultatif)

Dans **Paramètres → Sauvegardes cloud**, l’utilisateur peut connecter son **Google Drive**, ou indiquer l’adresse d’un **dossier WebDAV** (Nextcloud, ownCloud, kDrive, Koofr, pCloud, NAS…), un identifiant et un mot de passe, de préférence un mot de passe d’application. Sans cette configuration, aucun échange n’a lieu. Le projet ne fournit ni n’héberge ce stockage : il appartient à l’utilisateur, ou au service qu’il a choisi, dont les conditions et la politique de confidentialité s’appliquent ([Règles de confidentialité de Google](https://policies.google.com/privacy) pour Google Drive).

- **Ce qui est envoyé.** Seules les sauvegardes de cartouche (`.srm`) des jeux de la bibliothèque, et une liste `ravenemu-saves.json`. Pour chaque jeu, elle donne l’empreinte SHA-256 qui l’identifie dans RavenEmu, l’empreinte et la taille de sa sauvegarde, la date d’envoi, le nom de l’appareil qui l’a envoyée (fabricant et modèle Android, par exemple « Google Pixel 8 ») et les empreintes des deux versions précédentes. Aucun nom de jeu, aucune ROM, aucun BIOS, aucun état instantané ni aucune jaquette n’est envoyé.
- **Connexion.** Uniquement en HTTPS, vers l’hôte du dossier WebDAV ou vers l’API Google Drive (`www.googleapis.com`) ; une redirection n’est jamais suivie. Le serveur reçoit les informations d’une connexion HTTPS, dont l’adresse IP, et l’identifiant du client (User-Agent) « RavenEmu/version (Android) ».
- **Google Drive.** L’utilisateur choisit son compte et autorise RavenEmu sur les écrans de Google, par les services Google Play. RavenEmu demande seulement l’autorisation `drive.file` : il ne voit que les fichiers qu’il a créés, dans le dossier « RavenEmu » de la racine du Drive, jamais le reste du Drive. Les services Google Play conservent l’autorisation et fournissent à chaque synchronisation un jeton d’accès temporaire, que RavenEmu ne stocke pas. RavenEmu lit l’adresse e-mail du compte auprès de Google Drive pour l’afficher dans les paramètres.
- **Quand.** Au lancement de l’application et à la fermeture d’un jeu si **Synchroniser automatiquement** est activé, et quand l’utilisateur touche **Synchroniser maintenant** ou règle un conflit.
- **Sur l’appareil.** L’adresse, l’identifiant et le mot de passe du dossier WebDAV, ou l’adresse e-mail du compte Google, sont chiffrés avec Android Keystore dans un fichier exclu des sauvegardes Android : le mot de passe sert à chaque synchronisation et n’est conservé nulle part ailleurs. Un fichier d’état garde les empreintes de la dernière synchronisation et les conflits à régler. Une sauvegarde remplacée par celle du cloud est d’abord copiée dans le stockage privé de l’application, cinq versions par jeu.
- **Fin.** La déconnexion efface l’adresse, le mot de passe ou le compte Google, et l’état de synchronisation de l’appareil. Elle ne supprime ni les sauvegardes du téléphone ni les fichiers du dossier WebDAV ou du Google Drive, que l’utilisateur gère lui-même. L’autorisation donnée à RavenEmu sur Google Drive se retire depuis le compte Google (rubrique Sécurité, accès des applications tierces).

## Jaquettes en ligne (facultatif)

**L'application native actuelle ne propose pas cette option** : elle ne télécharge aucune jaquette. Ce qui suit vaut pour la bêta retirée, et pour l'option si elle revient.

Cette option cherche les jaquettes manquantes de la bibliothèque sur `https://thumbnails.libretro.com`, un service tiers. Cette recherche est **désactivée par défaut** et ne démarre que si l’utilisateur l’active dans Réglages → Général → Bibliothèque.

Une fois activée, l’application télécharge l’index public des jaquettes de chaque console présente dans la bibliothèque (Game Boy, Game Boy Color, Game Boy Advance), puis l’image correspondant au nom de fichier de chaque jeu sans jaquette. Ce service reçoit donc les informations d’une connexion HTTPS, dont l’adresse IP de l’appareil, et, à travers les images demandées, le nom des jeux de la bibliothèque. Aucune ROM, empreinte, sauvegarde ni aucun état n’est envoyé. Les jaquettes sont conservées sur l’appareil. Les jeux absents de l’index ne sont pas recherchés de nouveau ; désactiver puis réactiver l’option relance la recherche.

Les images ainsi obtenues sont la propriété de leurs ayants droit ; RavenEmu ne les distribue pas et ne les inclut pas dans l’application.

## Câble link sans fil (Android)

**L'application native actuelle n'a pas encore de câble link sans fil.** Ce qui suit vaut pour la bêta retirée, et pour cette fonction quand elle reviendra.

Le câble link sans fil relie deux téléphones du même réseau local. Il est activé par défaut et se désactive dans Réglages → Général → Câble link. Il ne passe par aucun serveur : les échanges restent sur le réseau local, d’un téléphone à l’autre.

- **Annonce.** Seulement pendant que le jeu en cours se sert de son câble link, l’application s’annonce sur le réseau local (DNS-SD/mDNS) et cherche les autres téléphones. L’annonce indique le modèle du téléphone, la famille de console (Game Boy ou Game Boy Advance), la version du protocole et un identifiant tiré au hasard à chaque lancement de l’application. Elle n’indique ni le jeu, ni le compte, ni aucun fichier. Les appareils du même réseau peuvent la voir.
- **Liaison.** Une fois deux téléphones reliés, chacun envoie à l’autre le modèle du téléphone et le nom de son fichier de jeu, affichés dans le menu du jeu, puis les données du câble link émulé. Aucune ROM, sauvegarde ni aucun état n’est envoyé.
- **Fin.** Rien n’est conservé après la liaison. L’annonce s’arrête quand le jeu ne se sert plus du câble, quand l’application passe en arrière-plan ou quand le réglage est désactivé.

## Accès aux fichiers

RavenEmu utilise le sélecteur de documents Android afin que l’utilisateur choisisse explicitement les dossiers accessibles. Le projet ne demande pas un accès global au stockage lorsque le sélecteur Android suffit.

L’utilisateur reste responsable des ROM, BIOS et autres fichiers qu’il choisit d’ouvrir. RavenEmu ne fournit aucun contenu protégé.

## Site et services GitHub

Le site officiel est publié avec GitHub Pages. Le dépôt, les issues, les téléchargements et les avis de sécurité sont fournis par GitHub. Ces services peuvent traiter des données techniques conformément à leurs propres politiques.

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


