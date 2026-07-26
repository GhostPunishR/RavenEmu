# RavenEmu

[![Android CI](https://github.com/GhostPunishR/RavenEmu/actions/workflows/android.yml/badge.svg)](https://github.com/GhostPunishR/RavenEmu/actions/workflows/android.yml)
[![Wiki](https://img.shields.io/badge/Documentation-Wiki-5b21b6.svg?logo=github&logoColor=white)](https://github.com/GhostPunishR/RavenEmu/wiki)
[![Site officiel](https://img.shields.io/badge/Site-RavenEmu-7c3aed.svg?logo=githubpages&logoColor=white)](https://ghostpunishr.github.io/RavenEmu/)
[![APK Debug](https://img.shields.io/badge/APK-Debug-a855f7.svg?logo=android&logoColor=white)](https://github.com/GhostPunishR/RavenEmu/releases/download/debug-latest/RavenEmu-debug.apk)
[![Licence MIT](https://img.shields.io/badge/Licence-MIT-22c55e.svg?logo=opensourceinitiative&logoColor=white)](LICENSE)

**RavenEmu** est un émulateur Game Boy, Game Boy Color et Game Boy Advance pour Android.

Les moteurs sont écrits en Kotlin pour le projet à partir de documentation technique publique. RavenEmu n'intègre aucun cœur d'émulation tiers et ne fournit aucun BIOS, aucune ROM ni aucun contenu protégé.

## Télécharger

La CI publie automatiquement un APK de test après chaque construction réussie de `main`.

### [Télécharger le dernier APK Debug](https://github.com/GhostPunishR/RavenEmu/releases/download/debug-latest/RavenEmu-debug.apk)

L'empreinte SHA-256 est disponible dans le fichier [RavenEmu-debug.apk.sha256](https://github.com/GhostPunishR/RavenEmu/releases/download/debug-latest/RavenEmu-debug.apk.sha256).

L'APK Debug porte l'identifiant `com.ravenemu.app.debug` et utilise une signature de développement. Il sert aux essais et ne remplace pas une version Release signée.

RavenEmu ne fournit aucun jeu. Utilisez uniquement des copies que vous êtes autorisé à employer ou des homebrews librement distribués.

## État du projet

| Console | État | Capacités principales |
|---|---|---|
| Game Boy | Avancé | CPU LR35902, vidéo, audio, timers, DMA et principaux MBC |
| Game Boy Color | Intégré | Couleur, banques mémoire, palettes, HDMA et double vitesse |
| Game Boy Advance | Expérimental | ARM7TDMI, vidéo, audio, DMA, sauvegardes et BIOS HLE |

Le moteur Game Boy Advance démarre désormais des jeux commerciaux testés, mais sa compatibilité et ses performances restent à valider jeu par jeu sur Android.

## Fonctionnalités

### Game Boy et Game Boy Color

- CPU Sharp LR35902 avec instructions principales et CB
- Gestion du délai `EI` et du comportement `HALT`
- Fond, fenêtre, sprites 8 x 8 et 8 x 16, priorités et interruptions vidéo
- Audio à quatre canaux avec mixage stéréo
- Timers, interruptions et DMA OAM
- ROM seule, MBC1, MBC2, MBC3 avec horloge et MBC5
- Banques VRAM et WRAM, palettes 15 bits, attributs de tuiles, HDMA et double vitesse pour la Game Boy Color

### Game Boy Advance

- CPU ARM7TDMI avec registres banqués, exceptions et modes ARM et Thumb
- Modes vidéo bitmap 3, 4 et 5
- Arrière-plans texte et affines
- Sprites normaux et affines, fenêtres et effets de couleur
- Interruptions, quatre timers et quatre canaux DMA
- Temps d'attente mémoire, accès séquentiels et préchargement Game Pak
- BIOS HLE avec copies mémoire, calculs, attentes d'interruption et décompression
- Audio PSG et Direct Sound avec FIFO et DMA
- SRAM, Flash 64 ou 128 Kio et EEPROM
- Diagnostics de performance dans l'APK Debug

### Application Android

- Bibliothèque locale avec recherche, tri, filtres et vues grille ou liste
- Accès aux dossiers par le sélecteur Android, sans permission globale de stockage
- Identification par CRC32, SHA-1 et SHA-256
- Import de métadonnées No-Intro `.dat` ou JSON, sans téléchargement de ROM
- Pochettes choisies par l'utilisateur ou générées localement
- Commandes tactiles configurables par console, orientation et jeu
- Manettes physiques, multitouch, diagonales et vibrations
- Profils d'écran Game Boy et réglages de luminosité, contraste et couleur
- Ratio natif, mise à l'échelle entière et rendu nearest-neighbor
- Sauvegardes `.sav` automatiques et états instantanés versionnés
- Avance rapide et surcouche de performance
- Aucun réseau et aucune télémétrie dans l'application

## Architecture

| Module | Type | Responsabilité |
|---|---|---|
| `app` | Android | Écrans, navigation et session d'émulation |
| `emulation-api` | Kotlin/JVM | Contrats communs entre l'application et les moteurs |
| `gameboy-core` | Kotlin/JVM | Moteur Game Boy et Game Boy Color |
| `gba-core` | Kotlin/JVM | Moteur Game Boy Advance |
| `rom-library` | Kotlin/JVM | En-têtes, empreintes, identification et index |
| `storage` | Android | Dossiers, sauvegardes, états et pochettes |
| `renderer` | Android | Affichage du framebuffer |
| `input` | Android | Commandes tactiles et manettes |
| `settings` | Android | Préférences et profils |

Les moteurs ne dépendent pas d'Android et peuvent être testés sur une JVM de bureau. Ils restent indépendants les uns des autres. Une nouvelle console peut être ajoutée dans un module séparé qui implémente `emulation-api`.

## Compiler

### Prérequis

- JDK 21 recommandé
- Gradle Wrapper fourni
- SDK Android avec `compileSdk 35` pour construire l'application

Les modules Kotlin/JVM restent testables sans SDK Android.

```bash
git clone https://github.com/GhostPunishR/RavenEmu.git
cd RavenEmu

# Tests
./gradlew test

# Validation Android
./gradlew lint

# APK Debug
./gradlew assembleDebug
```

L'APK est produit dans `app/build/outputs/apk/debug/`.

## Documentation

- [Wiki RavenEmu](https://github.com/GhostPunishR/RavenEmu/wiki)
- [Site officiel](https://ghostpunishr.github.io/RavenEmu/)
- [Installation](https://github.com/GhostPunishR/RavenEmu/wiki/Installation)
- [Premiers pas](https://github.com/GhostPunishR/RavenEmu/wiki/Premiers-pas)
- [Consoles et compatibilité](https://github.com/GhostPunishR/RavenEmu/wiki/Consoles-et-compatibilite)
- [Compilation](https://github.com/GhostPunishR/RavenEmu/wiki/Compilation)
- [Architecture](https://github.com/GhostPunishR/RavenEmu/wiki/Architecture)
- [Dépannage](https://github.com/GhostPunishR/RavenEmu/wiki/Depannage)

## Limites actuelles

### Game Boy et Game Boy Color

- Pas de câble link
- Pas de multicartouches MBC1M
- Effets modifiant le rendu au milieu d'une ligne non reproduits
- Certains comportements audio rares restent simplifiés
- Timing HDMA HBlank et séquenceur audio en double vitesse encore simplifiés

### Game Boy Advance

- Compatibilité et performances encore expérimentales
- Mosaïque et effets vidéo au milieu d'une ligne absents
- DMA de capture vidéo absent
- Interruptions clavier et série absentes
- Certains détails audio, dont `SOUNDBIAS`, restent simplifiés
- Chargement d'un BIOS externe non pris en charge

Les états instantanés utilisent le format versionné `RVNS`. Ils ne sont pas compatibles avec les états d'autres émulateurs et une évolution du format peut rendre un ancien état illisible.

## Contribuer

Les contributions de code, de documentation et de validation sont bienvenues.

Avant une modification importante:

1. consultez les [issues](https://github.com/GhostPunishR/RavenEmu/issues);
2. décrivez clairement le comportement visé;
3. conservez les frontières entre modules;
4. utilisez uniquement de la documentation technique publique;
5. ajoutez des tests synthétiques reproductibles;
6. exécutez les tâches Gradle adaptées;
7. documentez les limites restantes.

Le code d'un autre émulateur ne doit pas être copié, traduit ou adapté dans RavenEmu. Les tests ne doivent contenir aucune ROM commerciale ni aucun BIOS protégé.

Pour une vulnérabilité, utilisez un [avis de sécurité privé](https://github.com/GhostPunishR/RavenEmu/security/advisories/new) et ne publiez pas les détails dans une issue.

## Licence et marques

RavenEmu est distribué sous [licence MIT](LICENSE). Copyright 2026 GhostPunishR.

Game Boy, Game Boy Color et Game Boy Advance sont des marques de Nintendo. RavenEmu n'est ni affilié à Nintendo, ni approuvé par Nintendo.
