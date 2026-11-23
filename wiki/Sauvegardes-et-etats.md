# Sauvegardes et états

La nouvelle version Android conserve ses sauvegardes dans son espace privé, séparément de l'ancienne application. Chaque jeu est identifié par le **SHA-256 du contenu exact de sa ROM**. Renommer un fichier ne change pas son identité ; utiliser une autre région ou révision peut la changer.

## Deux mécanismes distincts

| Mécanisme | Usage | Compatibilité |
|---|---|---|
| Sauvegarde de cartouche | Fonction de sauvegarde prévue par le jeu, persistée par RavenEmu | Un export `.sav` peut être utilisable ailleurs selon le format accepté par le jeu et l'autre émulateur |
| État instantané | Capture de la machine émulée à un instant donné | Dépend de la ROM, du moteur et de son format d'état ; pas de garantie entre versions ou émulateurs |

Privilégiez la sauvegarde normale du jeu pour conserver une progression durable. Les formats natifs sont ceux de la révision RavenCore épinglée ; les anciens numéros de format documentés pour l'ancienne application ne constituent pas une garantie pour cette application.

## Sauvegardes de cartouche

La mémoire batterie modifiée est enregistrée périodiquement, ainsi qu'à la sortie du lecteur, au changement de ROM et en arrière-plan. Elle est rechargée quand vous relancez la même ROM.

Les fichiers sont écrits atomiquement. Cela ne remplace pas une copie externe : une désinstallation ou un effacement des données supprime le stockage privé.

## Synchronisation dans le cloud (Google Drive ou WebDAV)

RavenEmu synchronise les **sauvegardes de cartouche** avec votre **Google Drive**, ou, comme RetroArch, avec un dossier **WebDAV** : Nextcloud, ownCloud, kDrive, Koofr, pCloud, un NAS… Vous retrouvez ainsi votre partie sur un autre téléphone. Les ROMs, le BIOS et les états instantanés ne sont jamais envoyés.

### Google Drive

Dans **Paramètres → Sauvegardes cloud**, touchez **Se connecter avec Google**, choisissez votre compte et autorisez RavenEmu. RavenEmu crée lui-même le dossier **RavenEmu** à la racine de votre Drive ; ne le créez pas à la main, RavenEmu ne verrait pas un dossier qu'il n'a pas créé. Il n'accède qu'aux fichiers qu'il y a créés, jamais au reste de votre Drive. Il faut les **services Google Play** : sur un téléphone qui ne les a pas, utilisez un dossier WebDAV. Pour retirer l'accès de RavenEmu, passez par votre compte Google, rubrique Sécurité.

### Dossier WebDAV

1. Dans votre cloud, créez un dossier dédié, par exemple `RavenEmu`, et copiez son adresse WebDAV. Avec Nextcloud : `https://votre-serveur/remote.php/dav/files/identifiant/RavenEmu/`.
2. Créez si possible un **mot de passe d'application** : votre cloud permet de le révoquer sans changer votre mot de passe principal.
3. Dans **Paramètres → Sauvegardes cloud**, saisissez l'adresse (`https://` obligatoire), l'identifiant et le mot de passe, puis **Se connecter**. RavenEmu vérifie le dossier et lance une première synchronisation.

**Quand ?** Au lancement de RavenEmu et à la fermeture d'un jeu (réglage **Synchroniser automatiquement**), ou avec **Synchroniser maintenant**. Un jeu en cours n'est jamais modifié.

**Règle.** Pour chaque jeu de la bibliothèque, RavenEmu compare la sauvegarde du téléphone, celle du cloud et celle de la dernière synchronisation :

| Situation | Résultat |
|---|---|
| Seul le téléphone a changé, ou le cloud n'a rien | La sauvegarde du téléphone est envoyée |
| Seul le cloud a changé, ou le téléphone n'a rien | Celle du cloud remplace celle du téléphone, mise de côté d'abord |
| Les deux ont changé (ou première synchronisation avec deux sauvegardes différentes) | **Conflit** : rien n'est remplacé, vous choisissez |

