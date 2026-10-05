# Architecture RavenEmu V2

RavenEmu V2 utilise les chemins physiques comme frontières d'architecture. Un
module Gradle n'est jamais redirigé vers un ancien dossier avec `projectDir`.

```text
RavenEmu/
├── app/
│   └── android/
├── cores/                    # 100 % C++
│   ├── common/
│   ├── gb/
│   ├── gbc/
│   ├── gba/
│   └── nds/
├── native/
│   ├── api/
│   └── jni/
├── engine/                   # Kotlin/JVM pur
│   ├── api/
│   ├── runtime/
│   ├── session/
│   ├── state/
│   ├── save/
│   ├── audio/
│   └── diagnostics/
├── platform/
│   └── android/
│       ├── audio/
│       ├── renderer/
│       ├── input/
│       ├── storage/
│       ├── vibration/
│       └── lifecycle/
├── features/
│   ├── library/
│   ├── player/
│   ├── settings/
│   ├── skins/
│   ├── savestates/
│   └── diagnostics/
├── build-logic/
├── gradle/
├── tools/
├── docs/                     # site officiel RavenEmu
└── settings.gradle.kts
```

## Règles de dépendances

```text
app/android
    ↓
features + platform/android
    ↓
engine
    ↓
native/api + native/jni
    ↓
cores
```

- `cores/` ne contient que du C++ et ne connaît ni JNI, ni JVM, ni Android.
- `native/api` expose la frontière native générique ; `native/jni` est le seul endroit qui connaît JNI.
- `engine/*` reste Kotlin/JVM pur et ne dépend jamais d'Android.
- `platform/android/*` contient les services dépendants du système Android.
- `features/*` contient les fonctions produit ; les features JVM restent indépendantes de la plateforme.
- `app/android` est la composition finale et la coque UI Android.
- `docs/` est réservé aux fichiers du site officiel RavenEmu.

## Cœurs C++

`cores/common` porte le contrat `Core`, les primitives binaires et SHA-256, ainsi
que les contrats sans dépendance plateforme `LinkEndpoint` et
`InfraredEndpoint`.

`cores/gb` porte l'implémentation matérielle commune GB/GBC. Le modèle physique
demandé à la fabrique (`automatic`, `dmg` ou `cgb`) est distinct des capacités
annoncées par la cartouche. Il produit l'un des trois modes effectifs suivants :

- DMG ;
- CGB natif pour une cartouche couleur ;
- CGB exécutant une cartouche DMG en mode de compatibilité.

La fabrique historique conserve le mode automatique et l'identité persistée
Game Boy pour ne pas modifier le contrat JNI, la bibliothèque ou le stockage.

`cores/gbc` est une vraie bibliothèque statique `gbc_raven_core`, avec ses
propres tests matériels. Sa fabrique force maintenant un CGB physique : une ROM
DMG y entre donc en mode de compatibilité au lieu de retomber sur le modèle DMG.
L'extraction est progressive plutôt qu'une copie du cœur GB : les composants
spécifiques déjà déplacés comprennent notamment le contrôleur de double vitesse
et le port infrarouge. Le PPU, les DMA, le port série et les autres organes
communs restent factorisés pendant leur séparation sous tests de parité.

Le dossier porte donc **deux** cibles, et la distinction commande la suite de
l'extraction :

- `gbc_hardware` rassemble les composants CGB déjà extraits. C'est elle que
  `gb_raven_core` lie, parce que l'implémentation unifiée DMG/CGB les consomme.
- `gbc_raven_core` est la façade publique du cœur couleur ; elle s'appuie sur
  `gb_raven_core`.

Les deux arêtes vont en sens inverse et sont toutes les deux justes ; une cible
unique créerait un cycle. `gbc_hardware` reste INTERFACE tant que les composants
extraits ne sont que des en-têtes, et devient STATIC dès que l'un d'eux gagne un
`.cpp` — sans qu'aucune autre cible n'ait à changer.

### Ordonnancement GB/GBC

Le CPU LR35902 ne fait plus avancer une instruction entière d'un bloc. Chaque
lecture, écriture et cycle interne appelle le bus à une frontière de M-cycle.
Le bus avance alors timer, série, PPU, APU, cartouche et DMA dans leurs domaines
d'horloge respectifs ; un GDMA ou un bloc HDMA peut ainsi prendre le bus entre
deux micro-opérations de la même instruction. La double vitesse change le ratio
cycles CPU/dots périphériques sans accélérer le LCD.

Le DMA OAM possède une phase de demande, un M-cycle de démarrage et un transfert
d'un octet par M-cycle CPU. Sa propriété du port OAM est transmise au PPU : le
scan du mode 2 voit des objets hors écran et le fetch OBJ du mode 3 reçoit le mot
16 bits actuellement présenté par le DMA. Cette vue transitoire est reconstruite
depuis l'index DMA et l'OAM lors d'une restauration, sans état PPU redondant.
GDMA/HDMA reste cadencé à un octet par deux dots dans les deux vitesses ; un
HDMA actif ignore une nouvelle commande à bit 7 armé et ne peut être arrêté
qu'entre deux blocs par une écriture à bit 7 nul.

Le PPU partagé utilise un fetcher et deux FIFO sauvegardables, l'une pour le
fond/fenêtre et l'autre pour les OBJ. La durée du mode 3 dépend du décalage fin
`SCX`, du démarrage de fenêtre et des sprites, au lieu d'une constante par
ligne. Une écriture de `WX` après le démarrage de la fenêtre peut armer
l'injection du pixel neutre documenté ; les bits de tuile de `SCX` sont relus
aux étapes Get Tile, tandis que ses trois bits fins restent ceux du discard
initial.

Le fetch OBJ est un état explicite : attente du fetch BG, lecture OAM, lecture
des octets bas/haut en VRAM puis fusion dans le FIFO OBJ. Les coordonnées Y/X
retenues en mode 2, le Tile ID, les attributs, la banque, les octets de tuile et
la décision de priorité sont ainsi échantillonnés à leur phase respective au
lieu d'être relus pour chaque pixel. La fusion conserve la priorité par X sur
DMG/compatibilité ou par index OAM en CGB natif selon `OPRI`. Une coupure de
`LCDC.1` annule un fetch DMG en cours, alors que le matériel CGB poursuit le
fetch et son coût temporel même si les OBJ sont masqués. Le timing résiduel de
l'annulation par rapport à une écriture CPU et les courses propres aux révisions
LCD restent à mesurer ; le PPU n'est donc pas qualifié de cycle-perfect.

