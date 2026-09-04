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
│   └── gba/
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

Les suites natives (`common`, GB/GBC par sous-système, `gbc`, `gba`) doivent
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