Un conflit apparaît dans **Paramètres → Sauvegardes cloud**, avec les dates et l'appareil qui a envoyé la version du cloud. **Garder ce téléphone** envoie la vôtre ; **Prendre le cloud** la remplace. Aucune version n'est effacée : la sauvegarde remplacée sur le téléphone est gardée dans le stockage privé (cinq versions par jeu), et le dossier garde les **deux versions précédentes** de chaque jeu, dans des fichiers `ravenemu-<jeu>-<version>.srm` décrits par `ravenemu-saves.json`.

Un jeu est reconnu par l'empreinte de sa ROM : la synchronisation ne concerne que deux copies de la **même ROM exacte**. **Déconnexion** oublie l'adresse et le mot de passe sur l'appareil, sans rien supprimer, ni sur le téléphone ni dans le cloud.

Limites actuelles : en WebDAV, HTTPS seulement, avec un certificat reconnu par Android (pas de certificat auto-signé de NAS), et le dossier doit exister avant la connexion ; les versions mises de côté sur le téléphone ne se restaurent pas encore depuis l'application.

**Erreur 429, « le cloud bloque les connexions pour un moment »** : le plus souvent après plusieurs mots de passe refusés (Koofr le fait). Patientez, le délai est indiqué quand le serveur le donne. Générez un **mot de passe d'application** dans votre cloud (Koofr : Préférences → Mot de passe), puis réessayez ; un VPN peut aussi déclencher ce blocage. Après un refus, RavenEmu ne réessaie plus de lui-même : touchez **Synchroniser maintenant** une fois le problème réglé.

## Quatre emplacements d'état

Dans le menu **⋮** du lecteur, chaque jeu dispose de **quatre emplacements**, avec miniature et date lorsqu'elles sont disponibles.

- Enregistrer dans un emplacement déjà occupé demande confirmation.
- Charger un état reprend la partie et renouvelle la sortie audio.
- **Annuler le chargement** remet la partie telle qu'elle était juste avant le dernier chargement, si un état a été chargé par erreur. La partie d'avant n'est gardée qu'en mémoire, jusqu'au chargement suivant ou au changement de jeu. Les mêmes règles que pour un chargement s'appliquent : rien en Hardcore, ni pendant le câble sans fil.
- Supprimer un emplacement demande confirmation et ne supprime pas le fichier batterie de la cartouche.
- Une miniature absente ou illisible ne doit pas empêcher le chargement de l'état.

## Codes de triche

**Codes de triche**, dans le menu **⋮**, garde une liste de codes par jeu. Chaque code s'active, se désactive ou se supprime ; un nouveau code est vérifié par RavenCore, qui affiche la raison d'un refus.

- Game Boy et Game Boy Color : Game Genie (`ABC-DEF-GHI` ou `ABC-DEF`) et GameShark (8 chiffres, comme `010238CD`).
- Game Boy Advance : Codebreaker non chiffré (`XAAAAAAA YYYY`), GameShark v1/v2 et Action Replay v3 (`XXXXXXXX YYYYYYYY`) tels que l'appareil les affiche.
- Un code de plusieurs lignes s'écrit d'un bloc. Les codes actifs s'appliquent dès l'image suivante, ne sont jamais enregistrés dans un état, et ne tournent pas en mode Hardcore.

La liste est gardée dans le stockage privé de l'application, au format de RavenEmu PC (une ligne par code : `1`, nom et code séparés par des tabulations).

## Exporter sa progression

**Un jeu** : dans sa fiche, touchez **Exporter** puis choisissez le type de fichier :

- **.sav** : lu par la plupart des émulateurs ;
- **.srm** : le nom attendu par RetroArch.

Les deux contiennent la même sauvegarde de cartouche ; seule l'extension change. Le fichier reprend le nom complet de la ROM.

