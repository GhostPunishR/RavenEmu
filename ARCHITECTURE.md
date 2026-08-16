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

`cores/common` porte le contrat `Core`, les primitives binaires et SHA-256.

`cores/gb` porte encore l'implémentation principale commune GB/GBC. Le mode CGB
est sélectionné à partir de l'en-tête de la cartouche et conserve la même identité
persistée que la Game Boy afin de ne pas casser la bibliothèque ou les sauvegardes.

`cores/gbc` est désormais une vraie bibliothèque statique `gbc_raven_core`, avec
ses propres tests matériels. L'extraction est progressive plutôt qu'une copie du
cœur GB : les composants spécifiques déjà déplacés comprennent notamment le
contrôleur de double vitesse et le port infrarouge. Le PPU, les DMA, le port série
et les autres organes restent encore partagés avec l'implémentation GB pendant
leur séparation sous tests de parité.

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

`cores/gba` porte le moteur Game Boy Advance indépendant.

`cores/nds` est une **fondation**, pas encore un moteur. Il porte l'identité de
la console, décode et contrôle l'en-tête de cartouche, publie le contrat vidéo
et audio, exécute les deux processeurs de la console avec leurs jeux
d'instructions et le coprocesseur système du principal, et décode les cartes
mémoire que chacun voit. Toute demande de faire tourner une image est en revanche
refusée par une erreur nommée : un écran noir laisserait croire à une émulation
muette, là où il manque encore la communication entre les deux processeurs et
l'affichage.

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

Les neuf banques vidéo existent et sont atteignables par la fenêtre de
transfert, celle qu'on emprunte pour les remplir. L'aiguillage qui les présente
aux moteurs 2D et 3D viendra avec ces moteurs, seuls à pouvoir dire s'il est
juste ; d'ici là, un accès à ces fenêtres est compté, pas absorbé en silence. Le
BIOS, la cartouche et le port Game Boy Advance ne sont pas décodés non plus,
faute de contenu à leur donner.

Deux décisions y sont prises, parce qu'elles engagent le reste du projet et
qu'il vaut mieux les arrêter avant d'écrire un moteur autour :

- **Identité persistée.** La console prend l'identifiant 3. Le 1 reste retiré,
  ayant désigné une seconde entrée Game Boy Color, et ne sera jamais réattribué.
- **Deux écrans dans un tampon unique.** Le contrat vidéo de RavenEmu ne décrit
  qu'un écran. Plutôt que de l'élargir pour une seule console, les deux écrans
  sont empilés : l'écran haut occupe les 192 premières lignes, l'écran bas les
  192 suivantes. L'agencement réel — côte à côte, un seul écran, proportions
  libres — reste à la couche qui affiche, seule à connaître l'appareil.

Les suites natives (`common`, `gb`, `gbc`, `gba`, `nds`) doivent pouvoir être
construites directement avec `cmake -S cores`.

## Frontières plateforme

Les cœurs ne pilotent jamais directement un service Android. Par exemple, une
cartouche MBC5 rumble expose uniquement son état de vibration dans le contrat
moteur. `engine/session` transforme cet état en sortie abstraite et
`platform/android/vibration` est seul responsable du `Vibrator` Android.

Le même principe s'applique au port série et au port infrarouge : le modèle
matériel reste dans le cœur, tandis qu'une future connexion entre appareils doit
passer par une couche plateforme séparée.

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
