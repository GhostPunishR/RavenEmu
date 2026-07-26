# RavenEmu : décisions d'architecture

Ce document décrit les choix structurants de RavenEmu. Il doit rester cohérent
avec le code, les tests et les limites réellement connues.

## AD-01 : organisation en modules Gradle

RavenEmu utilise une architecture multi-module.

| Module | Type | Responsabilité |
|---|---|---|
| `app` | Android | Navigation, écrans, cycle de vie, composition des moteurs |
| `emulation-api` | Kotlin JVM | Contrats communs entre l'application et les moteurs |
| `gameboy-core` | Kotlin JVM | Game Boy et Game Boy Color |
| `gba-core` | Kotlin JVM | Game Boy Advance |
| `rom-library` | Kotlin JVM | Analyse des en-têtes, empreintes et modèle de bibliothèque |
| `storage` | Android | SAF, fichiers `.sav`, états instantanés et index |
| `renderer` | Android | Affichage des framebuffers |
| `input` | Android | Tactile, manettes et dispositions |
| `settings` | Android | Préférences globales et par jeu |

Les modules `emulation-api`, `gameboy-core`, `gba-core` et `rom-library` ne
dépendent pas d'Android. Ils doivent rester testables sur une JVM standard.

## AD-02 : moteurs indépendants

Chaque famille de console possède son propre moteur :

- `gameboy-core` gère la Game Boy et la Game Boy Color ;
- `gba-core` gère la Game Boy Advance.

Un moteur ne doit pas dépendre d'un autre moteur. Les éléments partagés passent
uniquement par les contrats de `emulation-api`.

L'application sélectionne le moteur avec `EmulatorCoreFactory`. Les activités
Android ne doivent pas instancier directement un coeur concret.

## AD-03 : contrat `EmulatorCore`

Le contrat commun fournit notamment :

- le type de console ;
- les dimensions et la cadence vidéo ;
- le format du framebuffer ;
- `loadRom` ;
- `runFrame` ;
- `readAudio` ;
- les entrées logiques ;
- les sauvegardes de cartouche ;
- les états instantanés.

Le coeur exécute une trame à la demande. L'application contrôle le thread,
la cadence, l'audio Android et l'affichage.

Le contrat ne doit contenir aucune classe Android.

## AD-04 : Kotlin JVM avant code natif

Les moteurs sont écrits en Kotlin JVM afin de privilégier :

- la lisibilité ;
- les tests unitaires ;
- le débogage ;
- le partage de code entre environnements.

Une couche native ne doit être envisagée qu'après profilage, avec des mesures
reproductibles et une justification documentée.

## AD-05 : construction sans SDK Android

`settings.gradle.kts` peut limiter le build aux modules JVM lorsqu'aucun SDK
Android n'est détecté.

Cette organisation permet notamment :

```bash
./gradlew jvmTest
```

sur une machine ne disposant pas d'un environnement Android complet.

## AD-06 : threading

`EmulationSession` possède le thread d'émulation.

Le thread principal Android ne doit pas exécuter :

- la boucle CPU ;
- le rendu PPU ;
- le mixage APU ;
- les écritures de sauvegarde ;
- les imports de gros fichiers ;
- les calculs d'empreinte coûteux.

Les échanges avec le moteur utilisent une file de commandes.

Le renderer possède son propre chemin d'affichage. La présentation d'une image
ne doit pas bloquer durablement le thread d'émulation.

## AD-07 : vidéo

### Game Boy monochrome

Le moteur produit quatre niveaux logiques. La couleur visuelle est appliquée
par le renderer avec un profil d'écran.

Les profils DMG, Pocket, Light et noir et blanc sont des simulations visuelles.
Ils ne doivent pas être présentés comme des valeurs numériques officielles de
Nintendo.

### Game Boy Color et Game Boy Advance

Les moteurs produisent directement un framebuffer ARGB 8888.

Le renderer applique ensuite les réglages d'affichage généraux sans connaître
les détails du PPU de chaque console.

Le filtrage par défaut reste en nearest-neighbor.

## AD-08 : audio

Les moteurs produisent du PCM stéréo 16 bits par le contrat commun.

La sortie Android utilise `AudioTrack`. Le moteur ne doit pas dépendre de cette
classe.

L'audio peut participer au cadencement uniquement lorsque l'écriture Android
fonctionne normalement. En cas d'échec, la session doit utiliser une horloge
monotone et éviter toute accélération incontrôlée.

Les chemins audio doivent éviter les allocations par échantillon.

## AD-09 : Game Boy et Game Boy Color

`gameboy-core` partage le CPU et les composants communs entre DMG et CGB.

Le mode CGB est choisi à partir de l'en-tête de la cartouche.

Le moteur comprend notamment :

- CPU LR35902 ;
- mémoire et cartouches ;
- PPU DMG et CGB ;
- APU ;
- timers et interruptions ;
- OAM DMA ;
- contrôleurs MBC ;
- sauvegardes de cartouche ;
- états instantanés.

Les limites de précision doivent rester documentées dans le code et les tests.
Une compatibilité élevée ne doit pas être annoncée uniquement à partir de ROM
synthétiques.

## AD-10 : Game Boy Advance

La Game Boy Advance utilise `gba-core`, avec notamment :