**Toute la bibliothèque** : dans le menu **⋮** de la bibliothèque, **Exporter toutes les sauvegardes (.zip)** réunit, pour chaque jeu qui en a, la sauvegarde de cartouche, les emplacements d'état occupés et leurs miniatures. Chaque jeu a son dossier, au nom de sa ROM.

Choisissez ensuite une destination avec le sélecteur Android. Chaque dossier du ZIP comporte un manifeste versionné, l'empreinte de la ROM et les sommes de contrôle des fichiers. **Il ne contient aucune ROM.** Conservez aussi votre propre copie de celles-ci.

## Importer

Dans la fiche du jeu cible, touchez **Importer**, puis sélectionnez :

| Fichier | Contrôle et confirmation |
|---|---|
| ZIP RavenEmu de la nouvelle version | Le manifeste doit correspondre à la même ROM exacte. Les fichiers et empreintes sont vérifiés avant écriture |
| ZIP de toute la bibliothèque | Seul le dossier du jeu cible est lu, avec les mêmes contrôles. Importez-le dans la fiche de chaque jeu à restaurer |
| `.sav` ou `.srm` brut | Aucune identité de ROM n'est incluse ; un avertissement demande de confirmer le jeu cible |

Le résumé précise les fichiers existants qui seront remplacés. Les éléments absents du paquet restent intacts ; si un état est remplacé sans miniature, l'ancienne miniature correspondante est retirée. Les ROM ne sont pas modifiées.

Les fichiers de l'ancienne application et les états d'autres émulateurs ne doivent pas être importés comme des ZIP de cette version. Un `.sav` ou un `.srm` compatible peut être transféré, sans garantie de conversion automatique.

Pour un jeu GBA à horloge (Pokémon Rubis, Saphir et Émeraude, Boktai), le `.sav` se termine par 16 octets qui gardent l'heure réglée par le jeu, au format de mGBA. mGBA le relit tel quel. Un `.sav` sans ces 16 octets, venu d'un autre émulateur, s'importe aussi : l'horloge suit alors l'heure du téléphone.

## Après une mise à jour

Un état peut devenir incompatible après une évolution du moteur. Essayez alors de lancer normalement le jeu et de reprendre depuis sa sauvegarde de cartouche.

Avec le correctif RTC Pokémon/CFRU de RavenCore (`241f56c`), certaines ROMs modifiées sont désormais reconnues comme ayant une horloge. Leur ancienne sauvegarde Flash se charge ; le cœur conserve ses données et utilise désormais un bloc RTC supplémentaire de 16 octets. Un ancien état créé sans horloge peut être incompatible : relancez normalement le jeu depuis sa sauvegarde de cartouche, puis créez un nouvel état. La reconnaissance dépend du pilote de la ROM ; toutes les variantes d'Unbound n'ont pas été validées en jeu. Voir [les limites du correctif](https://github.com/GhostPunishR/RavenCore/blob/241f56c3564ca1389813753d5c13dd931771fe40/cores/gba/tests/rtc_compatibility.md).

Lorsqu'un ancien jeu GB est reclassé comme GBC d'après son en-tête, sa sauvegarde de cartouche est conservée, mais ses états créés avec le moteur GB ne sont plus chargeables sur le moteur GBC.

## Précautions

La capture d'écran de la bêta n'est pas encore dans l'application native. Les états de la bêta ne s'importent pas ; ses sauvegardes de cartouche, si. Avant de désinstaller, vérifiez vos exports et gardez-les hors du stockage privé de RavenEmu. Ne publiez pas vos sauvegardes personnelles dans une issue.

Sources : [transfert de sauvegardes](https://github.com/GhostPunishR/RavenEmu/blob/main/android/src/main/java/com/ravenemu/app/data/saves/SaveTransfer.java) et [gestion des emplacements](https://github.com/GhostPunishR/RavenEmu/blob/main/android/src/main/java/com/ravenemu/app/data/saves/StateSlotRepository.java).