Les portes CPU de VRAM, OAM et CRAM sont calculées séparément du mode publié
par `STAT`. En régime établi elles suivent la phase interne du PPU ; pendant les
trois premières lignes qui suivent `LCDC.7` sur DMG, les fronts distincts de
lecture et d'écriture restent explicitement modélisés. Le bus les échantillonne
après l'avancement du M-cycle, y compris en double vitesse. Une écriture CGB de
`BGPD`/`OBPD` refusée en mode 3 laisse la CRAM intacte mais avance tout de même
l'index lorsque l'auto-incrément est armé.

Pendant les 2050 M-cycles d'une transition `KEY1`, le raster continue mais ses
portes internes restent figées au niveau du mode où `STOP` a commencé : aucune
mémoire vidéo en modes 0/1, fond sans OAM en mode 2, accès complets en mode 3.
La phase figée est sauvegardée et validée avec le compteur du contrôleur de
vitesse. Les effets d'interruptions pendant cette pause et les différences de
révision CGB restent à caractériser sur matériel.

Le séquenceur APU n'emploie plus un compteur autonome de 8 192 dots. Le bus
observe le front descendant du bit 12 du diviseur interne, ou du bit 13 en
double vitesse, y compris lors des remises à zéro par `FF04` et `STOP`. Les
compteurs de longueur, enveloppes et sweep restent ainsi liés à la phase réelle
de `DIV`. Les comportements communs documentés (reload de longueur raccourci,
enveloppe de période zéro, délai de trigger, corruption wave DMG pendant une
lecture, coupure LFSR 14/15, premier pas duty, pente DAC/filtre par matériel et
cas zombie portable) sont modélisés. Le profil
matériel ne distingue pas encore le CGB-02 du CGB-04/05 ; sa variante du clock
de longueur et les autres variantes zombie DMG restent explicitement ouvertes.

Le format d'état GB/GBC est en version 10. Il sérialise la phase du séquenceur
APU dérivée de `DIV`, le pixel `WX` éventuellement armé, le FIFO OBJ et chaque
phase intermédiaire de son fetch, y compris les dots où le fetch OBJ et la
sortie du FIFO BG progressent simultanément, ainsi que la porte vidéo figée par `KEY1`.
Les portes ordinaires du bus vidéo et la contention OAM DMA sont dérivées des
phases déjà sérialisées et ne constituent pas un état redondant. Les versions 9
et antérieures sont refusées au lieu d'être chargées partiellement ; les phases
PPU, DMA ou KEY1 incohérentes d'un état version 10 sont également rejetées
explicitement.

Une boot ROM DMG ou CGB peut être injectée par les fabriques C++ publiques. Son
mapping et `FF50` restent dans le cœur ; aucune image n'est distribuée. Sans
image, le cœur conserve un démarrage HLE post-boot explicite, avec des registres
CPU/APU distincts pour DMG, CGB natif et compatibilité CGB. Lorsqu'une image est
présente, un CGB démarre avec ses fonctions natives puis bascule, à l'écriture
de `FF50`, vers la compatibilité si la cartouche est monochrome. Les mémoires et
registres non documentés au vrai power-on sont initialisés à zéro de manière
déterministe ; cette normalisation est une approximation assumée des valeurs
électriques non initialisées. Les images CGB peuvent utiliser le format compact
de 2 048 octets ou le layout adressé de 2 304 octets qui conserve le trou
`0100-01FF`.

Le fallback HLE cible les phases observables DMG ABC/MGB et CGB ABCDE, y compris
le diviseur série libre aligné au reset. Les autres révisions matérielles ne
sont pas implicitement assimilées à ces profils.

Le sous-système cartouche possède désormais un contrôleur MMM01 distinct du
MBC1. Le parseur recherche son en-tête dans les 32 derniers Kio, qui sont les
seuls visibles au reset, puis le contrôleur conserve séparément les bits de
sélection du jeu, leurs masques d'écriture et le verrou irréversible du mode
mappé. Les chemins standard et multiplexés composent directement les lignes de
banque ROM/RAM sans table par jeu. La RAM batterie reste un fichier brut ; les
registres transitoires MMM01 appartiennent seulement à l'état instantané. Pan
Docs ne tranche ni l'accès RAM avant mapping, ni l'effet d'une écriture qui
change simultanément le masque RAM et arme le mapping : RavenEmu conserve le
chemin RAM décodé et applique le masque avant le verrou, choix isolés et
documentés en attente d'une mesure matérielle publique.

Le MBC6 possède lui aussi un contrôleur dédié. `Mbc6` décode ses deux fenêtres
ROM/flash de 8 Kio, ses deux fenêtres SRAM de 4 Kio et les signaux `/CE` et
`/WP`, tandis que `Mbc6Flash` modélise séparément le composant MX29F008TC :
séquences de déverrouillage, identifiants JEDEC, tampon de programmation de
128 octets, huit secteurs, région cachée et protection non volatile du secteur
0. Aucune table par jeu n'intervient dans ce chemin.

La persistance MBC6 est un conteneur versionné `RVM6` qui réunit les 32 Kio de
SRAM, le Mio de flash, les 256 octets cachés et le bit de protection. Son état
instantané conserve en plus les registres, le mode de lecture, l'automate de
commande et un tampon de programmation partiellement rempli. La garde de taille
GB/GBC est donc portée à 2 Mio. Le contrôleur n'impose pas de changement de
version supplémentaire : les anciens formats refusaient le type `$20` et ne
pouvaient pas produire un état MBC6 ambigu. Les durées internes de
programmation/effacement ne sont pas encore cadencées ; l'opération est
appliquée immédiatement et le statut expose
directement `ready`. Les bits de statut publiquement non déterminés sont
normalisés à zéro, sans prétendre reproduire leur niveau électrique.

Le MBC7 sépare de la même façon le décodage cartouche et son EEPROM 93LC56.
`Mbc7Eeprom` reçoit les quatre broches logiques `CS/CLK/DI/DO`, décode les
commandes série MSB-first (lecture, écriture, effacement, opérations globales et
verrou EWEN/EWDS), conserve le signal `RDY` pendant un cycle d'écriture nominal
de 5 ms et avance en dots indépendamment de la double vitesse CPU. Les 256
octets EEPROM constituent directement la sauvegarde batterie.

L'accéléromètre est alimenté par une entrée abstraite
`Core::set_game_boy_acceleration`, exprimée en unités brutes autour du repos.
Le contrôleur applique le centre matériel `$81D0`, puis ne rend la mesure
visible qu'après la séquence de latch `$55/$AA`; aucun code Android n'entre dans
le cœur. L'API Kotlin `EmulatorCore::setGameBoyAcceleration` et son transport
JNI exposent la même entrée sans imposer de backend de capteur à la plateforme.
L'hôte conserve cette valeur à travers chargements et resets, tandis qu'un
save state restaure bien l'entrée émulée capturée. L'entrée courante, le latch,
les broches, la commande EEPROM partielle,
le verrou d'écriture et la période occupée figurent dans l'état instantané. Ce
layout n'impose pas de changement supplémentaire à la version globale 10,
puisque le type `$22` était auparavant refusé.

