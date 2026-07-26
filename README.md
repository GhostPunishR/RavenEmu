# RavenEmu

[![Android CI](https://github.com/GhostPunishR/RavenEmu/actions/workflows/android.yml/badge.svg)](https://github.com/GhostPunishR/RavenEmu/actions/workflows/android.yml)
[![Licence: MIT](https://img.shields.io/badge/Licence-MIT-green.svg)](LICENSE)
[![Site RavenEmu](https://img.shields.io/badge/Site-RavenEmu-a855f7.svg)](https://ghostpunishr.github.io/RavenEmu/)

**RavenEmu** est un émulateur **Game Boy, Game Boy Color et Game Boy Advance**
pour Android, aux moteurs écrits intégralement en Kotlin à partir de
documentation technique publique — sans code d'émulateur existant, sans cœur
tiers, sans BIOS ni contenu protégé.

## Fonctionnalités

### Game Boy / Game Boy Color
- CPU Sharp LR35902 complet (jeu principal + instructions CB), cycles exacts,
  délai EI, bug HALT
- PPU scanline : fond, fenêtre, sprites 8×8/8×16, priorités DMG, STAT/LYC
- **Game Boy Color** : banques VRAM/WRAM, palettes couleur 15 bits, attributs
  de tuiles, priorités, HDMA/GDMA, mode double vitesse ; sortie couleur ARGB
- **Audio 4 canaux** : ondes carrées (balayage, enveloppe), table d'onde,
  bruit LFSR, mixage stéréo, synchronisation audio/vidéo
- Timers au cycle, interruptions, OAM DMA, plan mémoire complet
- Cartouches : ROM seule, MBC1, MBC2, MBC3 (+ horloge temps réel), MBC5

### Game Boy Advance
- **CPU ARM7TDMI** : registres banqués, modes processeur, `CPSR`/`SPSR`,
  exceptions ; jeu d'instructions ARM et Thumb quasi complet (traitement de
  données et barrel shifter, `LDM`/`STM`, transferts demi-mot et signés,
  multiplications longues, `SWP`, `SWI`, `PUSH`/`POP`)
- **Vidéo** : modes bitmap 3/4/5, arrière-plans texte et **affines**, sprites
  normaux et affines, **fenêtres**, **effets de couleur** (mélange alpha,
  éclaircissement, assombrissement), rendu ligne par ligne
- **Interruptions, timers et DMA** : `IE`/`IF`/`IME`, quatre timers (cascade,
  prédiviseur), quatre canaux DMA (immédiat, VBlank, HBlank, son)
- **BIOS HLE** écrit pour RavenEmu — gestionnaire d'interruption et appels
  `SWI` (division, racine, copies mémoire, mise en veille) : **aucun BIOS
  Nintendo n'est requis ni fourni**
- **Audio** : quatre canaux PSG hérités et deux canaux **Direct Sound**
  alimentés par FIFO et DMA
- **Sauvegardes** : SRAM, Flash 64/128 Kio et EEPROM, détectées dans la ROM et
  **remplaçables par jeu**, au format `.sav` brut

### Commun aux consoles
- Sauvegardes `.sav` au format brut compatible (écriture atomique, sauvegarde
  automatique), états instantanés versionnés
- Boucle déterministe sur thread dédié, cadence native, avance rapide
- Sélection automatique du moteur selon la console de la ROM

### Application
- **Bibliothèque** : dossiers choisis via le sélecteur Android (aucune
  permission globale de stockage), en-têtes de cartouche, recherche, tri,
  **filtre par console**, vue grille ou liste, actualisation avec détection des
  fichiers ajoutés/déplacés/supprimés
- **Identification** : empreintes CRC32/SHA-1/SHA-256, statuts prudents
  (jamais « officielle » sans correspondance d'empreinte) ; **base de
  références** enrichissable par import de fichiers No-Intro `.dat` ou de
  datasets JSON (métadonnées uniquement, jamais de ROM)
- **Pochettes** : image choisie, image voisine de la ROM, dossier de
  pochettes (par nom ou par empreinte), jaquette générée sinon — rien n'est
  téléchargé ni distribué
- **Commandes tactiles** : disposition entièrement modifiable (position,
  taille, opacité, visibilité), profils portrait/paysage **par console** et par
  jeu, gâchettes `L`/`R` sur Game Boy Advance, multi-touch avec diagonales,
  vibrations, manettes physiques
- **Affichage** : en monochrome, le moteur ne produit que les **quatre niveaux**
  `0..3` et le renderer applique un **profil d'écran** (simulation LCD
  calibrable : Game Boy DMG, Pocket, Light éteint/allumé, Noir et blanc),
  changeable à chaud ; les sorties couleur (Game Boy Color, Game Boy Advance)
  sont affichées telles quelles. **Réglages avancés** : luminosité, contraste et
  correction colorimétrique LCD, appliqués en post-traitement sans effet par
  défaut. Ratio natif conservé, mise à l'échelle entière optionnelle,
  nearest-neighbor, adaptation aux encoches et à toutes tailles d'écran
- **Confidentialité** : aucun réseau, aucune télémétrie, permissions
  minimales

## Installation

À chaque mise à jour de `main`, la CI publie un APK de test :
**[télécharger le dernier APK debug](https://github.com/GhostPunishR/RavenEmu/releases/download/debug-latest/RavenEmu-debug.apk)**.
La préversion `debug-latest` et son empreinte SHA-256 sont remplacées après
chaque build réussi de `main`. L'artefact `ravenemu-debug-apk` reste également
disponible dans le run GitHub Actions correspondant.

Cet APK porte l'identifiant `com.ravenemu.app.debug` et utilise la signature
debug Android. Il est destiné aux tests et ne remplace pas une version Release
signée. Un APK Release signé et un **App Bundle `.aab`** (pour le Play Store)
sont produits lorsque les secrets de signature sont configurés. Les commandes
sont regroupées sur le
[site RavenEmu](https://ghostpunishr.github.io/RavenEmu/#demarrer).

RavenEmu ne fournit **aucune ROM ni aucun BIOS**. Utilisez uniquement des copies
de jeux que vous possédez ou des homebrews librement distribués.

## Compilation

```bash
# Tests des moteurs — aucun SDK Android requis
./gradlew test

# APK Debug — SDK Android requis (compileSdk 35)
./gradlew assembleDebug
```

Détails, contribution, architecture et sécurité :
[site RavenEmu](https://ghostpunishr.github.io/RavenEmu/).

## Architecture

| Module | Type | Rôle |
|---|---|---|
| `app` | Application Android | Écrans, navigation, session d'émulation |
| `emulation-api` | Kotlin JVM | Interfaces communes app ↔ moteurs |
| `gameboy-core` | Kotlin JVM | Moteur Game Boy / Color (CPU, PPU, APU, MBC…) |
| `gba-core` | Kotlin JVM | Moteur Game Boy Advance (ARM7TDMI, PPU, APU, DMA…) |
| `rom-library` | Kotlin JVM | En-têtes, empreintes, identification, index |
| `storage` | Bibliothèque Android | SAF, `.sav`, états, pochettes |
| `renderer` | Bibliothèque Android | Affichage du framebuffer |
| `input` | Bibliothèque Android | Tactile, éditeur, manettes |
| `settings` | Bibliothèque Android | Préférences, profils d'écran |

Les moteurs ne dépendent pas d'Android et se testent sur JVM. Ils sont
**indépendants les uns des autres** : `gba-core` ne référence pas
`gameboy-core`. L'ajout d'une console se fait par un nouveau module implémentant
`emulation-api`, sans toucher aux moteurs existants ; l'application sélectionne
le moteur par une fabrique et n'en instancie aucun directement. Les catégories
techniques sont présentées sur le
[site RavenEmu](https://ghostpunishr.github.io/RavenEmu/#architecture).

## Limites connues

### Game Boy / Game Boy Color
- PPU sans effets mid-scanline (durée de mode 3 fixe) — sans incidence sur
  la grande majorité des jeux DMG
- Comportements obscurs de l'APU non émulés (mode « zombie », corruption de
  Wave RAM)
- Multicarts MBC1M et câble link non pris en charge
- Game Boy Color : timing HDMA HBlank simplifié (un bloc par HBlank, sans coût
  cycle précis), séquenceur APU non doublé en double vitesse, registre OPRI non
  émulé, sans correction colorimétrique LCD

### Game Boy Advance
- **Le moteur n'a été éprouvé que sur des ROM synthétiques internes : la
  compatibilité avec les jeux du commerce reste à valider sur matériel réel.**
- Mosaïque et effets mid-scanline non émulés
- Temps d'attente mémoire (wait states) non émulés, comptage de cycles
  approximatif
- BIOS : seuls les appels `SWI` courants sont implémentés ; le BIOS fourni par
  l'utilisateur n'est pas encore pris en charge
- DMA de capture vidéo, interruptions clavier et série absentes
- Audio : RAM d'onde en banque unique, `SOUNDBIAS` non émulé

### Commun
- États instantanés propres à RavenEmu (format `RVNS` versionné), jamais
  présentés comme compatibles avec d'autres émulateurs

## Feuille de route

- [x] Compatibilité Game Boy Color
- [x] Base locale d'empreintes de référence enrichissable (import No-Intro/JSON)
- [x] Réglages d'affichage avancés (contraste, luminosité, correction LCD)
- [x] Android App Bundle (`.aab`)
- [x] Moteur Game Boy Advance (`gba-core`) : CPU, vidéo, audio, sauvegardes,
  BIOS HLE et intégration à l'interface
- [ ] Tests de compatibilité étendus sur matériel réel (Game Boy et surtout
  Game Boy Advance)
- [ ] Précision Game Boy Advance : temps d'attente mémoire, mosaïque, effets
  mid-scanline
- [ ] BIOS Game Boy Advance fourni par l'utilisateur (validé par taille et
  empreinte)

## Licence

Code sous [licence MIT](LICENSE) — © 2026 GhostPunishR.

« Game Boy » et « Game Boy Advance » sont des marques de Nintendo. RavenEmu
n'est ni affilié à, ni approuvé par Nintendo, et ne contient ni ne distribue
aucun BIOS, jeu ou contenu protégé.
