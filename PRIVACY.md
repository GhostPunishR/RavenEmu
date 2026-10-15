# Politique de confidentialité de RavenEmu

Date d’entrée en vigueur : 13 octobre 2026

## Champ d’application

Cette politique décrit le fonctionnement de l’application Android RavenEmu, du site officiel et des ressources GitHub du projet.

## Application Android

RavenEmu fonctionne sans compte, sans télémétrie et sans publicité. Il propose une connexion facultative au service tiers RetroAchievements. Sans connexion, aucun échange avec ce service n’est effectué. L’application ne téléverse jamais les ROM, sauvegardes, états instantanés ni pochettes.

Les données suivantes peuvent être créées ou analysées localement sur l’appareil :

- chemins de dossiers autorisés par l’utilisateur ;
- métadonnées et empreintes de jeux ;
- sauvegardes et états instantanés ;
- pochettes choisies ou générées localement ;
- préférences d’affichage, d’audio et de commandes ;
- mesures de performance affichées dans l’APK Debug.

Ces données restent sous le contrôle de l’utilisateur et peuvent être supprimées en retirant les fichiers concernés, en effaçant les données de l’application ou en désinstallant RavenEmu.

## RetroAchievements (facultatif)

Lors de la connexion choisie par l’utilisateur, le nom de compte et le mot de passe sont envoyés à `https://retroachievements.org` via HTTPS. Le mot de passe n’est pas conservé. Le jeton retourné est chiffré avec Android Keystore dans un fichier exclu des sauvegardes Android. La déconnexion efface ce fichier ; depuis le menu en jeu, elle ferme le client de la partie en cours dès la fermeture du dialogue du compte. Les succès déjà transmis ne sont pas supprimés.

Pendant une partie connectée, le SDK officiel transmet l’empreinte du jeu, les identifiants de jeu et de succès, les informations de session et de présence prévues par le jeu, ainsi que le nom/jeton du compte. Le service reçoit aussi les informations techniques d’une connexion HTTPS, dont l’adresse IP et la version du client. Les échanges servent à reconnaître la ROM, télécharger les conditions des succès et enregistrer leur obtention. La mémoire et les fichiers du jeu ne sont pas téléversés.

Les badges des succès et le portrait du compte sont des images publiques, téléchargées en HTTPS depuis `https://media.retroachievements.org`. Pour RetroAchievements, c’est le seul autre hôte contacté ; aucune donnée n’y est envoyée au-delà de ce que comporte une requête HTTPS, et les images sont conservées dans le cache de l’application, que le système peut vider et qu’effacer les données supprime.

Les informations enregistrées par RetroAchievements relèvent de sa [politique de confidentialité](https://retroachievements.org/privacy.php). RavenEmu ne stocke pas ces informations sur un serveur du projet. Les requêtes Softcore légitimes émises par le SDK sont désormais conservées localement dans un journal chiffré via Android Keystore et exclu des sauvegardes Android. Ce journal contient les paramètres de la requête d'origine, y compris le jeton du compte ; il est limité à 256 demandes et les nouvelles tentatives ne sont effectuées qu'après authentification du même compte, avec le jeton de cette connexion. Les échecs sont retentés pendant la session avec un délai progressif de 5 à 120 secondes. Les entrées sont retirées après confirmation de réussite et toute la file est effacée lors d'une déconnexion explicite ou de la suppression des données de l'application. RavenEmu n'héberge pas ces données sur un serveur.

## Jaquettes en ligne (facultatif, version multiplateforme)

La nouvelle version multiplateforme de RavenEmu peut chercher les jaquettes manquantes de la bibliothèque sur `https://thumbnails.libretro.com`, un service tiers. Cette recherche est **désactivée par défaut** et ne démarre que si l’utilisateur l’active dans Réglages → Général → Bibliothèque.

Une fois activée, l’application télécharge l’index public des jaquettes de chaque console présente dans la bibliothèque (Game Boy, Game Boy Color, Game Boy Advance), puis l’image correspondant au nom de fichier de chaque jeu sans jaquette. Ce service reçoit donc les informations d’une connexion HTTPS, dont l’adresse IP de l’appareil, et, à travers les images demandées, le nom des jeux de la bibliothèque. Aucune ROM, empreinte, sauvegarde ni aucun état n’est envoyé. Les jaquettes sont conservées sur l’appareil. Les jeux absents de l’index ne sont pas recherchés de nouveau ; désactiver puis réactiver l’option relance la recherche.

Les images ainsi obtenues sont la propriété de leurs ayants droit ; RavenEmu ne les distribue pas et ne les inclut pas dans l’application.

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