Le libellé d'en-tête historique du type `$22` mentionne un rumble, mais les
cartes MBC7 connues documentées ne montrent ni moteur ni commande publique
établie. RavenEmu n'invente donc pas de bit de contrôle : cette partie reste
explicitement ouverte jusqu'à une mesure matérielle reproductible.

Le HuC1 n'est pas traité comme un alias du MBC1. Son contrôleur dédié conserve
une banque ROM directe de six bits, une banque RAM de deux bits et une SRAM
toujours accessible. Une écriture exacte de `$0E` dans `$0000–$1FFF` remplace
temporairement la fenêtre SRAM par le registre IR miroir ; toute autre valeur
revient à la SRAM et `$6000–$7FFF` reste sans effet observable. La sauvegarde
batterie demeure une image SRAM brute. Le layout d'état HuC1 conserve les
banques, le mode RAM/IR, les deux niveaux logiques du transceiver et la SRAM.
Ce layout n'impose pas de changement supplémentaire à la version globale 10,
car les versions précédentes refusaient le type `$FF`.

Une machine CGB munie d'un HuC1 contient deux transceivers distincts : `RP`
dans la console et celui de la cartouche. `MachineInfraredPort` les agrège
derrière une unique extrémité externe. La lumière distante est distribuée aux
deux récepteurs et leurs LED sont combinées, sans permettre à une machine de
s'éclairer elle-même. Les valeurs d'écriture IR autres que `$00/$01` sont
actuellement normalisées sur leur bit 0, les lignes de banque ROM au-delà des
six publiquement établies sont ignorées et la propagation est logique et
instantanée. Ces trois points restent explicitement à caractériser sur matériel.

Le HuC3 possède également un contrôleur propre au lieu d'être assimilé au
MBC3. `Huc3` décode la banque ROM directe sur sept bits, les quatre banques de
SRAM et les modes `$0/$A-$E`; `Huc3Mcu` porte séparément la boîte aux lettres
B/C, le sémaphore D, l'index et les 256 nibbles internes. Les commandes de
lecture/écriture fixes ou auto-incrémentées, le réglage de l'index, la commande
de présence et les copies entre l'horloge et les nibbles `$00-$05` sont
exécutées après une phase occupée sauvegardable. Le compteur minute/jour
12 bits avance depuis une horloge injectable, reboucle après 4 096 jours et
reste donc déterministe dans les tests comme pendant un arrêt de l'émulateur.

La sauvegarde HuC3 conserve d'abord les 32 Kio de SRAM bruts, puis un pied de
page versionné `RVH3` contenant les nibbles empaquetés, le reliquat de secondes,
l'index et l'époque de synchronisation. Une ancienne image limitée exactement
à 32 Kio reste importable ; toute extension de taille, signature ou version
incorrecte est refusée en entier. Le layout d'état ajoute les registres du
mapper, le transceiver, une commande MCU éventuellement en cours et toute la
mémoire interne. Ce layout n'impose pas de changement supplémentaire à la
version globale 10 puisque RavenEmu refusait jusque-là le type `$FE`.

`CartridgeInfraredPort` factorise désormais l'attachement transactionnel, la
LED et le phototransistor des HuC1/HuC3. La durée exacte des commandes du MCU
HuC3 n'étant pas mesurée publiquement, elle est isolée et normalisée à quatre
dots (un M-cycle à vitesse normale). Le synthétiseur de tonalité, les alarmes
autonomes et les valeurs électriques initiales non documentées restent
explicitement ouverts ;
les nibbles concernés sont conservés, mais aucun son ou événement fictif n'est
produit. Au démarrage sans sauvegarde, la mémoire interne et la réponse C sont
normalisées à zéro ; une minute de réglage hors de `$000-$59F` est repliée
modulo 1 440, faute de mesure publiée pour ces entrées invalides.

`cores/gba` porte le moteur Game Boy Advance indépendant.

`cores/nds` est une **fondation**, pas encore un moteur. Il porte l'identité de
la console, décode et contrôle l'en-tête de cartouche, publie le contrat vidéo
et audio, exécute les deux processeurs de la console avec leurs jeux
d'instructions et le coprocesseur système du principal, décode les cartes
mémoire que chacun voit, les fait dialoguer, dessine les décors et les sprites de
ses deux moteurs graphiques, balaie ses deux écrans, fait tourner tout cela
ensemble, et **démarre une cartouche** : les deux binaires sont chargés à leurs
adresses, les deux processeurs partent de leurs points d'entrée, ils alternent au
rythme de leurs horloges, le faisceau avance entre eux, et une trame se dessine
ligne par ligne. `run_frame` produit donc une image.

Ce que cette image vaut est une autre question : il manque encore les minuteries,
les transferts autonomes, les entrées, les appels du programme d'amorçage, le bus
de cartouche et le moteur 3D. La plupart des cartouches réelles s'arrêteront donc
tôt. Ce qui refuse encore franchement, c'est l'enregistrement d'un état, faute de
format.

`cores/nds/src/cpu` tient les deux processeurs. Ils ne connaissent pas la carte
mémoire de la console : ils passent par une frontière `Bus` abstraite, ce qui
permet de les éprouver contre une simple mémoire de test — sans cartouche, sans
banques vidéo — et donc de distinguer une faute du processeur d'une faute de la
machine autour.

**Une seule implémentation les sert.** Ce n'est pas une économie de lignes :
deux copies dériveraient l'une de l'autre, et une correction apportée à l'une
laisserait l'autre avec l'ancienne faute. Ce qui les sépare tient dans une
révision d'architecture, `Architecture`, nommée et consultée aux quelques
endroits où elle compte — ces endroits sont ainsi énumérables, ce qu'une
duplication interdirait. `Arm9` et `Arm7` ne sont que ce cœur commun instancié
avec l'une ou l'autre.

Le processeur principal est un ARM946E-S, jeu ARMv5TE. Le secondaire est un
ARM7TDMI, jeu ARMv4T : il tient l'amorçage, le son, l'écran tactile et la
liaison sans fil, et son jeu est plus étroit — ni `BLX`, ni `CLZ`, ni
arithmétique saturante, ni doubles mots, ni coprocesseur. Ces absences sont
celles du matériel, et les instructions correspondantes lèvent l'exception
d'instruction indéfinie comme sur console. Une différence est plus insidieuse
que les autres : **charger le compteur de programme entrelace sur ARMv5 et pas
sur ARMv4T**, si bien qu'un même `LDR PC` change de jeu d'instructions sur l'un
et reste où il est sur l'autre.

