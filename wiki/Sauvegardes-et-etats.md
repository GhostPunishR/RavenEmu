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
- contrôleurs de cartouche;
- mode matériel DMG/CGB, `KEY1`, `OPRI`, IR et état de la boot ROM;
- état intermédiaire du fetcher, des FIFO BG/OBJ, d'un fetch OBJ en cours, des
  DMA, des boutons et de la phase libre du port série.

Les états utilisent le format RavenEmu `RVNS`. Ils ne sont pas annoncés comme compatibles avec d'autres émulateurs.

Le format GB/GBC courant est la **version 8**. Il enregistre les frontières de
M-cycle, le séquenceur APU dérivé de DIV et le pipeline PPU complet, y compris
les octets OBJ déjà échantillonnés mais pas encore fusionnés. Les versions
GB/GBC antérieures, notamment la version 7, sont refusées avec une erreur
explicite au lieu de charger silencieusement un état incomplet. Les portes
VRAM/OAM/CRAM sont recalculées depuis les phases PPU déjà enregistrées ; aucun
champ redondant ni changement de version n'est nécessaire. Les modes, dots ou
retards de publication incohérents sont rejetés au chargement. Le format du cœur
GBA évolue indépendamment.

Les objets de transport externes (`LinkEndpoint` et `InfraredEndpoint`) ne font
pas partie de la machine émulée et ne sont pas sérialisés. Après restauration,
le cœur se reconnecte à l'endpoint que l'hôte lui a déjà fourni ; l'autre
instance ou le backend reste responsable de sa propre restauration.

## Compatibilité des sauvegardes et des états

Les deux mécanismes n'offrent pas les mêmes garanties, et c'est volontaire.

**Sauvegarde `.sav` : compatibilité durable.** Le fichier reproduit le contenu brut de la mémoire de la cartouche, tel que le jeu l'écrit. Ce format ne dépend pas de RavenEmu : il ne change pas d'une version à l'autre, et reste lisible par d'autres outils qui lisent le même format. C'est le support à privilégier pour conserver une progression.

**État instantané `RVNS` : compatibilité limitée à un format donné.** Un état contient l'intégralité de la machine émulée, y compris des détails internes que les corrections de précision font évoluer. Le format porte un numéro de version, et un état d'une version antérieure est **refusé** plutôt que réinterprété au risque de produire un comportement faux.

Ce que RavenEmu garantit :

- un état n'est jamais chargé sur une autre ROM : l'empreinte SHA-256 de la ROM est inscrite dans le fichier et vérifiée ;
- un état n'est jamais chargé sur une autre console : l'identifiant de console inscrit est figé et n'est jamais réattribué, même si la liste des consoles évolue ;
- un état refusé (tronqué, corrompu, ou d'une autre version) laisse la partie en cours **strictement intacte** et jouable : la restauration se fait dans une machine neuve, qui ne remplace l'active qu'en cas de succès complet ;
- une sauvegarde `.sav` n'est effacée qu'après confirmation de l'écriture du nouveau fichier.

Ce que RavenEmu ne garantit pas :

- qu'un état créé par une version antérieure reste chargeable après une évolution du moteur ;
- qu'un état soit lisible par un autre émulateur ; le format `RVNS` est propre à RavenEmu et n'a pas vocation à être partagé.

## Recommandations

- utilisez les sauvegardes normales du jeu comme référence;
- gardez plusieurs copies de vos fichiers importants;
- ne déplacez pas un état entre deux jeux différents;
- évitez de charger un état créé avec une version très ancienne;
- vérifiez votre sauvegarde avant de désinstaller l'application;
- ne partagez pas publiquement une sauvegarde contenant des données personnelles.

## Après une mise à jour

Si un état ne se charge plus, démarrez le jeu normalement et utilisez sa sauvegarde `.sav`. Signalez le problème uniquement si la sauvegarde de cartouche est également touchée.
