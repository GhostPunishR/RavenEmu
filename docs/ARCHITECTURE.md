# RavenEmu — Décisions d'architecture

Ce document consigne les décisions structurantes du projet. Chaque décision
indique son contexte, le choix retenu et ses conséquences.

## AD-01 — Découpage en modules Gradle

**Choix.** Neuf modules :

| Module | Type | Rôle |
|---|---|---|
| `app` | Application Android | Navigation, écrans, cycle de vie |
| `emulation-api` | Kotlin JVM pur | Interfaces communes app ↔ moteurs |
| `gameboy-core` | Kotlin JVM pur | Moteur Game Boy complet |
| `rom-library` | Kotlin JVM pur | Parsing d'en-têtes, empreintes, identification, modèle d'index |
| `storage` | Bibliothèque Android | SAF, fichiers `.sav`, états, index persistant |
| `renderer` | Bibliothèque Android | Affichage du framebuffer (SurfaceView) |
| `input` | Bibliothèque Android | Tactile, manettes, dispositions |
| `settings` | Bibliothèque Android | Préférences globales et par console |

**Conséquences.** `emulation-api`, `gameboy-core` et `rom-library` ne
référencent aucune classe Android et se testent sur la JVM. L'ajout d'une
console future = nouveau module implémentant `emulation-api`, sans toucher
au moteur Game Boy.

## AD-02 — Moteur en Kotlin pur, couche native différée

Le premier moteur est écrit en Kotlin JVM afin de maximiser testabilité et
lisibilité. Une couche native (NDK) ne sera envisagée que si des mesures de
performance la justifient ; l'interface `EmulatorCore` isole ce choix.

## AD-03 — Contrat moteur : pas de callback, exécution par trame

`EmulatorCore` expose `runFrame()` : le moteur exécute exactement une trame
(70 224 cycles Game Boy) et remplit un framebuffer ARGB fourni par
l'appelant. L'app pilote la cadence (thread d'émulation + horloge
d'affichage). Avantages : déterminisme, aucune allocation par trame, tests
JVM triviaux, aucune dépendance de threading dans le cœur.

## AD-04 — Inclusion conditionnelle des modules Android

`settings.gradle.kts` n'inclut les modules Android que si un SDK Android est
détecté (`ANDROID_HOME`, `ANDROID_SDK_ROOT` ou `local.properties`). Les
modules JVM purs se construisent ainsi sur toute machine (`./gradlew test`),
la CI et les postes équipés du SDK construisent l'ensemble. Aucun module
n'applique de plugin Android au niveau racine.

## AD-05 — Précision d'émulation : pas au cycle machine, PPU par ligne

- CPU : précision au cycle (les instructions consomment leur nombre exact de
  T-cycles ; les périphériques avancent d'autant).
- PPU : rendu scanline par scanline au début du mode 3, timings de modes
  respectés (OAM 80, transfert 172 fixe, HBlank 204). Le mid-scanline
  n'est pas émulé en v1 (limite documentée, suffisante pour la grande
  majorité des jeux DMG).
- Timers : DIV/TIMA branchés sur le compteur interne 16 bits, sélection de
  bit conforme, débordement TIMA avec rechargement TMA différé de 4 cycles.

## AD-06 — Sauvegardes

- `.sav` : dump brut de la RAM cartouche (+ 48 octets RTC pour MBC3 au
  format horloge répandu), même nom de base que la ROM, écriture atomique
  (fichier temporaire puis remplacement), dossier configurable.
- États instantanés : format binaire propriétaire versionné (magic
  `RVNS`, version, console, empreinte ROM) ; jamais présentés comme
  compatibles avec d'autres émulateurs.

## AD-07 — Identification des ROM

CRC32 + SHA-1 + SHA-256 calculés à l'indexation. Base locale embarquée sous
forme de métadonnées/empreintes uniquement (jamais de contenu ROM). La
classification n'utilise jamais le seul en-tête : sans correspondance
d'empreinte, le statut est au mieux « Modifiée ou non reconnue ».

## AD-08 — UI en Views classiques

L'interface utilise les Views Android (RecyclerView, SurfaceView,
ConstraintLayout, Material Components) plutôt que Compose : dépendances plus
réduites, contrôle fin du rendu de l'écran d'émulation et de l'éditeur de
disposition tactile. Décision réversible module par module.

## AD-09 — Threading