Les deux jeux d'instructions sont complets de part et d'autre : ARM 32 bits et
Thumb 16 bits, avec le passage de l'un à l'autre dans les deux sens. Un jeu de
la console alterne sans cesse entre eux — le code compact en Thumb, les
gestionnaires d'interruption en ARM — si bien qu'un cœur qui n'en connaîtrait
qu'un ne ferait rien tourner. Ils partagent le décaleur, les indicateurs et le
banc de registres ; ils diffèrent surtout sur un point, que le code isole :
Thumb écrit ses indicateurs d'office, là où ARM demande un bit `S` explicite.

Le coprocesseur système CP15 accompagne le seul processeur principal, et c'est
par lui que celui-ci cesse d'être un simple exécuteur d'instructions. Le
processeur secondaire n'en a aucun, et la distinction est portée par un pointeur
nul plutôt que par un objet inerte : un coprocesseur qui répond « rien » n'est
pas la même chose qu'un coprocesseur absent. Trois choses y sont observables
depuis le processeur : les mémoires locales, qui ne sont pas sur le bus mais
dans le cœur et répondent avant lui ; la base de la table des vecteurs, que le
logiciel déplace ; et l'attente d'interruption, qui arrête le cœur au lieu de le
faire tourner à vide. Sans les mémoires locales, la carte mémoire de la console
ne peut pas être juste, parce que les mêmes adresses désignent autre chose selon
qu'une mémoire locale les couvre ou non.

Les caches ne sont pas modélisés, et les opérations qui les vident sont donc
acceptées sans effet. L'unité de protection est tenue mais pas appliquée : ses
registres s'écrivent et se relisent fidèlement, parce que le logiciel les relit,
mais aucun accès n'est refusé faute d'un chemin d'exception d'abandon où le
refus aurait un sens.

Les multiplications signées de la variante DSP et le point d'arrêt matériel ne
sont pas écrits : ils sont décodés et comptés comme non implémentés plutôt que
passés sous silence, parce qu'une instruction inconnue exécutée sans bruit donne
un jeu qui part à la dérive sans qu'on sache où. Aucune durée n'est comptée non
plus — une instruction par pas, sans cache et sans attente de bus — la justesse
temporelle demandant un modèle de durée que rien ne consomme encore.

`cores/nds/src/memory` porte les cartes mémoire, c'est-à-dire ce à quoi mène une
adresse. Il y en a **deux**, une par processeur, et elles ne voient pas la même
chose : le processeur secondaire ignore la palette, la mémoire d'objets et la
plupart des banques vidéo, et dispose en propre de soixante-quatre kilooctets de
mémoire de travail que l'autre ne voit pas.

Ce qu'elles partagent vit dans `SystemMemory` : la mémoire principale et la
mémoire commune. Les tenir là plutôt que dans l'une des deux cartes est ce qui
permet qu'une écriture faite par un processeur soit vue par l'autre, sans quoi
la communication entre eux serait impossible à écrire.

Rien n'y est acquis : le même nombre désigne deux choses différentes selon la
configuration, et trois mécanismes y pourvoient. Le partage de la mémoire commune
répartit trente-deux kilooctets entre les deux processeurs en quatre découpages
**complémentaires** — ce que l'un reçoit, l'autre ne l'a pas — dont deux ne
laissent rien à l'un d'eux, et « rien » est un état légitime, pas une panne. Les
deux fenêtres sont calculées depuis le même registre, de sorte qu'aucun découpage
ne puisse rendre les deux processeurs propriétaires du même octet. Privé de sa
part, le processeur secondaire ne se retrouve pas devant une fenêtre muette :
elle donne alors sur sa mémoire propre, et un programme qui s'y adresse continue
de fonctionner. Les mémoires locales du processeur principal, enfin, ne passent
jamais par sa carte : il les consulte avant le bus, si bien qu'une adresse peut
ne rien désigner là tout en lui répondant très bien. Le reste est du miroir,
parce que le matériel ne décode pas les bits hauts.

Les neuf banques vidéo ne vivent plus ici : elles sont passées à
`cores/nds/src/video`, avec leur aiguillage, parce qu'une banque n'est pas une
mémoire à une adresse fixe. Le BIOS, la cartouche et le port Game Boy Advance ne
sont pas décodés, faute de contenu à leur donner.

`cores/nds/src/system` porte ce qui n'appartient à aucun des deux processeurs
mais les relie. C'est ici que la console cesse d'être deux machines côte à côte :
les deux cartes mémoire donnaient déjà sur les mêmes octets, mais rien ne
permettait à l'un de dire à l'autre qu'il y avait écrit.

Deux mécanismes, et ils ne se remplacent pas. Le registre de synchronisation
porte quatre bits dans chaque sens : ce que l'un écrit, l'autre le relit à
l'autre bout du registre, de sorte que les deux côtés voient le même nombre aux
champs échangés près. Il sert aux échanges brefs — un état, un accusé, une étape
d'amorçage. Les deux files portent seize mots chacune, une par sens, et servent
aux commandes et à leurs réponses.

**Le destinataire de chaque interruption est le point délicat.** La file qui se
remplit réveille celui qui reçoit ; la file qui se vide réveille celui qui
envoie, puisque c'est lui qui attend de pouvoir en déposer d'autres. Se tromper
de côté donne deux processeurs qui s'attendent l'un l'autre sans fin, et rien
dans le code ne le signalerait : les deux chemins compilent, et seule une suite
qui monte les deux processeurs ensemble peut trancher. Ces réveils se posent sur
un front, non sur un niveau — une file déjà pleine qu'on remplit encore ne
réveille personne une seconde fois.

Déborder une file n'est pas refusé. Le matériel inscrit une erreur, rend la
dernière valeur lue ou écarte le mot, et continue ; le logiciel est censé
consulter cette erreur, qui ne s'efface qu'en écrivant un bit à un. Lever une
exception serait infidèle : un programme qui déborde sa file ne s'arrête pas sur
console.

Le contrôleur d'interruptions de chaque processeur transforme ces demandes en
interruption réellement prise, ou les laisse dormir. Son registre de demandes se
comporte à l'envers de ce qu'on attend — **écrire un bit à un l'efface** — parce
que c'est ainsi qu'un gestionnaire acquitte ; l'écrire normalement donnerait des
interruptions qui se redéclenchent sans fin. Il accepte toutes les sources, y
compris celles qu'aucun organe ne pose encore : ni retour de balayage, ni
minuteries, ni transferts autonomes.

Ces registres ont contraint les deux cartes mémoire à connaître la largeur de
l'accès qu'on leur demande, là où elles décomposaient jusqu'ici en octets. Lire
une file est **indivisible** : la décomposer en quatre lectures d'octet la
viderait quatre fois.

