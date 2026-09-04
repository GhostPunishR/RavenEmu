# Sauvegardes et états

RavenEmu gère deux mécanismes différents.

## Sauvegarde de cartouche

La sauvegarde de cartouche correspond au système utilisé par le jeu. Elle est
enregistrée dans un fichier `.sav`. Une mémoire simple reste brute ; les
contrôleurs qui possèdent plusieurs états persistants ajoutent un conteneur ou
un pied de page propre au matériel.

Exemples:

- mémoire avec pile sur Game Boy;
- MBC3 avec horloge;
- MBC6 avec SRAM, flash, région cachée et protection du secteur 0;
- MBC7 avec EEPROM série de 256 octets;
- HuC1 avec SRAM brute et batterie;
- HuC3 avec SRAM, mémoire interne à nibbles et RTC minute/jour;
- SRAM, Flash et EEPROM sur Game Boy Advance.

Le MBC6 utilise le conteneur versionné `RVM6`, car un seul fichier doit réunir
32 Kio de SRAM, 1 Mio de flash, sa région cachée de 256 octets et le verrou non
volatile du secteur 0. Un conteneur dont la taille, la signature ou la version
est invalide est refusé en entier : RavenEmu n'en importe jamais un préfixe.

Le HuC3 conserve les 32 Kio de SRAM en tête du fichier, puis ajoute le pied de
page versionné `RVH3`. Celui-ci empaquette les 256 nibbles internes, le reliquat
de secondes, l'index alimenté par la pile et l'époque de dernière
synchronisation. Une ancienne sauvegarde composée exactement des 32 Kio de
SRAM est acceptée et enrichie au prochain instantané ; une taille étendue, une
signature, une version ou un compteur invalide est refusé sans importer même
le préfixe SRAM.

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

Le format GB/GBC courant est la **version 10**. Il enregistre les frontières de
M-cycle, le séquenceur APU dérivé de DIV et le pipeline PPU complet, y compris
les octets OBJ déjà échantillonnés mais pas encore fusionnés et les dots de
fetch OBJ qui recouvrent la sortie du FIFO BG. La phase des DMA
et la porte vidéo figée pendant une transition `KEY1` sont également conservées,
ainsi que la sélection, les masques et les verrous d'une cartouche MMM01. Pour
MBC6, il conserve la SRAM et la flash complètes, la région cachée, la protection,
les registres, l'automate de commande et le tampon de programmation. Sa taille
peut donc dépasser 1 Mio et reste bornée à 2 Mio.
L'ajout des layouts internes MMM01 et MBC6 n'avait pas imposé de nouvelle
version globale : les versions précédentes refusaient ces contrôleurs et ne
pouvaient donc produire aucun état ambigu à recharger. Le passage à la version
10 vient du nouvel état de recouvrement temporel du fetch OBJ.
Pour MBC7, l'état conserve également les axes bruts, leur latch, les broches et
l'automate EEPROM, y compris une commande ou un délai d'écriture en cours. Comme
ce contrôleur était auparavant refusé, son layout interne n'impose pas de
changement de version supplémentaire.
Pour HuC1, les banques, la sélection RAM/IR, l'émetteur, le niveau lumineux
reçu et la SRAM sont enregistrés. Le transport externe reste exclu comme pour
`RP`; le type `$FF` était auparavant refusé, donc son ajout reste non ambigu.
Pour HuC3, l'état inclut en plus la boîte aux lettres, la réponse, l'index, les
256 nibbles, l'horloge, le sémaphore et la commande éventuellement suspendue au
milieu de sa phase occupée. Le type `$FE` était lui aussi refusé auparavant ;
son layout interne n'impose donc pas de changement de version supplémentaire.
Les versions GB/GBC antérieures, notamment la version 9, sont refusées avec une
erreur explicite au lieu de charger silencieusement un état incomplet. Les
portes VRAM/OAM/CRAM ordinaires et le mot OAM présenté par le DMA sont
recalculés depuis les phases déjà enregistrées. Les modes, dots, retards de
publication ou couples KEY1/PPU incohérents sont rejetés au chargement. Le
format du cœur GBA évolue indépendamment.

Les objets de transport externes (`LinkEndpoint` et `InfraredEndpoint`) ne font
pas partie de la machine émulée et ne sont pas sérialisés. Après restauration,
le cœur se reconnecte à l'endpoint que l'hôte lui a déjà fourni ; l'autre
instance ou le backend reste responsable de sa propre restauration.

## Compatibilité des sauvegardes et des états

Les deux mécanismes n'offrent pas les mêmes garanties, et c'est volontaire.

**Sauvegarde `.sav` : compatibilité durable au sein du format documenté.** Pour
une SRAM simple, le fichier reproduit directement les octets de la cartouche.
Les pieds de page RTC et le conteneur `RVM6` conservent les états persistants qui
n'ont pas de représentation SRAM brute. RavenEmu versionne et valide ces
extensions ; un outil externe doit connaître le format concerné pour les lire.
C'est le support à privilégier pour conserver une progression.

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
