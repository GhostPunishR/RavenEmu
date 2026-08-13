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

Le format d'état GB/GBC est en version 8. Il sérialise la phase du séquenceur
APU dérivée de `DIV`, le pixel `WX` éventuellement armé, le FIFO OBJ et chaque
phase intermédiaire de son fetch. Les portes du bus vidéo sont dérivées de ces
phases sérialisées et ne constituent pas un état redondant. Les versions 7 et
antérieures sont refusées au lieu d'être chargées partiellement ; les phases
PPU incohérentes d'un état version 8 sont également rejetées explicitement.

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

`cores/gba` porte le moteur Game Boy Advance indépendant.

Les suites natives (`common`, GB/GBC par sous-système, `gbc`, `gba`) doivent
pouvoir être construites directement avec `cmake -S cores`. Le runner
`gb_conformance_runner` reçoit uniquement des ROMs de test externes fournies par
le développeur ou la CI ; aucune ROM de conformité n'est intégrée implicitement.

## Frontières plateforme

Les cœurs ne pilotent jamais directement un service Android. Par exemple, une
cartouche MBC5 rumble expose uniquement son état de vibration dans le contrat
moteur. `engine/session` transforme cet état en sortie abstraite et
`platform/android/vibration` est seul responsable du `Vibrator` Android.

Le même principe s'applique au port série et au port infrarouge : le modèle
matériel reste dans le cœur. Des implémentations locales déterministes des deux
endpoints relient déjà deux machines dans un même processus ; un futur transport
Android, réseau ou Bluetooth devra implémenter ces contrats sans entrer dans le
cœur. L'endpoint doit vivre au moins aussi longtemps que les cœurs connectés et
la topologie externe n'est pas sérialisée dans les états instantanés.

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