**L'ordonnanceur** monte enfin tout cela et le fait avancer. Il n'apporte aucun
organe nouveau : deux processeurs, deux cartes, la mémoire partagée, les files,
les deux moteurs et le balayage existaient déjà, mais chacun attendait qu'on
l'appelle.

Faire tourner un processeur pendant toute une trame puis l'autre donnerait
exactement les mêmes registres à la fin et une console qui ne marche pas, parce
que les deux se parlent en cours de route : celui qui dépose un mot dans une file
et attend la réponse attendrait une trame entière. Les deux avancent donc par
petits pas **alternés**, au plus fin que ce cœur sache faire — une instruction —
et le processeur principal joue deux fois pour une du secondaire, comme le veut
le rapport de leurs horloges. Ce rapport est réel et s'observe ; le nombre
d'instructions accordées à une ligne ne l'est pas.

Aucune instruction ne dure ici : rien ne compte les cycles, ni les attentes de
bus. Le budget d'une ligne repose donc sur une **convention explicite, une
instruction par cycle de l'horloge maître**. Les 2130 cycles d'une ligne sont,
eux, ceux du matériel — 355 points à six cycles — et le jour où les instructions
auront une durée, c'est la convention qui disparaîtra, pas les constantes. La
console tourne ainsi plus vite qu'une vraie ; ce qui est préservé, et qui compte
davantage, c'est le rapport entre les deux processeurs et la place du balayage.

Les deux processeurs savent s'arrêter, et par deux chemins différents que le
matériel impose : le principal par une opération de son coprocesseur, le
secondaire par un registre d'entrée-sortie. L'état d'arrêt appartient donc au
cœur, et non au coprocesseur que l'un des deux n'a pas. Ce qui les relance est en
revanche le même des deux côtés, et **ce n'est pas la condition qui fait prendre
l'interruption** : une source autorisée en attente suffit, sans l'autorisation
générale. Un programme de console coupe couramment cette autorisation avant de
s'arrêter, pour traiter la demande à la main plutôt que par le vecteur ; la lui
imposer pour repartir l'endormirait définitivement.

**L'amorçage** fait ce que l'en-tête de cartouche décrit, et rien de plus : les
deux binaires sont copiés à leurs adresses de chargement, par mots comme le fait
le transfert de cartouche, et les deux processeurs pointés sur leurs points
d'entrée. Amorcer remet d'abord la console à zéro, sans quoi deux exécutions se
mêleraient.

**Ce n'est pas tout ce que le matériel fait**, et l'écart est dit plutôt que
comblé au jugé. Sur console, un programme d'amorçage tourne avant la cartouche et
laisse un état que l'en-tête ne décrit pas : piles des différents modes, mémoires
locales du processeur principal configurées, registres initialisés. Cet état
n'est pas modélisé, faute d'une source qui en fixe les valeurs dans ce dépôt ;
les inventer serait une affirmation que rien ne vérifie. Les deux processeurs
partent donc de leur état de mise sous tension. Un programme qui monte sa propre
pile et démasque lui-même ses interruptions démarre ; un programme qui compte sur
l'amorceur ne démarre pas.

Une conséquence en découle pour la suite : le chargement passe par la carte
mémoire et non par le chemin d'écriture du processeur. Les deux coïncident tant
que les mémoires locales sont éteintes, ce qui est le cas faute d'amorceur pour
les allumer ; le jour où cet état sera modélisé, le chargement devra passer par
le processeur, sinon un binaire destiné à une mémoire locale atterrirait à côté.

**Les quatre minuteries** de chaque processeur donnent enfin à un programme une
horloge plus fine que la trame. Le balayage ne mesurait que des seizièmes de
seconde ; un son se cadence à des dizaines de milliers de fois par seconde, une
attente se compte en microsecondes. Un jeu privé de minuteries ne va pas plus
lentement : il s'arrête, parce qu'il attend un compteur qui ne bouge jamais.

Elles appartiennent à la carte de leur processeur, comme le registre
d'alimentation, et non à un organe partagé : rien de ce qu'elles comptent ne
traverse d'un processeur à l'autre.

Trois choses y comptent plus qu'elles n'en ont l'air. **Le reste de la division
est conservé** d'un pas à l'autre : le jeter ferait dériver une minuterie lente
d'autant plus vite qu'on l'interroge souvent, et la dérive resterait invisible
jusqu'au jour où un jeu compte dessus. **Le registre bas ne dit pas la même
chose dans les deux sens** : on y lit le compteur, on y écrit le rechargement, et
confondre les deux donne soit un temps immobile, soit un jeu qui replace le temps
où il veut. Et **une minuterie peut compter les débordements de celle qui la
précède** plutôt que le temps, ce qui est la seule façon d'obtenir un compteur
plus large que seize bits ; une minuterie éteinte au milieu d'une chaîne la rompt
plutôt que de se laisser enjamber.

Les minuteries avancent à la granularité d'une ligne de balayage, comme tout le
reste : une interruption tombe à une frontière de ligne plutôt qu'à l'instant
exact du débordement. L'allumage ne remet pas la phase du diviseur à zéro, faute
d'une source qui le dise ; l'écart possible vaut moins d'un pas.

**Les quatre canaux de transfert autonome** de chaque processeur copient la
mémoire sans que le processeur y revienne. C'est par là que passent la palette,
la table des sprites et les tuiles à chaque trame : un émulateur qui n'a pas ces
canaux ne montre pas un écran fautif, il montre un écran qui ne se met jamais à
jour.

**Le point délicat est le moment où un canal part.** À l'allumage pour un départ
immédiat, ou au moment demandé : retour vertical, retour horizontal. Les autres
moments désignent des organes qui n'existent pas encore, et un canal qui les
demande est **compté** plutôt que parti au mauvais instant — un transfert
déclenché trop tôt est plus difficile à diagnostiquer qu'un transfert qui n'a pas
lieu. Le champ qui porte ce moment n'a pas la même largeur des deux côtés, si
bien que les mêmes bits disent « retour vertical » au processeur principal et
« départ immédiat » au secondaire.

Un canal peut se réarmer après chaque transfert, et c'est ainsi qu'un jeu obtient
une copie à chaque trame sans y revenir. La répétition n'a de sens qu'avec un
moment : un départ immédiat qui se répéterait tournerait sans fin. Des deux
façons dont une arrivée évolue, l'une revient à son point de départ à chaque
tour et l'autre poursuit son chemin.

Un transfert a lieu **d'un coup, entre deux instructions**, là où le matériel
l'entrelace avec le processeur en lui volant des cycles. La différence
s'observerait sur un programme qui lit la zone d'arrivée pendant qu'elle se
remplit ; elle ne s'observe pas sur un programme qui attend la fin, ce que fait
le logiciel ordinaire. Un canal armé par un moment de fin de ligne est servi au
premier pas de la ligne suivante, exactement comme l'interruption posée au même
instant.

