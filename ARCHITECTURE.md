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
et audio, et exécute les deux jeux d'instructions du processeur principal ainsi
que son coprocesseur système. Toute demande de faire tourner une image est en
revanche refusée par une erreur nommée : un écran noir laisserait croire à une
émulation muette, là où il manque encore la carte mémoire, le second processeur
et l'affichage.

`cores/nds/src/cpu` tient ce processeur. Il ne connaît pas la carte mémoire de
la console : il passe par une frontière `Bus` abstraite, ce qui permet de
l'éprouver contre une simple mémoire de test — sans cartouche, sans banques
vidéo, sans ARM7 — et donc de distinguer une faute du processeur d'une faute de
la machine autour.

Les deux jeux d'instructions y sont complets : ARM 32 bits, avec `CLZ`, `BLX` et
l'arithmétique saturante, et Thumb 16 bits, avec le passage d'un jeu à l'autre
dans les deux sens. Un jeu de la console alterne sans cesse entre eux — le code
compact en Thumb, les gestionnaires d'interruption en ARM — si bien qu'un cœur
qui n'en connaîtrait qu'un ne ferait rien tourner. Les deux jeux partagent le
décaleur, les indicateurs et le banc de registres ; ils diffèrent surtout sur un
point, que le code isole : Thumb écrit ses indicateurs d'office, là où ARM
demande un bit `S` explicite.

Le coprocesseur système CP15 les accompagne, et c'est par lui que le cœur cesse
d'être un simple exécuteur d'instructions. Trois choses y sont observables
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
temporelle dépendant en outre de la carte mémoire, qui n'existe pas encore.

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
