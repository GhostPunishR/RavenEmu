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
- Supprimer un emplacement demande confirmation et ne supprime pas le fichier batterie de la cartouche.
- Une miniature absente ou illisible ne doit pas empêcher le chargement de l'état.

L'emplacement 1 conserve le chemin historique `.slot0.state` des premières préversions de cette nouvelle application. Cela ne garantit pas la compatibilité des états ancienne application.

## Exporter sa progression

Ouvrez la **fiche du jeu dans la bibliothèque** :

1. **Exporter toutes les sauvegardes (.zip)** : sauvegarde de cartouche, emplacements d'état occupés et miniatures disponibles.
2. **Exporter .sav** : fichier batterie seul, lorsqu'une sauvegarde existe.
3. Choisissez une destination avec le sélecteur Android.

Le ZIP comporte un manifeste versionné, l'empreinte de la ROM et les sommes de contrôle des fichiers. **Il ne contient pas la ROM.** Conservez aussi votre propre copie de celle-ci.

## Importer

Dans la fiche du jeu cible, touchez **Importer**, puis sélectionnez :

| Fichier | Contrôle et confirmation |
|---|---|
| ZIP RavenEmu de la nouvelle version | Le manifeste doit correspondre à la même ROM exacte. Les fichiers et empreintes sont vérifiés avant écriture |
| `.sav` brut | Aucune identité de ROM n'est incluse ; un avertissement demande de confirmer le jeu cible |

Le résumé précise les fichiers existants qui seront remplacés. Les éléments absents du paquet restent intacts ; si un état est remplacé sans miniature, l'ancienne miniature correspondante est retirée. Les ROM ne sont pas modifiées.

Les fichiers de l'ancienne application et les états d'autres émulateurs ne doivent pas être importés comme des ZIP de cette version. Un `.sav` compatible peut être transféré, sans garantie de conversion automatique.

Pour un jeu GBA à horloge (Pokémon Rubis, Saphir et Émeraude, Boktai), le `.sav` se termine par 16 octets qui gardent l'heure réglée par le jeu, au format de mGBA. mGBA le relit tel quel. Un `.sav` sans ces 16 octets, venu d'un autre émulateur, s'importe aussi : l'horloge suit alors l'heure du téléphone.

## Après une mise à jour

Un état peut devenir incompatible après une évolution du moteur. Essayez alors de lancer normalement le jeu et de reprendre depuis sa sauvegarde de cartouche.

Avec le correctif RTC Pokémon/CFRU de RavenCore (`241f56c`), certaines ROMs modifiées sont désormais reconnues comme ayant une horloge. Leur ancienne sauvegarde Flash se charge ; le cœur conserve ses données et utilise désormais un bloc RTC supplémentaire de 16 octets. Un ancien état créé sans horloge peut être incompatible : relancez normalement le jeu depuis sa sauvegarde de cartouche, puis créez un nouvel état. La reconnaissance dépend du pilote de la ROM ; toutes les variantes d'Unbound n'ont pas été validées en jeu. Voir [les limites du correctif](https://github.com/GhostPunishR/RavenCore/blob/241f56c3564ca1389813753d5c13dd931771fe40/cores/gba/tests/rtc_compatibility.md).

Lorsqu'un ancien jeu GB est reclassé comme GBC d'après son en-tête, sa sauvegarde de cartouche est conservée, mais ses états créés avec le moteur GB ne sont plus chargeables sur le moteur GBC.

## iOS et précautions

Le stockage et le transfert iOS ne sont pas encore raccordés. Avant de désinstaller Android, vérifiez vos exports et gardez-les hors du stockage privé de RavenEmu. Ne publiez pas vos sauvegardes personnelles dans une issue.

Sources : [transfert de sauvegardes](https://github.com/GhostPunishR/RavenEmu/blob/main/androidApp/src/main/kotlin/com/ravenemu/android/GbSaveTransfer.kt) et [gestion des emplacements](https://github.com/GhostPunishR/RavenEmu/blob/main/androidApp/src/main/kotlin/com/ravenemu/android/GbSaveStateStore.kt).