**Les touches** sont enfin lisibles. Les dix de la face avant se lisent des deux
côtés, à la même adresse ; les deux supplémentaires, le contact de l'écran
tactile et celui du couvercle **ne se lisent que du côté du processeur
secondaire**, et un jeu qui veut les connaître doit les lui demander par la file.
Cette asymétrie est celle du matériel, et elle explique pourquoi l'état vit dans
un organe partagé plutôt que dans l'une des deux cartes.

Les deux registres sont **actifs à zéro** : une touche tire une ligne vers la
masse, et une ligne que rien ne tire se lit à un. Inverser la convention ne donne
pas une console en panne mais un jeu qui part tout seul, toutes touches enfoncées.
La même raison décide de ce que rendent les bits sans emploi, et c'est une
conséquence plutôt qu'une affirmation de plus.

Chaque processeur règle son propre réveil par les touches, avec deux conditions
qui ne se ressemblent pas : dès qu'une des touches choisies est enfoncée, ou
seulement quand **toutes** le sont. La seconde sert aux combinaisons. Une
sélection vide ne réveille jamais, dans les deux cas : en mode combinaison,
l'ensemble vide serait sinon satisfait par n'importe quel état. Le réveil tient
sur un niveau et non sur un front, si bien qu'une touche gardée enfoncée le
redemande après chaque acquittement.

Les coordonnées de l'écran tactile ne sont pas là : elles ne passent pas par un
registre mais par un convertisseur que le processeur secondaire interroge en
série, et cet organe n'existe pas encore. Le contact lui-même l'est, puisqu'il
est porté par un registre. Les deux touches supplémentaires sont modélisées dans
le matériel mais **hors de portée de l'interface publique**, dont l'énumération
décrit une manette à dix touches commune à tous les cœurs.

`cores/nds/src/video` porte les neuf banques, leur aiguillage, les deux moteurs
graphiques 2D et le contrôleur d'affichage.

Ce matériel est **partagé par les deux processeurs**, et non possédé par l'un
d'eux. Il vivait d'abord dans la carte du processeur principal, ce qui suffisait
tant que lui seul y touchait ; le contrôleur d'affichage a changé cela, le
processeur secondaire lisant l'état du balayage et se faisant réveiller par lui.
Laisser ce matériel chez l'autre aurait obligé une carte à dépendre de sa
jumelle, alors qu'elles sont paires. C'est la même décision que pour
`SystemMemory`, et pour la même raison.

**Une banque vidéo n'est pas une mémoire à une adresse fixe** : c'est un bloc
qu'on branche quelque part. Le même bloc peut servir de décor au moteur
principal, de sprites au secondaire, de texture au moteur 3D, de palette
étendue, ou être prêté au processeur secondaire — et ce qu'il vaut à une adresse
donnée dépend entièrement de ce branchement. Tant que personne ne lisait ces
banques, les tenir pour de simples tableaux suffisait ; ça cesse dès qu'un
moteur doit y trouver ses décors.

Le décodage est écrit banque par banque, et non ramené à une formule commune :
ni le nombre de destinations, ni la façon dont le champ d'écart les place, ne se
déduisent d'une règle générale. Deux banques n'acceptent que quatre destinations,
cinq en acceptent huit ; certaines se placent par blocs de cent vingt-huit
kilooctets, deux d'entre elles combinent deux bits d'écart qui ne se suivent pas,
et une seule ne commence pas au début de sa fenêtre. Une banque branchée seize
kilooctets trop loin donne un décor faux sans que rien ne le signale.

**Remplir une banque et l'afficher sont exclusifs.** Une banque branchée sur un
moteur quitte la fenêtre de transfert, celle qu'on emprunte pour la remplir ; le
matériel ne les distingue pas d'une banque éteinte, et le code non plus. Quand
deux banques se disputent la même place, le résultat n'est pas défini sur
console : ici la première dans l'ordre répond, et le recouvrement est compté
plutôt qu'absorbé, parce qu'une faute de configuration passée sous silence se
manifeste bien plus loin sous la forme d'un décor faux.

Les deux moteurs partagent une implémentation, pour la raison qui a valu aux deux
processeurs : deux copies dérivent. Le principal place ses décors dans une fenêtre
quatre fois plus grande et décale ses bases par deux champs supplémentaires ; il
reçoit le rendu 3D comme un plan, sait afficher une banque telle quelle et lire
son image depuis la mémoire principale. Le secondaire n'a rien de tout cela.

Sont rendus les **décors en mode texte** : tuiles de huit sur huit, seize ou deux
cent cinquante-six couleurs, retournement dans les deux sens, quatre tailles de
carte, défilement, et la résolution des priorités entre les quatre plans et le
fond. C'est le socle, parce que tous les autres modes s'appuient sur les mêmes
palettes, les mêmes priorités et la même composition.

Et les **sprites ordinaires** : cent vingt-huit objets, douze formats donnés par
deux champs séparés dont le couple ne se déduit ni de l'un ni de l'autre, les
deux profondeurs de palette, les deux retournements, et les deux rangements de
tuiles. Les retournements portent sur le sprite entier et non sur chacune de ses
tuiles, ce qui les distingue de ceux d'un décor. Les deux replis comptent : une
ordonnée sur huit bits fait reparaître en haut un sprite posé bas, une abscisse
sur neuf bits fait revenir par la gauche un sprite posé au-delà du bord droit,
et c'est ainsi qu'un jeu fait entrer ses personnages par les côtés.

**Un sprite passe devant un décor de même priorité.** C'est l'inverse de la règle
entre décors, où le plus petit numéro l'emporte : la comparaison est large d'un
côté, stricte de l'autre, et c'est ce qui met un personnage devant son sol
plutôt que dedans.

Le tampon des sprites couvre les cinq cent douze positions de l'abscisse, non les
deux cent cinquante-six de l'écran. Ce n'est pas du gaspillage : un sprite posé
au bord y dépose ce qui dépasse, la composition ne relit que l'écran, et le
découpage vient donc de la forme du tampon plutôt que d'une condition qu'aucune
image ne permettrait de vérifier.

Une entrée d'attributs à zéro ne décrit pas l'absence de sprite : elle décrit un
sprite de huit sur huit, allumé, posé en haut à gauche. Une table vierge en
dessine donc cent vingt-huit superposés, et tout logiciel de console commence par
les éteindre.

Deux détails y comptent plus qu'ils n'en ont l'air. **La première couleur d'une
palette n'est pas une couleur** : c'est l'absence de pixel, et c'est ce qui
permet à quatre plans de se superposer sans se cacher entièrement ; une
sous-palette déplace les quinze autres couleurs sans déplacer celle-là. Et **à
priorité égale, le plan de plus petit numéro l'emporte**, ce qui tient à une
comparaison stricte : la rendre large cacherait un décor derrière un autre.

