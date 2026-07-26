# Sauvegardes et états

RavenEmu gère deux mécanismes différents.

## Sauvegarde de cartouche

La sauvegarde de cartouche correspond au système utilisé par le jeu. Elle est enregistrée dans un fichier `.sav` brut lorsque le matériel de la cartouche le permet.

Exemples:

- mémoire avec pile sur Game Boy;
- MBC3 avec horloge;
- SRAM, Flash et EEPROM sur Game Boy Advance.

Ces données sont sauvegardées automatiquement et écrites de manière atomique pour limiter les risques de corruption.

## État instantané

Un état instantané capture l'ensemble de la machine émulée à un moment précis:

- processeur;
- mémoire;
- vidéo;
- audio;
- timers;
- interruptions;
- contrôleurs de cartouche.

Les états utilisent le format RavenEmu `RVNS`. Ils ne sont pas annoncés comme compatibles avec d'autres émulateurs.

## Compatibilité des états

Le format est versionné. Une évolution importante du moteur peut rendre un ancien état incompatible. Une sauvegarde `.sav` reste distincte et doit être privilégiée pour conserver une progression à long terme.

## Recommandations

- utilisez les sauvegardes normales du jeu comme référence;
- gardez plusieurs copies de vos fichiers importants;
- ne déplacez pas un état entre deux jeux différents;
- évitez de charger un état créé avec une version très ancienne;
- vérifiez votre sauvegarde avant de désinstaller l'application;
- ne partagez pas publiquement une sauvegarde contenant des données personnelles.

## Après une mise à jour

Si un état ne se charge plus, démarrez le jeu normalement et utilisez sa sauvegarde `.sav`. Signalez le problème uniquement si la sauvegarde de cartouche est également touchée.