Un `EmulationSession` (module `app`) possède le thread d'émulation. Échanges
UI ↔ moteur par files de commandes et double buffer d'image protégé. Le
thread principal ne bloque jamais sur le moteur. `onPause` déclenche pause +
sauvegarde `.sav` ; `onStop` sérialise un état de secours lorsque possible.

## AD-10 — Dépendances

Autorisées : AndroidX, Material Components, kotlinx-coroutines,
kotlinx-serialization (JetBrains, officielle) pour l'index et les profils.
Interdites : toute bibliothèque d'émulation, tout SDK tiers non officiel.

## AD-11 — Audio en phase dédiée (livrée)

L'APU (4 canaux) a été livré dans une phase dédiée après validation du
moteur principal, conformément au besoin. Le moteur produit des échantillons
stéréo PCM 16 bits à 32 768 Hz (128 cycles par échantillon, division exacte)
dans un tampon circulaire drainé par `EmulatorCore.readAudio`. Côté
application, l'écriture **bloquante** vers `AudioTrack` cadence la session
d'émulation : la synchronisation audio/vidéo découle de l'horloge audio du
système ; en avance rapide ou audio coupé, la session revient au cadencement
par horloge monotone et abandonne les échantillons.

Un **filtre passe-haut** (condensateur par côté, facteur de charge DMG) est
appliqué à la sortie, comme sur console : il retire la composante continue
des DAC et supprime les « pops » à chaque coupure/réactivation de canal.
Chaque canal **intègre** sa sortie sur l'intervalle d'échantillon (filtre
anti-repliement) au lieu d'un instantané, éliminant l'aliasing.

Le **rendu vidéo est découplé** du thread d'émulation : `presentFrame` ne
fait qu'une recopie brève du framebuffer dans un tampon partagé, et un thread
de rendu dédié dessine sur la `SurfaceView` (et se cale sur le vsync) à sa
propre cadence. Le thread d'émulation n'est donc jamais bloqué par
l'affichage : sa cadence est pilotée uniquement par l'écriture audio
bloquante, ce qui supprime les sous-alimentations audio (craquements)
lorsqu'une image tarde à être présentée.

Enfin, la sortie ouvre l'`AudioTrack` au **débit natif** du périphérique
(`PROPERTY_OUTPUT_SAMPLE_RATE`, repli 48 kHz) et rééchantillonne le flux
32768 Hz du moteur avec un `LinearResampler` interne (module `emulation-api`,
testé sur JVM) plutôt que de s'en remettre au rééchantillonneur — de qualité
variable — du système. Le tampon est court (~3 trames) et le chemin basse
latence (`PERFORMANCE_MODE_LOW_LATENCY`) est demandé : la latence de sortie
reste faible, ce qui garde le son proche de l'image (synchronisation A/V) et
l'entrée réactive, sans risque de sous-alimentation puisque le thread
d'émulation n'est plus bloqué par l'affichage.

## AD-12 — Affichage monochrome : niveaux au moteur, couleur au renderer

Le moteur Game Boy ne produit **aucune couleur** : `runFrame` écrit les
quatre niveaux logiques `0..3` (le PPU applique déjà les registres BGP/OBP),
et `EmulatorCore.framebufferFormat` vaut `INDEXED_4`. La colorisation est
faite par le renderer via un **profil d'écran** (`MonochromeDisplayProfile`,
module `emulation-api`) : une palette visuelle de quatre couleurs ARGB,
appliquée au moment de l'affichage.

Conséquences : le profil est indépendant de l'état d'émulation (jamais
sérialisé dans les `.sav` ni les états instantanés), un changement de profil
est visible immédiatement sans redémarrer le jeu, et les captures d'écran
utilisent le profil actif par construction. Les palettes fournies (DMG,
Pocket, Light éteint/allumé, Noir et blanc) sont des **simulations LCD
calibrables**, jamais présentées comme des valeurs officielles — aucune
palette numérique n'ayant été publiée pour ces panneaux, dont la teinte
réelle varie selon le panneau, son vieillissement et l'éclairage.

