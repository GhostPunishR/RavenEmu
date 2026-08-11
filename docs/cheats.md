# Cheats RavenEmu

## Première version : GameShark GB/GBC, cartouches couleur uniquement

RavenEmu implémente le format de patch RAM GameShark décrit par Pan Docs :

```text
ABCDEFGH

AB    banque de RAM externe
CD    nouvelle valeur sur 8 bits
GHEF  adresse mémoire, comprise entre A000 et DFFF
```

Ainsi, `010238CD` sélectionne la banque de RAM externe `01` et écrit `02` à
l'adresse `CD38`. Les deux octets d'adresse sont donc écrits dans le code dans
l'ordre faible, puis fort.

Référence technique publique :
[Pan Docs — Game Genie/Shark Cheats](https://gbdev.io/pandocs/Shark_Cheats.html).
L'implémentation RavenEmu est originale et ne reprend aucun moteur de cheats
d'un autre émulateur.

### Syntaxe acceptée

- exactement huit chiffres hexadécimaux après normalisation ;
- lettres minuscules ou majuscules ;
- espaces, tabulations et tirets ignorés à l'intérieur d'une ligne ;
- banque externe `00` à `0F` ;
- adresse `A000` à `DFFF`.

Un retour à la ligne sépare deux codes : un cheat peut ainsi regrouper plusieurs
écritures. Chaque ligne est validée séparément avant d'être persistée ou
transmise au cœur.

Les préfixes `90`/`91` parfois publiés pour certains appareils GameShark Color
ne font pas partie du format RAM documenté ci-dessus et ne sont pas interprétés
par cette version. Ils sont refusés au lieu d'être silencieusement transformés.

### Configuration et persistance

Le frontend conserve des définitions génériques (`id`, nom, format, lignes et
état activé) dans le stockage privé Android :

```text
files/cheats/<sha256-de-la-rom>.json
```

`CheatStore` contrôle l'empreinte, la version du document, les limites de taille
et chaque ligne de code à la lecture comme à l'écriture. Un document absent ou
corrompu donne une liste vide ; il n'est jamais interprété comme une préférence
globale.

Après une modification, Android persiste la liste puis poste une commande à
`EmulationSession`. La session remplace la liste active sur son propre thread,
y compris lorsqu'elle est en pause pendant l'affichage des dialogues. Le cœur
natif reste l'unique composant qui écrit dans la mémoire émulée.

### Sémantique mémoire

- `A000-BFFF` écrit directement dans la banque de RAM de cartouche indiquée par
  `AB`, sans modifier les registres actifs du MBC. La banque et la taille réelle
  de la RAM sont contrôlées avant l'écriture.
- `C000-CFFF` écrit dans la banque WRAM fixe.
- `D000-DFFF` écrit dans la banque WRAM actuellement sélectionnée par la
  machine CGB. Le champ `AB`, qui décrit la RAM externe, est sans effet sur
  cette plage.

Le cœur vérifie de nouveau format, banque et adresse : une chaîne venant de JNI
n'est jamais tenue pour valide sur la seule foi de l'interface Android.

### Point d'application déterministe

Les écritures actives sont appliquées par le cœur natif après l'exécution
exacte d'une trame émulée et avant la remise du framebuffer au frontend. Ce
point correspond au comportement de réécriture périodique à VBlank décrit pour
le GameShark, sans timer Android, thread auxiliaire ni écriture mémoire depuis
l'interface.

La liste active appartient au frontend et reste en dehors des save states :

- un reset reconstruit la machine mais conserve la liste active du cœur ;
- un chargement de save state remplace la machine, pas la configuration des
  cheats ;
- pause, frame skipping et cadence n'ajoutent aucun point d'application ; une
  écriture a lieu exactement une fois par trame réellement exécutée.

### Capacité exposée

Le cœur unifié Game Boy annonce ce format uniquement après le chargement d'une
cartouche dont le mode matériel réel est CGB. `ConsoleType.GAME_BOY` reste
l'unique type public pour GB et GBC ; aucun `ConsoleType.GAME_BOY_COLOR` n'est
réintroduit.

Formats non pris en charge dans cette première version :

- Game Genie GB/GBC ;
- variantes GameShark Color `90`/`91` non documentées par Pan Docs ;
- codes conditionnels ou patches ROM GB/GBC ;
- formats d'autres consoles.

## Game Boy Advance : GameShark Advance v1/v2

RavenEmu annonce `GAMESHARK_GBA_V1_V2` (`storageId = 1`) sous le nom visible
**GameShark GBA v1/v2**. Il s'agit du GameShark Advance original, vendu sous le
nom Action Replay v1/v2 en Europe. Action Replay v3 utilise un autre jeu de
commandes et d'autres graines de chiffrement : il n'est pas inclus dans ce
format.

L'identifiant historique `GAMESHARK_GB_GBC.storageId = 0` reste inchangé. La
fonctionnalité GBA précédente n'ayant jamais été fusionnée, l'identifiant `1`
appartient directement au format GameShark GBA v1/v2 dans cet historique.

### Syntaxe acceptée

Une ligne GameShark contient exactement deux mots de 32 bits :

```text
XXXXXXXX YYYYYYYY
```

- huit chiffres hexadécimaux par mot ;
- minuscules et majuscules acceptées ;
- espaces et tabulations ignorés ;
- une paire par ligne ;
- plusieurs lignes d'un même cheat restent un seul programme, afin qu'une
  condition ne déborde jamais sur le cheat suivant.

Sans préfixe, RavenEmu interprète toujours la paire comme un code chiffré à
saisir sur un appareil v1/v2. Par exemple, le code public :

```text
CD93194F 089CE0B4
```

est déchiffré en `03001C88 0000002F`, c'est-à-dire une commande GameShark
d'écriture 8 bits. Il n'est jamais interprété comme une paire adresse/valeur
générique.

La représentation déchiffrée de la spécification reste disponible avec un
préfixe explicite, par exemple `RAW 02000000 0000002A`. Ce préfixe est
obligatoire : une ligne chiffrée et une ligne RAW ont exactement 16 chiffres,
et tenter de les deviner pourrait transformer silencieusement un vrai code
joueur en une autre commande valide. `RAW` désigne ici exclusivement une
commande GameShark v1/v2 typée, jamais une simple paire adresse/valeur.

### Chiffrement v1/v2

Chaque bloc chiffré de 64 bits est déchiffré par l'inverse des 32 tours décrits
pour le GameShark Advance, avec les graines initiales v1/v2 :

```text
09F4FBBD 9681884A 352027E9 F3DEE5A7
```

Tous les calculs sont réalisés sur des entiers non signés 32 bits avec
débordement modulo 2^32. Kotlin valide le bloc et le cœur C++ le déchiffre et le
compile de nouveau sans faire confiance au frontend.

Les codes Action Replay v3/v4 ont eux aussi deux mots de 32 bits. Aucun bit de
version fiable ne permet de distinguer universellement les deux chiffrements :
le joueur doit donc choisir explicitement **GameShark GBA v1/v2** et ne pas y
coller un code AR v3.

### Commandes prises en charge

Les types RAW suivants sont exécutés :

```text
0aaaaaaa 000000xx  écriture 8 bits
1aaaaaaa 0000xxxx  écriture 16 bits
2aaaaaaa xxxxxxxx  écriture 32 bits
60aaaaaa 0000xxxx  patch ROM 16 bits à 08000000 + aaaaaa × 2
60aaaaaa 1000xxxx  même patch, actif immédiatement sur l'appareil physique
Daaaaaaa 0000xxxx  si [adresse] == xxxx, exécuter la ligne suivante
E0zzxxxx 0aaaaaaa  si [adresse] == xxxx, exécuter les zz lignes suivantes
Faaaaaaa 00000x0y  ligne de hook du Master Code
xxxxxxxx 001DC0DE  identifiant de jeu du Master Code
```

Les écritures passent exclusivement par le bus GBA émulé. Les régions RAM,
I/O, palette, VRAM, OAM et sauvegarde adressables par le matériel sont bornées
et leurs effets de bord sont conservés. Les écritures 16 et 32 bits doivent
être alignées. BIOS, zones non mappées et écriture directe dans la ROM sont
refusés ; une modification ROM doit employer le type `6`.

Les comparaisons `D`/`E` sont limitées aux régions de travail, I/O et vidéo
`0x02` à `0x07`. Les pseudo-lectures conditionnelles du BIOS, de la ROM et des
sauvegardes série sont refusées au lieu d'en simuler une sémantique incorrecte.

Un type `6` est compilé en interception de lecture ROM 16 bits. Il agit donc
également sur les lectures d'instructions, au lieu d'être simulé par une
écriture RAM par trame. Plusieurs patches actifs sont déterministes ; en cas de
recouvrement, la dernière définition active gagne.

### Master Codes

Sur l'appareil physique, la ligne `F` détourne une routine du jeu pour appeler
le gestionnaire GameShark plusieurs fois par seconde. RavenEmu possède déjà le
contrôle du cycle et du bus : modifier le code du jeu pour appeler un second
gestionnaire serait à la fois inutile et moins fidèle. La ligne est néanmoins
analysée et ses adresse/type de hook sont contrôlés avant d'être acceptée comme
opération sans effet.

La ligne `xxxxxxxx 001DC0DE` ne sert sur le périphérique qu'à vérifier la
cartouche insérée. RavenEmu attache déjà chaque définition au SHA-256 de la ROM :
la ligne est reconnue, validée comme ligne d'identifiant, puis acceptée comme
opération sans effet. Elle ne peut donc ni sélectionner un autre jeu ni écrire
en mémoire.

`DEADFACE`, qui change les graines des lignes suivantes au moyen des tables du
matériel, n'est pas ignoré : cette version le refuse avec une erreur dédiée.

### Point d'application, reset et save states

Les écritures et conditions RAM sont exécutées une fois après chaque trame,
sur le thread du cœur, même lorsque le rendu vidéo est sauté. Les patches ROM
sont consultés directement lors de chaque lecture concernée. Aucun timer,
`Handler`, thread auxiliaire ou pointeur natif dérivé d'une adresse de cheat
n'est utilisé.

La liste compilée reste extérieure au save state :

- reset réinstalle les patches ROM et conserve le programme actif ;
- charger un save state remplace la machine puis réinstalle les patches actifs ;
- activation, désactivation, modification et suppression passent par
  `EmulationSession.post` et prennent effet sans redémarrage.

Les conditions `D`/`E` pilotent les écritures RAM périodiques. Une condition
qui engloberait un patch ROM ou une ligne de Master Code est refusée : ces
opérations sont installées structurellement lors du remplacement atomique du
programme et ne sont jamais appliquées silencieusement avec une sémantique
différente de celle du format.

### Limitations actuelles

Sont refusés explicitement :

- group write `3000cccc` et ses lignes paramètres ;
- écritures conditionnées au bouton physique GameShark `8A1`/`8A2` ;
- ralentissement matériel `80F` ;
- changement de graines `DEADFACE` ;
- Action Replay v3/v4/MAX ;
- CodeBreaker Advance, GameShark SP et Xploder.

### Références techniques publiques

- [GBATEK — Gameshark/Action Replay V1/V2](https://doc.kodewerx.org/documents/gbatek.html)
  décrit les commandes RAW, les Master Codes, les graines et les 32 tours de
  chiffrement.
- [EnHacklopedia — Hacking GBA](https://doc.kodewerx.org/hacking_gba.html)
  distingue GameShark Advance, Action Replay v3 et CodeBreaker/GameShark SP.
- [OpenEmu — User guide: Cheat codes](https://github.com/OpenEmu/OpenEmu/wiki/User-guide%3A-Cheat-codes)
  publie le vecteur v1/v2 `CD93194F 089CE0B4` et sépare explicitement AR v3.
- [GameHacking.org — GBA](https://wiki.gamehacking.org/Hacking_Game_Boy_Advance)
  documente la sémantique des commandes et des Master Codes GameShark Advance.

Ces documents servent uniquement de spécification et de vecteurs de
conformité. Le parseur, le déchiffrement, la compilation et l'exécution de
RavenEmu sont une implémentation originale.
