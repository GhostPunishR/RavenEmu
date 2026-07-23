# RavenEmu

[![Android CI](https://github.com/GhostPunishR/RavenEmu/actions/workflows/android.yml/badge.svg)](https://github.com/GhostPunishR/RavenEmu/actions/workflows/android.yml)
[![Licence: MIT](https://img.shields.io/badge/Licence-MIT-green.svg)](LICENSE)

**RavenEmu** est un émulateur Game Boy (DMG) pour Android, au moteur écrit
intégralement en Kotlin à partir de documentation technique publique — sans
code d'émulateur existant, sans cœur tiers, sans BIOS ni contenu protégé.

## Fonctionnalités

### Émulation
- CPU Sharp LR35902 complet (jeu principal + instructions CB), cycles exacts,
  délai EI, bug HALT
- PPU scanline : fond, fenêtre, sprites 8×8/8×16, priorités DMG, STAT/LYC
- **Game Boy Color** : banques VRAM/WRAM, palettes couleur 15 bits, attributs
  de tuiles, priorités, HDMA/GDMA, mode double vitesse ; sortie couleur ARGB
- **Audio 4 canaux** : ondes carrées (balayage, enveloppe), table d'onde,
  bruit LFSR, mixage stéréo, synchronisation audio/vidéo
- Timers au cycle, interruptions, OAM DMA, plan mémoire complet
- Cartouches : ROM seule, MBC1, MBC2, MBC3 (+ horloge temps réel), MBC5
- Sauvegardes `.sav` au format brut compatible (écriture atomique,
  sauvegarde automatique), états instantanés versionnés
- Boucle déterministe sur thread dédié, cadence native 59,7275 Hz,
  avance rapide

### Application
- **Bibliothèque** : dossiers choisis via le sélecteur Android (aucune
  permission globale de stockage), en-têtes de cartouche, recherche, tri,
  vue grille ou liste, actualisation avec détection des fichiers
  ajoutés/déplacés/supprimés
- **Identification** : empreintes CRC32/SHA-1/SHA-256, statuts prudents
  (jamais « officielle » sans correspondance d'empreinte) ; **base de
  références** enrichissable par import de fichiers No-Intro `.dat` ou de
  datasets JSON (métadonnées uniquement, jamais de ROM)
- **Pochettes** : image choisie, image voisine de la ROM, dossier de
  pochettes (par nom ou par empreinte), jaquette générée sinon — rien n'est
  téléchargé ni distribué
- **Commandes tactiles** : disposition entièrement modifiable (position,
  taille, opacité, visibilité), profils portrait/paysage et par jeu,
  multi-touch avec diagonales, vibrations, manettes physiques
- **Affichage** : le moteur ne produit que les **quatre niveaux monochromes**
  `0..3` ; le renderer applique un **profil d'écran** (simulation LCD
  calibrable : Game Boy DMG, Pocket, Light éteint/allumé, Noir et blanc),
  changeable à chaud. **Réglages avancés** : luminosité, contraste et
  correction colorimétrique LCD (simulation, surtout utile en Game Boy Color),
  appliqués en post-traitement sans effet par défaut. Ratio natif conservé,
  mise à l'échelle entière optionnelle, nearest-neighbor, adaptation aux
  encoches et à toutes tailles d'écran
- **Confidentialité** : aucun réseau, aucune télémétrie, permissions
  minimales

## Installation

À chaque mise à jour de `main`, la CI publie un APK de test :
**Actions → dernier run → artefact `ravenemu-debug-apk`** (`app-debug.apk`,
installable directement). Un APK Release signé et un **App Bundle `.aab`**
(pour le Play Store) sont produits lorsque les secrets de signature sont
configurés (voir [docs/BUILD.md](docs/BUILD.md)).

RavenEmu ne fournit **aucune ROM**. Utilisez uniquement des copies de jeux
que vous possédez ou des homebrews librement distribués.

## Compilation

```bash
# Tests du moteur — aucun SDK Android requis
./gradlew test

# APK Debug — SDK Android requis (compileSdk 35)
./gradlew assembleDebug
```

Détails, signature Release et CI : [docs/BUILD.md](docs/BUILD.md).

## Architecture

| Module | Type | Rôle |
|---|---|---|
| `app` | Application Android | Écrans, navigation, session d'émulation |
| `emulation-api` | Kotlin JVM | Interfaces communes app ↔ moteurs |
| `gameboy-core` | Kotlin JVM | Moteur Game Boy (CPU, PPU, APU, MBC…) |
| `gba-core` | Kotlin JVM | Moteur Game Boy Advance (ARM7TDMI) — en construction |
| `rom-library` | Kotlin JVM | En-têtes, empreintes, identification, index |
| `storage` | Bibliothèque Android | SAF, `.sav`, états, pochettes |
| `renderer` | Bibliothèque Android | Affichage du framebuffer |
| `input` | Bibliothèque Android | Tactile, éditeur, manettes |
| `settings` | Bibliothèque Android | Préférences, profils d'écran |

Les moteurs ne dépendent pas d'Android et se testent sur JVM. L'ajout d'une
console se fait par un nouveau module implémentant `emulation-api`, sans
toucher aux moteurs existants — c'est ainsi que la prise en charge de la
Game Boy Advance (`gba-core`, moteur ARM7TDMI) est construite, sans modifier
`gameboy-core`. Décisions détaillées :
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · spécification :
[docs/CAHIER_DES_CHARGES.md](docs/CAHIER_DES_CHARGES.md) · contribution :
[docs/CONTRIBUTING.md](docs/CONTRIBUTING.md).

## Limites connues

- PPU sans effets mid-scanline (durée de mode 3 fixe) — sans incidence sur
  la grande majorité des jeux DMG
- Comportements obscurs de l'APU non émulés (mode « zombie », corruption de
  Wave RAM)
- Multicarts MBC1M et câble link non pris en charge
- Game Boy Color pris en charge ; limites : timing HDMA HBlank simplifié
  (un bloc par HBlank, sans coût cycle précis), séquenceur APU non doublé en
  double vitesse, registre OPRI non émulé, sans correction colorimétrique LCD
- États instantanés propres à RavenEmu (format `RVNS` versionné)

## Feuille de route

- [x] Compatibilité Game Boy Color
- [x] Base locale d'empreintes de référence enrichissable (import No-Intro/JSON)
- [x] Réglages d'affichage avancés (contraste, luminosité, correction LCD)
- [ ] Tests de compatibilité étendus sur matériel réel
- [x] Android App Bundle (`.aab`)
- [ ] Game Boy Advance (`gba-core`, moteur ARM7TDMI) — **en cours** : squelette,
  en-tête, bus mémoire, sous-ensemble ARM/Thumb, PPU couleur unie ; audio,
  vidéo complète, entrées, sauvegardes et intégration à venir

## Licence

Code sous [licence MIT](LICENSE) — © 2026 GhostPunishR.

« Game Boy » est une marque de Nintendo. RavenEmu n'est ni affilié à, ni
approuvé par Nintendo, et ne contient ni ne distribue aucun BIOS, jeu ou
contenu protégé.