Les réglages avancés de **luminosité**, **contraste** et **correction
colorimétrique LCD** sont implémentés (`DisplayAdjustments`, module
`emulation-api`, testé sur JVM) comme post-traitement appliqué **après** la
palette, sans modifier les niveaux produits par le PPU et **sans effet par
défaut** (l'objet identité laisse le framebuffer intact). Luminosité et
contraste sont des tables 8 bits par canal (décalage additif, étirement
photographique autour du gris moyen) ; la correction LCD est une **simulation
calibrable** — jamais des valeurs officielles — de la désaturation vers la
luminance et du gamma d'un panneau réfléchissant, surtout utile pour les
couleurs vives de la Game Boy Color. Sur un écran monochrome, seules
luminosité et contraste s'appliquent (la palette est déjà calibrée) ; en
sortie couleur, les trois réglages s'appliquent par pixel. Les autres effets
possibles (persistance, grille LCD, mélange d'images) restent non implémentés
et s'inséreraient dans le même post-traitement, sans scanlines, CRT ni
lissage bilinéaire par défaut.

## AD-13 — Game Boy Color

Le cœur `gameboy-core` gère DMG et Game Boy Color dans un même moteur : le
mode CGB est activé si l'en-tête de la cartouche le déclare
(`supportsCgb`), et les cartouches `.gbc` sont indexées par le même
analyseur que les `.gb`.

Ajouts CGB : deux banques de VRAM (VBK), WRAM 32 KiO en 8 banques (SVBK),
palettes couleur BG/OBJ 15 bits (CRAM, registres 0xFF68–0xFF6B) pré-calculées
en ARGB, attributs de tuiles en banque 1 (palette, banque de tuile,
retournements, priorité), priorité maître LCDC bit 0, transfert VRAM
HDMA/GDMA (0xFF51–0xFF55) et commutateur de vitesse KEY1 (STOP → bascule via
`SpeedController`). En double vitesse, CPU, timers et série tournent à
8,4 MHz tandis que PPU et APU restent à 4,19 MHz ; la boucle de trame compte
les cycles ramenés à l'horloge PPU (70 224 points/trame) pour rester correcte
dans les deux vitesses.

Sortie : en CGB, le PPU produit directement des couleurs ARGB
(`framebufferFormat = ARGB_8888`) et le renderer les affiche telles quelles
(profil d'écran monochrome désactivé) ; en DMG, le comportement niveaux +
profil (AD-12) est conservé. Le format d'état passe en version 4 (CRAM,
banques, vitesse, HDMA).

Limites documentées : timing HDMA HBlank simplifié (un bloc de 16 octets par
HBlank, sans coût cycle précis ni verrouillage bus), séquenceur de trames APU
non doublé en double vitesse (léger effet sur enveloppes/longueurs), registre
OPRI non émulé (priorité sprites par index OAM), pas de correction
colorimétrique LCD (couleurs BGR555 mises à l'échelle linéairement).

## AD-14 — Base d'empreintes de référence

L'identification des ROM s'appuie sur une base locale de **métadonnées et
d'empreintes uniquement** (jamais de ROM), fusion d'une semence embarquée
(`assets/references.json`, vide par défaut) et de bases importées par
l'utilisateur, stockées dans l'espace privé. RavenEmu ne distribue aucune
empreinte de jeu officiel : l'enrichissement se fait par import.

Formats acceptés : DAT Logiqx (No-Intro, Redump…), analysés par
`NoIntroDatParser` (durci contre l'injection d'entités externes XXE), et
datasets JSON natifs (`ReferenceDataset`). La correspondance retient la plus
forte empreinte disponible (SHA-256, puis SHA-1, puis CRC32), ce qui rend les
DAT No-Intro (CRC + SHA-1) directement exploitables. Aucun statut affirmatif
n'est attribué sans correspondance d'empreinte ; à défaut, une ROM dont seul
le titre d'en-tête correspond à un jeu officiel connu est au mieux
« Modifiée ou non reconnue ».

Un changement de base est appliqué à chaud : la bibliothèque est **reclassée**
sans relire les fichiers (les empreintes sont déjà indexées). La palette de
statuts et les surcharges utilisateur (Homebrew déclaré) sont préservées.

## AD-15 — Game Boy Advance : moteur ARM7TDMI dédié (premier lot)

La Game Boy Advance est servie par un **nouveau module `gba-core`**, JVM pur
et indépendant, à côté de `gameboy-core` (qui reste inchangé et fonctionnel).
Il implémente le même contrat [EmulatorCore] et est sélectionné via
`ConsoleType.GAME_BOY_ADVANCE` (extension `.gba`). Comme les autres cœurs, il
est écrit **à partir de documentation matérielle publique**, sans code d'un
autre émulateur, sans BIOS ni ROM Nintendo.

La sélection du moteur passe par l'interface `EmulatorCoreFactory`
(module `emulation-api`), implémentée par `RavenEmulatorCoreFactory` dans la
**racine de composition** (module `app`) : c'est le seul point qui connaît les
cœurs concrets. L'écran d'émulation n'instancie plus aucun moteur directement,
il demande à la fabrique celui de la console de la ROM. Côté bibliothèque, un
`GbaRomAnalyzer` (module `rom-library`) reconnaît les fichiers `.gba`, analyse
l'en-tête et indexe la ROM ; `RomEntry` a été généralisé (les champs propres à
la cartouche Game Boy — MBC, région… — deviennent facultatifs) pour accueillir
des consoles dont l'en-tête ne les définit pas, sans changer le format d'index
persisté (nouveaux champs à valeur par défaut). L'affichage de la bibliothèque
est adapté à la console (une ROM GBA montre « Game Boy Advance » plutôt que des
champs MBC dénués de sens). La sortie du PPU étant en ARGB, le renderer
l'affiche telle quelle (profil monochrome désactivé), exactement comme pour la
Game Boy Color.

Le CPU ARM7TDMI est modélisé par un état architectural séparé (`CpuState` :
`R0..R15`, `CPSR`/`SPSR`, modes et **banques de registres**) et un moteur
(`Arm7Tdmi`) qui délègue à `ArmDecoder` (ARM 32 bits) et `ThumbDecoder`
(Thumb 16 bits). Le pipeline est simplifié mais exact du point de vue logiciel
(`R15` lu à `+8`/`+4`). Le **jeu d'instructions est désormais quasi complet** :
traitement de données + barrel shifter + drapeaux, branchements (`B`/`BL`/`BX`),
`MRS`/`MSR`, transferts simples et demi-mot/signés (`LDR`/`STR`/`LDRH`/`LDRSB`…),
transferts de blocs (`LDM`/`STM`, quatre modes + réécriture), multiplications
(`MUL`/`MLA`, longues), échange (`SWP`), et `SWI` via un mécanisme d'**entrée en
exception** (`raiseException`, réutilisé par l'IRQ à venir) ; côté Thumb, tous
les formats de chargement/stockage, `PUSH`/`POP`, `LDMIA`/`STMIA`, `MUL` et
`SWI`. Restent hors périmètre : coprocesseur (inutile sur GBA), livraison
matérielle des IRQ (dépend du contrôleur d'interruptions), et quelques cas
limites (temps d'attente précis, banque utilisateur de `LDM/STM^`). Le plan mémoire (BIOS, EWRAM, IWRAM, E/S, palette,
VRAM, OAM, ROM, SRAM) est géré par `GbaBus` avec accès 8/16/32 bits,
alignement, rotation des lectures non alignées et zones miroir. Le PPU produit
un framebuffer **240 × 160 ARGB 8888** que le renderer Android affiche sans
rien connaître de ses détails.

**Périmètre du premier lot** (volontairement borné) : sous-ensemble
d'instructions ARM/Thumb suffisant pour exécuter une **ROM synthétique interne**
(traitement de données complet avec barrel shifter et drapeaux, `B`/`BL`/`BX`,
`LDR`/`STR`, `MRS`/`MSR`), bus mémoire minimal, PPU d'**une couleur unie**
(arrière-plan), et format d'état `RVNS` GBA versionné, transactionnel et lié au
SHA-256 de la ROM, distinct de celui de la Game Boy.

**Intégration Android (fait)** : fabrique de production, sélection du moteur par
l'écran d'émulation, reconnaissance et affichage des ROM `.gba` dans la
bibliothèque, et **entrées** : `GbaKeypad` alimente le registre `KEYINPUT`
(actif-bas, dix touches). `EmulatorButton` est étendu de `L` et `R` (gâchettes
d'épaule), ignorées par la Game Boy ; les manettes physiques mappent `L1`/`R1`,
et la disposition tactile ajoute deux boutons `L`/`R` redimensionnables,
affichés uniquement pour la Game Boy Advance.

**Vidéo (premier incrément)** : le PPU est rendu **ligne par ligne** au fil des
cycles (4 cycles = 1 point, 308 points/ligne, 228 lignes). Il expose `VCOUNT`
et les drapeaux `DISPSTAT` (VBlank, HBlank, coïncidence VCount) — lisibles via
le bus — et gère `DISPCNT`. Sont rendus : les **modes bitmap** 3 (16 bpp),
4 (8 bpp paletté, double page) et 5 (16 bpp, 160×128, double page), et les
**modes texte** 0 et 1 avec arrière-plans BG0–BG3 (tuiles 4/8 bpp, défilement,
retournements, priorités). Le blanc forcé et la couleur d'arrière-plan sont
gérés. Les **sprites** (OBJ) normaux sont rendus : tailles carrées et
rectangulaires, 4/8 bpp, mappage 1D/2D, retournements, priorité par pixel entre
sprites et arrière-plans (les sprites gagnent les égalités ; un sprite d'index
OAM inférieur passe devant). Le rendu se fait via une **composition par pixel**
(couleur + priorité de couche). Le framebuffer 240×160 ARGB reste affiché tel
quel par le renderer.

**Événements matériels** : un **contrôleur d'interruptions** (`GbaInterruptController` :
`IE`/`IF`/`IME`, `IF` en écriture-pour-effacer) centralise les sources ; le PPU
lève VBlank/HBlank/coïncidence VCount (si activées dans `DISPSTAT`), les **timers**
et les **DMA** lèvent les leurs. Les **quatre timers** (`GbaTimers`) gèrent
prédiviseur (1/64/256/1024), mode cascade et IRQ de débordement. Les **quatre
canaux DMA** (`DmaController`) copient en mots de 16/32 bits avec contrôle
d'adresse (incrément/décrément/fixe), répétition et IRQ, déclenchés en immédiat,
VBlank ou HBlank. La **boucle machine** livre l'exception IRQ (vecteur `0x18`)
avant chaque instruction quand une interruption autorisée est en attente et que
le drapeau `I` du CPU est dégagé.

**BIOS HLE** : RavenEmu ne distribue **aucun BIOS Nintendo**. Un BIOS de
substitution (`GbaBios`) est écrit intégralement à partir de la documentation
publique et remplit deux rôles.

D'abord un **gestionnaire d'interruption** : un court programme ARM, assemblé
par RavenEmu, est installé au vecteur IRQ `0x18`. Il sauvegarde le contexte, lit
l'adresse du gestionnaire du jeu en `0x0300_7FFC` (miroir de `0x03FF_FFFC`),
l'appelle, restaure puis retourne par `subs pc, lr, #4` — le comportement
documenté du BIOS. C'est cette pièce qui referme la chaîne : une source lève son
IRQ, la boucle machine prend l'exception, le handler BIOS appelle **le code du
jeu**.

Ensuite les **appels logiciels `SWI`**, interceptés en haut niveau
(`Arm7Tdmi.swiHandler`) plutôt que routés vers le vecteur `0x08` : `Div`,
`DivArm`, `Sqrt`, `CpuSet`, `CpuFastSet`, et `Halt`/`Stop`/`IntrWait`/
`VBlankIntrWait` qui posent le drapeau **`halted`** du CPU. En pause, la boucle
machine n'exécute plus d'instruction mais continue d'avancer PPU et timers,
jusqu'à ce qu'une interruption activée réveille le processeur. Les appels non
implémentés (`SoftReset`, `RegisterRamReset`, décompression…) sont sans effet.
Un BIOS **fourni par l'utilisateur** (sélection SAF, validation par taille et
empreinte) reste une option prévue mais non implémentée.

**Vidéo affine, fenêtres et effets de couleur** : les arrière-plans **affines**
(`BG2` en mode 1, `BG2`/`BG3` en mode 2) sont rendus à partir d'un point de
référence interne — rechargé depuis `BGxX`/`BGxY` au VBlank puis incrémenté de
`PB`/`PD` à chaque ligne, comme sur le matériel — et des paramètres `PA`–`PD` en
virgule fixe 8.8 ; la carte affine tient sur un octet par tuile, en 8 bpp, avec
répétition optionnelle. Les **sprites affines** appliquent la matrice du groupe
`attr3` désigné, avec ou sans emprise doublée. Les **fenêtres** (`WIN0`, `WIN1`,
fenêtre objet, `WININ`/`WINOUT`) produisent un masque par pixel indiquant les
couches visibles et l'autorisation des effets ; les bornes qui s'inversent
enroulent la fenêtre. Les **effets de couleur** (`BLDCNT`/`BLDALPHA`/`BLDY`)
couvrent le mélange alpha, l'éclaircissement et l'assombrissement, les sprites
**semi-transparents** imposant le mélange quel que soit le mode programmé.

Ces effets exigent de connaître les **deux couches supérieures** de chaque
pixel : la composition maintient donc, par pixel, la couleur/priorité/identité
de la couche visible et de celle juste derrière. Simplification documentée :
lorsqu'un sprite s'intercale, il remplace la seconde couche plutôt que de
réordonner une pile complète.

**Audio** : `GbaApu` réunit les **quatre canaux PSG** hérités de la Game Boy
(deux ondes carrées avec enveloppe et balayage, table d'onde, bruit à registre à
décalage), réécrits pour `gba-core` afin que **`gameboy-core` reste autonome** —
aucune dépendance entre les deux moteurs — et les **deux canaux Direct Sound**.
Ceux-ci rejouent du PCM 8 bits signé dépilé d'une **FIFO de 32 octets** : chaque
canal est cadencé par le timer 0 ou 1, consomme un échantillon à chaque
débordement, et réclame son réapprovisionnement au **DMA en mode son** (canaux 1
et 2, temporisation spéciale, quatre mots par transfert vers l'adresse de FIFO)
dès que sa file passe sous la moitié. Le mixage suit `SOUNDCNT_L/H/X` (volumes
et panoramique PSG, proportion PSG/Direct Sound, volumes et panoramique Direct
Sound) et produit du PCM stéréo 16 bits à 32 768 Hz, drainé par `readAudio` — le
même contrat que la Game Boy, donc la même sortie Android.

L'horloge audio vaut le quart de l'horloge CPU, ce qui redonne les formules de
période de la Game Boy et un séquenceur de trames à 512 Hz. Les oscillateurs
n'avancent jamais au-delà du prochain point d'échantillonnage : sans cet
entrelacement, une salve d'échantillons observerait un état figé des canaux.

Limites documentées : RAM d'onde traitée comme une **banque unique** (le double
banc du GBA n'est pas émulé), `SOUNDBIAS` et le rééchantillonnage matériel non
émulés, modes obscurs des canaux PSG absents.

**Sauvegardes de cartouche** : le type de mémoire est **détecté** dans la ROM à
partir des chaînes laissées par les bibliothèques officielles (`SRAM_V`,
`FLASH_V`, `FLASH512_V`, `FLASH1M_V`, `EEPROM_V`) et reste **remplaçable
manuellement** (`GbaCore.forcedSaveType`), la détection ne pouvant pas trancher
tous les cas. Trois familles sont émulées :

- **SRAM** 32 Kio : accès direct par octet ;
- **Flash** 64/128 Kio : machine à états de commandes (`0xAA`/`0x55` puis code
  d'opération) couvrant l'écriture d'octet, l'effacement total et par secteur de
  4 Kio, le mode identification (constructeur/modèle) et, en 128 Kio, la
  commutation de bancs ; une Flash vierge vaut `0xFF` ;
- **EEPROM** 512 o / 8 Kio : protocole **série**, un bit par accès 16 bits,
  commandes de lecture (`11`) et d'écriture (`10`) suivies de l'adresse (6 ou
  14 bits) et, en écriture, de 64 bits de données. La largeur d'adresse est
  déduite de la **longueur du transfert DMA**, comme le font les jeux.

Le contenu est exposé au format `.sav` **brut** via le contrat commun
(`hasBatteryRam`, `batteryRamDirty`, `exportBatteryRam`, et restauration à
`loadRom`), donc l'écriture atomique de `SaveFileStore` s'applique sans
changement ; il est aussi inclus dans les états instantanés. La mémoire survit à
un `reset` (power-cycle).

**Interface Android** : la bibliothèque se **filtre par console**, chaque entrée
indique la sienne, et les détails d'une ROM GBA affichent code jeu, validité
d'en-tête et type de sauvegarde. Ce dernier est **réglable par jeu** (détection
automatique ou type imposé) : le choix est mémorisé par empreinte SHA-256 et
transmis au moteur à la création, via la fabrique. Les dispositions tactiles
sont enregistrées **par orientation et par console**, si bien que les gâchettes
`L`/`R` de la Game Boy Advance n'altèrent pas la disposition Game Boy.

**Différé** (limites documentées) : mosaïque, effets mid-scanline ; appels BIOS
restants et BIOS externe ; DMA de capture vidéo, IRQ clavier/série. Les temps
d'attente mémoire sont désormais modélisés (voir AD-16). La **compatibilité
commerciale reste à valider sur matériel réel** : le moteur n'a été éprouvé que
sur des ROM synthétiques.

## AD-16 — Game Boy Advance : cadencement mémoire, DMA progressif et diagnostics

Le premier lot GBA supposait que **tous les accès mémoire coûtaient la même
chose** et qu'un transfert DMA était gratuit. Les deux hypothèses faussent le
cadencement d'un jeu commercial, dont le code s'exécute majoritairement depuis
la cartouche et qui déplace ses graphismes par DMA.

**Temps d'attente mémoire.** `MemoryTiming` décode `WAITCNT` (attente SRAM,
zones d'attente 0/1/2 avec premier accès et accès suivants distincts, file de
préchargement Game Pak) et modélise la largeur du bus par région : 32 bits pour
BIOS, IWRAM, E/S et OAM ; 16 bits pour EWRAM, palette, VRAM et cartouche ;
8 bits pour la SRAM. Un accès plus large que son bus est découpé en accès
matériels dont seul le premier est non séquentiel. `MemoryAccessTiming`
(`sequential`, `nonSequential`) expose ces coûts sous forme descriptive, mais le
**chemin chaud ne manipule que des entiers** : `waitStates` est appelé pour
chaque accès mémoire, il ne construit aucun objet. `GbaBus` qualifie chaque accès
de séquentiel ou non selon l'adresse du précédent, accumule les temps d'attente,
et `Arm7Tdmi.step` les ajoute au coût nominal de l'instruction. Le préchargement
Game Pak ne s'applique qu'aux lectures d'instruction ; son remplissage réel
n'est pas suivi (limite documentée : coût légèrement sous-estimé).

**DMA progressif.** Chaque mot transféré est facturé en une lecture et une
écriture au coût de sa région, le premier mot en accès non séquentiel. Les
cycles dus s'accumulent dans `DmaController.pendingCycles`, que la boucle de la
machine écoule **avant** de rendre la main au processeur : le CPU est réellement
arrêté pendant le transfert, tandis qu'affichage, timers et audio avancent. Les
mots sont copiés d'un bloc puis le temps est écoulé ; aucun autre maître du bus
n'observant l'état intermédiaire, le résultat est identique à celui d'un
transfert entrelacé (limite documentée : la position exacte de chaque mot dans la
ligne d'affichage diffère).

**Aucune allocation dans les chemins chauds.** Le mixage audio tourne 32 768 fois
par seconde et la composition graphique 160 fois par trame : ni l'un ni l'autre
ne crée d'objet. Les niveaux PSG, l'ordre de tracé des plans et les tampons de
ligne sont des tableaux réutilisés ; le tri des priorités se fait sur place par
insertion, à sortie graphique identique. Un test mesure les allocations par
trame via `ThreadMXBean` et échoue si elles réapparaissent.

**Diagnostics.** `GbaDiagnostics` compte les instructions par trame, le dernier
appel logiciel, la dernière interruption et les sous-alimentations audio, et
**signale de façon bridée** cinq anomalies : appel BIOS non géré, instruction
indéfinie, accès hors du plan mémoire, attente d'interruption qui ne vient pas,
flux compressé invalide. Chaque catégorie n'est journalisée qu'un nombre limité
de fois — le détail textuel n'est même pas construit au-delà — alors que les
compteurs continuent de progresser. `GbaCore.debugSnapshot()` expose l'ensemble,
et la surcouche de l'écran d'émulation l'affiche **en Debug uniquement**
(`BuildConfig.DEBUG`) ; en Release elle se limite au nombre d'images par seconde.

**Mesure de performance.** `PerfBenchmark` publie des chiffres indicatifs (temps
par trame, coût du CPU, du PPU, de l'APU et du DMA, instructions par trame et par
seconde, allocations) sans jamais faire échouer la construction :
`PerfRegressionTest` porte les garde-fous, et ils sont **relatifs** (le chemin
rapide du bus doit battre le chemin générique, la cartouche doit coûter plus que
l'IWRAM, rien ne doit s'allouer) ou grossiers à dessein. Un résultat obtenu sur
une JVM de bureau ne dit rien de la cadence atteinte sur un téléphone.
