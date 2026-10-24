# Légalité et confidentialité

Cette page résume les informations du projet. Les documents officiels sont la [licence](https://github.com/GhostPunishR/RavenEmu/blob/main/LICENSE), les [conditions d'utilisation](https://github.com/GhostPunishR/RavenEmu/blob/main/TERMS.md) et la [politique de confidentialité](https://github.com/GhostPunishR/RavenEmu/blob/main/PRIVACY.md).

## Licence et composants

RavenEmu est distribué sous **GNU AGPL-3.0**. Consultez le texte de la licence et les avis du dépôt pour les conditions de réutilisation et de redistribution.

Les moteurs C++20 sont maintenus dans RavenCore et écrits pour le projet à partir de documentation technique publique. Le SDK officiel rcheevos conserve sa licence MIT ; les autres composants conservent leurs avis de licence respectifs. Les couleurs des jeux Game Boy sur GBC viennent des tables de [The Cutting Room Floor](https://tcrf.net/Notes:Game_Boy_Color_Bootstrap_ROM), sous licence [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/), compilées dans RavenCore.

Game Boy, Game Boy Color et Game Boy Advance sont des marques de Nintendo. RavenEmu n'est ni affilié à Nintendo, ni approuvé par Nintendo.

## Contenu non fourni

Le projet ne distribue aucune ROM, aucun BIOS propriétaire ni aucun jeu protégé. Utilisez des copies que vous êtes autorisé à employer ou des homebrews distribués légalement.

Le lecteur GBA actuel ne demande pas de fichier BIOS externe à l'utilisateur. Cela ne signifie pas qu'un BIOS propriétaire est fourni.

## Données locales

La bibliothèque, les ROM importées, les sauvegardes, les états, les réglages et les jaquettes sont conservés sur Android dans l'espace privé de l'application. RavenEmu ne téléverse pas les ROM ou les sauvegardes et n'ajoute ni publicité ni télémétrie.

Le sélecteur de documents permet de choisir explicitement fichiers et dossiers. Il n'y a pas de demande d'accès global au stockage pour ces opérations. Désinstaller ou effacer les données supprime le stockage privé ; les exports choisis par l'utilisateur doivent être conservés séparément.

## Réseau facultatif

| Fonction | Déclenchement | Données concernées |
|---|---|---|
| RetroAchievements | Connexion volontaire au compte tiers | Informations de compte/session, identification du jeu et succès prévus par le SDK ; le service reçoit aussi l'adresse IP |
| Images RetroAchievements | Utilisation du profil et des badges | Requêtes HTTPS d'images et informations techniques de connexion ; images en cache |
| Jaquettes Libretro | Option « Jaquettes en ligne », désactivée par défaut | Adresse IP et noms des images demandées, pouvant révéler des jeux de la bibliothèque ; aucune ROM ni sauvegarde envoyée |
| Câble link sans fil | Option activée par défaut ; seulement quand le jeu se sert de son câble | Sur le réseau local uniquement : modèle du téléphone, console et identifiant aléatoire annoncés ; au téléphone relié, nom du jeu et données du câble. Aucun serveur |

Le mot de passe RetroAchievements n'est pas conservé ; le jeton est chiffré sur l'appareil. Pour les hôtes contactés, la conservation, les nouvelles tentatives et la déconnexion, référez-vous à la politique complète.

RavenEmu peut être utilisé sans compte. Les données traitées par les services tiers relèvent aussi de leurs propres politiques.

## Site, GitHub et rapports

Le site est hébergé par GitHub Pages. Dépôt, issues, artefacts et wiki utilisent les services GitHub, qui peuvent traiter les données techniques de connexion. Le site n'ajoute aucun outil de suivi publicitaire.

Avant de publier une issue ou une capture, retirez chemins personnels, identifiants, clés et jetons. Ne joignez pas de ROM, BIOS ou sauvegarde personnelle. Pour un problème privé de sécurité ou de confidentialité, utilisez un [avis privé](https://github.com/GhostPunishR/RavenEmu/security/advisories/new).