- CPU ARM7TDMI en modes ARM et Thumb ;
- plan mémoire GBA ;
- interruptions ;
- timers ;
- DMA ;
- PPU 240 × 160 ;
- APU PSG et Direct Sound ;
- SRAM, Flash et EEPROM ;
- BIOS HLE interne ;
- états instantanés propres à la GBA.

RavenEmu ne distribue aucun BIOS Nintendo.

Le BIOS HLE est une réimplémentation originale des comportements documentés.
Chaque appel logiciel pris en charge doit posséder des tests dédiés.

La compatibilité avec les jeux commerciaux doit être validée séparément. Une
suite de tests synthétiques ne suffit pas à déclarer le moteur complet.

Les zones encore sensibles comprennent notamment :

- les appels BIOS ;
- les timings mémoire et `WAITCNT` ;
- les transferts DMA ;
- les performances Android ;
- la précision audio ;
- les effets graphiques dépendant du timing.

## AD-11 : sauvegardes de cartouche

Les fichiers `.sav` utilisent un format brut lorsque la mémoire de cartouche le
permet.

RavenEmu prend en charge différentes mémoires selon la console, notamment :

- RAM cartouche Game Boy ;
- RTC MBC3 ;
- SRAM GBA ;
- Flash GBA ;
- EEPROM GBA.

Les écritures doivent être atomiques lorsque le système de fichiers le permet.
Les erreurs d'écriture externe ne doivent pas être masquées.

Lorsqu'un dossier partagé est configuré, la stratégie de priorité entre copie
privée et copie partagée doit être explicite.

## AD-12 : états instantanés

Les états utilisent le conteneur RavenEmu `RVNS`.

Un état doit contenir :

- une version ;
- le type de console ;
- l'empreinte de la ROM ;
- les sections propres au moteur ;
- des longueurs contrôlées ;
- un mécanisme de détection de corruption.

La restauration doit être transactionnelle. Le moteur ne doit pas être modifié
avant validation complète du fichier.

Les états RavenEmu ne sont pas présentés comme compatibles avec d'autres
émulateurs.

## AD-13 : bibliothèque de ROM

L'indexation calcule les empreintes utiles, notamment CRC32, SHA-1 et SHA-256.

La classification repose sur une base locale de métadonnées et d'empreintes.
Aucun contenu ROM n'est distribué.

Une correspondance de titre ne suffit pas à déclarer une ROM officielle.

Les bases importées doivent être analysées hors du thread principal et avec des
lectures bornées.

## AD-14 : stockage Android

L'application utilise le Storage Access Framework pour les fichiers choisis par
l'utilisateur.

Les permissions doivent rester minimales.

RavenEmu ne doit pas :

- téléverser les ROM ;
- téléverser les sauvegardes ;
- ajouter une télémétrie cachée ;
- demander un accès global au stockage sans nécessité documentée.

Les lectures de fichiers doivent être bornées avant allocation complète.

## AD-15 : entrées

Les entrées logiques sont indépendantes de leur source.

Une même touche peut être maintenue par :

- le tactile ;
- une manette ;
- un clavier ;
- une future source externe.

La touche logique reste enfoncée tant qu'au moins une source la maintient.

Toutes les entrées doivent être relâchées lors d'une perte de focus, d'une
pause Android, d'une déconnexion de manette ou d'un arrêt de session.

Les profils tactiles sont séparés par console et orientation.

## AD-16 : contrôles visuels RavenEmu

Le skin par défaut utilise l'identité officielle RavenEmu :

- noir et anthracite ;
- accents violets ;
- bonne lisibilité ;
- logo officiel utilisé avec discrétion ;
- mise à l'échelle adaptée aux écrans Android.

Les contrôles doivent rester fonctionnels avant d'être décoratifs.

Un contrôle ne doit pas pouvoir être placé entièrement hors de l'écran.

## AD-17 : dépendances

Les dépendances existantes doivent rester limitées et justifiées.

Aucune bibliothèque d'émulation tierce ne doit être intégrée.

Toute nouvelle dépendance doit être examinée pour :

- sa licence ;
- sa maintenance ;
- son impact sur la taille ;
- son impact sur la sécurité ;
- son impact sur la confidentialité.

## AD-18 : sécurité

Les fichiers entrants sont considérés comme non fiables.

Le code doit se protéger contre :

- les tailles excessives ;
- les longueurs négatives ou incohérentes ;
- les états tronqués ;
- les bases XML malveillantes ;
- les images trop volumineuses ;
- les erreurs silencieuses de stockage ;
- les secrets ajoutés par erreur au dépôt.

La procédure de signalement est définie dans [SECURITY.md](SECURITY.md).

## AD-19 : contributions

Les contributions sont soumises à la licence MIT du projet.

Elles doivent respecter :

- [CONTRIBUTING.md](CONTRIBUTING.md) ;
- [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) ;
- [SECURITY.md](SECURITY.md).

Une pull request doit inclure les tests, les limites connues et les références
techniques nécessaires à la revue.

## AD-20 : documentation

La documentation doit décrire l'état réel du projet.

Les fonctionnalités expérimentales ne doivent pas être présentées comme
compatibles avec tous les jeux.

Les fichiers du dossier `docs` utilisent une ponctuation simple. Le tiret
cadratin n'y est pas utilisé.

Toute décision structurante nouvelle doit être ajoutée à ce document.