Un numéro de plan ne veut rien dire à lui seul : le plan 3 est un décor en
tuiles dans un mode, une surface tournante dans un autre, et n'existe pas dans un
troisième. Une table le dit mode par mode. Les plans qu'un mode ne donne pas ne
sont pas comptés comme manquants, parce que le matériel n'en affiche pas non
plus ; en revanche un plan demandé dans un mode que ce lot ne dessine pas encore
est compté, un plan absent qui ne dit rien se confondant avec un plan vide.

**Le contrôleur d'affichage donne son rythme à la console.** Un jeu n'attend pas
le temps qui passe : il attend le retour du balayage. Sans lui, un moteur
graphique est une fonction que personne n'appelle.

L'écran fait 192 lignes, le balayage en compte 263. Les 71 lignes de différence ne
s'affichent pas, et c'est pendant elles qu'un jeu prépare la trame suivante :
d'où l'importance de l'interruption de retour vertical, la plus utilisée de la
console. **La toute dernière ligne n'est pas comptée comme retour vertical**, ce
qui surprend et compte, un logiciel qui scrute cet indicateur le voyant retomber
une ligne avant la fin.

Le compteur de lignes est unique, puisque c'est un seul faisceau, mais **chaque
processeur a son propre registre d'état**, avec ses propres autorisations : l'un
peut demander à être réveillé au retour vertical sans que l'autre le soit, et
chacun guette la ligne qu'il veut. Le neuvième bit de cette ligne est rangé loin
des huit autres, et les recoller à l'envers ferait guetter une ligne pour une
autre. Les trois indicateurs, eux, se lisent pareil des deux côtés, et ne
s'écrivent pas : les laisser écrire donnerait à un jeu le pouvoir de se mentir
sur la position du faisceau.

Quel moteur alimente quel écran est décidé par un bit du registre
d'alimentation, non par une convention de ce code.

Le balayage avance ici **ligne par ligne**, non point par point. Le retour
horizontal est donc posé une fois par ligne, et l'indicateur correspondant
n'existe pas : à cette granularité toute lecture se fait à une frontière de
ligne, où le faisceau n'est pas en retour horizontal, et prétendre le contraire
serait inventer une position dans la ligne. C'est suffisant pour tout ce qui
s'accroche au retour vertical ou à une ligne donnée ; ce ne le serait pas pour un
effet qui change un registre au milieu d'une ligne.

Une ligne se dessine **au passage du faisceau**, et non toute la trame d'un coup
à la fin. C'est ce qui distingue un balayage d'une capture : un programme qui
change un décor en cours de trame n'agit que sur les lignes qui suivent, et
dessiner à la fin effacerait cette distinction sans rien dire. L'ordre à
l'intérieur d'une ligne compte pour la même raison — les processeurs ont leur
temps **avant** que la ligne se dessine, parce que le gestionnaire réveillé par
le retour horizontal de la ligne précédente s'exécute pendant celle-ci et prépare
ce qu'elle doit montrer.

Ne sont pas rendus : les décors tournants, les modes étendus, la grande image, le
plan 3D, les sprites tournants, la semi-transparence, la fenêtre par sprite, les
sprites en image directe, les fenêtres, les mélanges, la mosaïque et les palettes
étendues.

La distinction entre « pas dessiné » et « dessiné sans son effet » est tenue au
cas par cas plutôt que par une règle générale. Un sprite tournant ou
semi-transparent n'est pas dessiné, parce que le dessiner comme un sprite
ordinaire donnerait une image plausible et fausse ; un sprite mosaïqué l'est,
parce que l'omettre serait plus faux que de le rendre sans son effet. Les deux
sont comptés.

Deux décisions y sont prises, parce qu'elles engagent le reste du projet et
qu'il vaut mieux les arrêter avant d'écrire un moteur autour :

- **Identité persistée.** La console prend l'identifiant 3. Le 1 reste retiré,
  ayant désigné une seconde entrée Game Boy Color, et ne sera jamais réattribué.
- **Deux écrans dans un tampon unique.** Le contrat vidéo de RavenEmu ne décrit
  qu'un écran. Plutôt que de l'élargir pour une seule console, les deux écrans
  sont empilés : l'écran haut occupe les 192 premières lignes, l'écran bas les
  192 suivantes. L'agencement réel — côte à côte, un seul écran, proportions
  libres — reste à la couche qui affiche, seule à connaître l'appareil.

C'est cette pile que `features/skins` encadre : un skin Delta de Nintendo DS
déclare un écran de 256 sur 384, non de 256 sur 192. La taille est portée par
`DeltaSkinConsole`, aux côtés des touches que la console possède réellement, de
sorte qu'une touche dessinée par un skin mais absente de la console ne soit
jamais pressée. La zone tactile qu'un tel skin décrit emprunte la forme d'un
D-pad sans en être un : elle est reconnue à ses deux axes et rend une
**position**, jamais une direction, plutôt que d'être découpée en neuf cases.
Cette position est donnée en fractions de la zone, non en pixels : `features/skins`
ne connaît la résolution d'aucune console, et c'est `ConsoleType` qui porte la
taille de l'écran tactile et fait la conversion. Un skin qui déclare une telle
zone pour une console qui n'a pas de dalle la voit rester inerte et signalée dans
les entrées ignorées : ce qui décide est ce que le matériel possède, non ce que
le skin déclare.

La console est déclarée à l'application comme les deux autres : un
`ConsoleProvider` publié par son module de moteur, ajouté à la racine de
composition, et rien d'autre à retrouver ailleurs. Deux points lui sont propres.

Le premier est que l'en-tête de cartouche est **lu deux fois** : une fois en C++
pour amorcer, une fois en Kotlin pour indexer. Ce n'est pas un oubli. La
bibliothèque parcourt des fichiers sans jamais construire de moteur, et démarrer
un cœur natif pour lire un titre coûterait une allocation par ROM rencontrée. Le
prix de cette seconde lecture est qu'elle peut diverger de la première ; les deux
sont donc tenues aux mêmes refus, et une vérification de `tools/ci-policy`
compare les identifiants de console de part et d'autre du pont natif — un
identifiant qui différerait ferait construire le moteur d'une autre console, ou
relire un état enregistré pour elle.

Le second est qu'un moteur peut n'avoir **pas encore** de format d'état. C'est le
cas de celui-ci : en figer un avant que la console soit complète promettrait une
compatibilité que le prochain organe ajouté briserait. Le contrat le dit
(`EmulatorCore.supportsSaveState`, et son pendant sur `ConsoleProvider` pour
répondre sans construire de moteur), et l'application ne propose pas ce que le
moteur ne tient pas, au lieu de l'offrir et d'échouer au moment où le joueur
enregistre.

