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
