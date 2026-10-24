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

## Captures d'écran

**Capture d'écran**, dans le menu **⋮**, enregistre l'image du jeu en PNG, telle que le lecteur la montre (couleurs et corrections comprises). Elle est agrandie quatre fois sans lissage : 960 × 640 pour la GBA, 640 × 576 pour la GB et la GBC. Le nom du fichier reprend celui du jeu, avec la date et l'heure.

- Depuis Android 10, la capture va dans la galerie, dossier **Images/RavenEmu**, sans demander d'autorisation.
- Sur Android 8 et 9, la galerie demanderait une autorisation : la capture reste dans le dossier Images propre à RavenEmu (`Android/data/…/files/Pictures/RavenEmu`), supprimé avec l'application.

La capture est permise en Hardcore.

L'emplacement 1 conserve le chemin historique `.slot0.state` des premières préversions de cette nouvelle application. Cela ne garantit pas la compatibilité des états ancienne application.

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

## iOS et précautions

La première session iOS raccorde la sauvegarde batterie, quatre emplacements d'état sans miniature et l'import/export `.sav` avec Fichiers. Le ZIP n'y est pas encore proposé ; les essais sur appareil restent à effectuer. Avant de désinstaller Android, vérifiez vos exports et gardez-les hors du stockage privé de RavenEmu. Ne publiez pas vos sauvegardes personnelles dans une issue.

Sources : [transfert de sauvegardes](https://github.com/GhostPunishR/RavenEmu/blob/main/androidApp/src/main/kotlin/com/ravenemu/android/saves/GbSaveTransfer.kt) et [gestion des emplacements](https://github.com/GhostPunishR/RavenEmu/blob/main/androidApp/src/main/kotlin/com/ravenemu/android/saves/GbSaveStateStore.kt).