### Les services du programme d'amorçage

Un jeu de la console appelle son programme d'amorçage : attendre le retour
vertical, diviser, décompresser. Ce programme est du code de la console, que
RavenEmu ne fournit pas et ne peut pas fournir. Deux chemins sont pris, et la
distinction est délibérée.

**L'appel logiciel est intercepté avant son vecteur.** Le service est rendu hors
du processeur, à partir de la description publique de son comportement, et
l'exécution reprend à l'instruction suivante. Un appel non couvert redescend au
vecteur du matériel plutôt que d'être inventé.

**L'interruption, elle, ne l'est pas.** Le vecteur porte six instructions ARM
écrites pour RavenEmu, que le processeur émulé exécute vraiment : le changement
de mode, l'empilement et le retour restent ceux du matériel, là où les simuler
aurait demandé de refaire à la main ce que le cœur sait déjà. C'est aussi ce qui
rend la région du programme d'amorçage nécessaire dans les deux cartes mémoire,
en haut de l'espace pour le processeur principal et en bas pour le secondaire.

L'attente d'interruption mérite d'être signalée, parce qu'elle explique une
indirection qui semblerait gratuite. Elle ne se termine pas sur l'interruption
mais sur un **mot d'indicateurs que le gestionnaire du jeu tient à jour**. Sans
cette indirection, une attente du retour vertical se terminerait sur un
débordement de minuterie. La boucle est tenue par le compteur de programme, que
l'appel rembobine sur lui-même avant d'arrêter le processeur : au réveil, le
gestionnaire du jeu s'exécute, rend la main sur l'appel, et l'appel se repose la
question.

### Le bus de cartouche

Une décision y mérite d'être écrite : **l'image de la cartouche n'est pas
recopiée**. Le bus la relit à la demande, par une vue sans propriété. Une
cartouche fait jusqu'à cent vingt-huit mégaoctets, et en doubler la présence en
mémoire pour un téléphone n'aurait pas de sens. La contrepartie est une
contrainte de durée de vie, portée par la documentation de `Machine::boot` : la
fabrique du cœur garde l'image, et c'est elle qui la tient vivante.

Le décodage des registres du bus vit sur l'organe, non dans les deux cartes
mémoire. Les deux processeurs voient ces registres aux mêmes adresses, et deux
copies du décodage dériveraient ; les cartes ne gardent que le routage. C'est
aussi ce qui permet au partage du port, qui décide lequel des deux y accède,
d'être appliqué en un seul endroit.

### Le port série

Trois puces très différentes pendent au même fil, et le partage retenu suit cette
différence : `SerialPort` tient le **protocole du bus**, chaque puce tient le
**sien**, et `Firmware` tient à part le **contenu** de la mémoire de réglages.
Contenu et protocole sont séparés parce qu'ils se trompent différemment : un
contenu faux donne un jeu qui affiche le mauvais nom, un protocole faux donne un
jeu qui n'obtient rien du tout.

Ces registres ne vivent pas dans le fichier des registres partagés, contrairement
à ceux du bus de cartouche : le processeur principal ne les voit pas, et une
seule carte les route. Ce qui est partagé y vit ; ce qui ne l'est pas vit chez
son organe.

La décision qui engage le plus est celle de l'**étalonnage de l'écran tactile**.
La dalle rend des mesures brutes, jamais des pixels, et c'est le jeu qui traduit
avec les deux points enregistrés dans les réglages de la console. RavenEmu écrit
ces deux points lui-même, et fait rendre au convertisseur des mesures construites
pour eux : la conversion d'un jeu retombe alors au pixel près sur l'endroit
touché. Les deux moitiés ne sont donc justes qu'ensemble, et une vérification les
éprouve l'une contre l'autre plutôt que chacune contre une constante.

Le contact traverse ensuite quatre couches sans qu'aucune ne devine ce que fait
la suivante : la zone d'un skin rend une fraction, `ConsoleType` la convertit en
pixels, `EmulationSession` la fait passer par la file du thread d'émulation, et
le cœur la ramène dans l'écran avant de la confier au convertisseur.

Les suites natives (`common`, GB/GBC par sous-système, `gbc`, `gba`, `nds`) doivent
pouvoir être construites directement avec `cmake -S cores`. Le runner
`gb_conformance_runner` reçoit uniquement des ROMs de test externes fournies par
le développeur ou la CI ; aucune ROM de conformité n'est intégrée implicitement.
L'orchestrateur `conformance_manifest.py` valide un manifeste versionné, confine
les chemins sous une racine explicite, contrôle les SHA-256 et produit un
rapport JSON distinguant réussite, échec matériel, timeout, erreur, crash et
artefact optionnel absent. Les URL, licences et politiques de redistribution
sont obligatoires comme garde de provenance, mais aucun téléchargement ou avis
juridique n'est effectué. Son auto-test génère uniquement une ROM RavenEmu
synthétique dans un répertoire temporaire.

## Frontières plateforme

Les cœurs ne pilotent jamais directement un service Android. Par exemple, une
cartouche MBC5 rumble expose uniquement son état de vibration dans le contrat
moteur. `engine/session` transforme cet état en sortie abstraite et
`platform/android/vibration` est seul responsable du `Vibrator` Android.

Le même principe s'applique au port série et au port infrarouge : le modèle
matériel reste dans le cœur. Des implémentations locales déterministes des deux
endpoints relient déjà deux machines dans un même processus ; un futur transport
Android, réseau ou Bluetooth devra implémenter ces contrats sans entrer dans le
cœur. L'agrégateur interne IR présente toujours une seule console au transport,
même si un HuC1 ou un HuC3 ajoute un second transceiver matériel. L'endpoint
doit vivre au moins aussi longtemps que les cœurs connectés et la topologie
externe n'est pas sérialisée dans les états instantanés.
L'écran tactile NDS suit la même frontière : le cœur reçoit un pixel,
et `platform/android/input` traduit les gestes du joueur.

## Bibliothèque ROM

La bibliothèque vit dans `features/library`. Ses modèles persistés, `storageId`,
filtres, index et analyseurs restent indépendants de l'UI Android. Les écrans de
bibliothèque vivent encore dans `app/android` et pourront être extraits séparément.

## Chaîne de build

- Android Gradle Plugin : 9.3.1
- Gradle : 9.5.0
- Kotlin : 2.4.10
- compileSdk : 37
- targetSdk : 35
- NDK : 29.0.14206865
- Java/JVM : 17
- C++ : C++20
- CMake : 3.22.1

La documentation utilisateur détaillée se trouve dans le wiki. Le dossier `docs/`
reste réservé au site officiel.

