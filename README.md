# RavenEmu

[![Android CI](https://github.com/GhostPunishR/RavenEmu/actions/workflows/android.yml/badge.svg)](https://github.com/GhostPunishR/RavenEmu/actions/workflows/android.yml)
[![Wiki](https://img.shields.io/badge/Documentation-Wiki-5b21b6.svg?logo=github&logoColor=white)](https://github.com/GhostPunishR/RavenEmu/wiki)
[![Site officiel](https://img.shields.io/badge/Site-RavenEmu-7c3aed.svg?logo=githubpages&logoColor=white)](https://ghostpunishr.github.io/RavenEmu/)
[![APK Test](https://img.shields.io/badge/APK-Test-a855f7.svg?logo=android&logoColor=white)](https://github.com/GhostPunishR/RavenEmu/releases/download/test-latest/RavenEmu-test.apk)
[![Licence MIT](https://img.shields.io/badge/Licence-MIT-22c55e.svg?logo=opensourceinitiative&logoColor=white)](LICENSE)

**RavenEmu** est un émulateur Game Boy, Game Boy Color et Game Boy Advance pour Android.

Les moteurs d'exécution sont écrits en C++20 pour le projet à partir de documentation technique publique, puis exposés à l'application par une frontière JNI minimale. RavenEmu n'intègre aucun cœur d'émulation tiers et ne fournit aucun BIOS, aucune ROM ni aucun contenu protégé.

## Télécharger

La CI publie automatiquement un APK de test après chaque construction réussie de `main`.

### [Télécharger le dernier APK Test](https://github.com/GhostPunishR/RavenEmu/releases/download/test-latest/RavenEmu-test.apk)

L'empreinte SHA-256 est disponible dans le fichier [RavenEmu-test.apk.sha256](https://github.com/GhostPunishR/RavenEmu/releases/download/test-latest/RavenEmu-test.apk.sha256).

L'APK Test conserve les diagnostics et laisse Android optimiser le moteur. Il sert aux essais et ne remplace pas une version Release. Le tag `test-latest` est mobile : le lien reste le même, le fichier change à chaque construction de `main`.

### Trois constructions, trois usages

| Construction | Package | Signature | Publiée | Pour quoi |
|---|---|---|---|---|
| **Test** | `com.ravenemu.app.profil` | Clé Test dédiée, stable | Oui, `test-latest` | Essais et mesures de compatibilité |
| Debug | `com.ravenemu.app.debug` | Clé de débogage locale | Non | Développement, avec débogueur |
| Release | `com.ravenemu.app` | Clé Release, distincte de la clé Test | Sur tag `v*` | Version stable |

Les trois s'installent côte à côte : leurs identifiants d'application diffèrent. L'APK Test est signé par une **clé dédiée**, jamais celle des versions Release — un APK Test compromis ne peut donc pas se faire passer pour une mise à jour de l'application publiée.

### Vérifier ce que vous installez

L'empreinte du certificat de signature est publiée dans les notes de chaque préversion et **reste identique** d'une version Test à l'autre. C'est ce qui permet de mettre à jour l'application sans la désinstaller.

```bash
sha256sum -c RavenEmu-test.apk.sha256
apksigner verify --print-certs RavenEmu-test.apk
```

L'empreinte attendue est :

```
c439aed3f5210f88d92f435f949614cacbb4105ed7967a246abfa051d59feee1
```

Si `apksigner` en affiche une autre, n'installez pas : soit la clé du projet a tourné — ce qui serait annoncé ici —, soit l'APK ne vient pas de ce dépôt.

Cette empreinte est aussi rappelée dans les notes de chaque préversion, avec l'empreinte du fichier et le commit qui l'a produit.

### Les avertissements d'Android sont normaux

Android signale toute installation faite hors du Play Store, et Play Protect ajoute que le certificat lui est inconnu. Ces messages ne veulent pas dire qu'un problème a été trouvé dans le fichier : ils veulent dire que Google n'a rien à en dire. La vérification ci-dessus répond à cette absence d'information.

Elle n'y répond que pour l'origine du fichier : elle prouve qui l'a signé, pas que son contenu est inoffensif. Un avertissement annonçant une **application dangereuse** ou **nuisible** est au contraire un verdict de détection, qu'il ne faut jamais écarter sur la foi d'une empreinte. Voir [Installation](https://github.com/GhostPunishR/RavenEmu/wiki/Installation) pour le détail.

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
- Diagnostics de performance dans l'APK Test

### Application Android

- Bibliothèque locale avec recherche, tri, filtres et vues grille ou liste
- Accès aux dossiers par le sélecteur Android, sans permission globale de stockage
- Identification par CRC32, SHA-1 et SHA-256
- Badge d'intégrité calculé depuis les sommes de contrôle de la cartouche, sans base extérieure
- Pochettes choisies par l'utilisateur ou générées localement
- Commandes tactiles configurables par console, orientation et jeu
- Import local de panneaux de commandes au format `.deltaskin` (PDF) pour GB/GBC et GBA
  en portrait. Compatibilité de format uniquement : RavenEmu n'est pas affilié à Delta
  et ne distribue aucun skin.
- Manettes physiques, multitouch, diagonales et vibrations
- Profils d'écran Game Boy et réglages de luminosité, contraste et couleur
- Ratio natif, mise à l'échelle entière et rendu nearest-neighbor
- Sauvegardes `.sav` automatiques et états instantanés versionnés
- Avance rapide et surcouche de performance
- Aucun réseau et aucune télémétrie dans l'application

## Architecture

| Module | Type | Responsabilité |
|---|---|---|
| `android/app` | Android | Écrans, navigation et session d'émulation |
| `core/emulation-api` | Kotlin/JVM | Contrats communs entre l'application et les moteurs |
| `native-core` | C++20 | Moteurs Game Boy/Game Boy Color et Game Boy Advance |
| `core/native-bridge` | Java/JNI | Frontière primitive entre les adaptateurs JVM et C++ |
| `core/deltaskin` | Kotlin/JVM | Validation, manifeste, stockage et géométrie du format `.deltaskin` |
| `core/gameboy-core` | Kotlin/JVM | Adaptateur et métadonnées Game Boy/Game Boy Color |
| `core/gba-core` | Kotlin/JVM | Adaptateur et métadonnées Game Boy Advance |
| `core/rom-library` | Kotlin/JVM | En-têtes, empreintes, identification et index |
| `android/storage` | Android | Dossiers, sauvegardes, états et pochettes |
| `android/renderer` | Android | Affichage du framebuffer |
| `android/input` | Android | Commandes tactiles et manettes |
| `android/settings` | Android | Préférences et profils |

Les moteurs C++ ne dépendent pas d'Android et possèdent des tests natifs sur l'hôte. Les adaptateurs JVM gardent le contrat `emulation-api`, de sorte que la session Android, le renderer, les entrées et DeltaSkin ne connaissent pas JNI. Les deux cœurs restent indépendants l'un de l'autre.

## Compiler

### Prérequis

- JDK 21 recommandé
- Gradle Wrapper fourni
- SDK Android avec `compileSdk 35` pour construire l'application
- Android NDK `27.2.12479018` et CMake `3.22.1`

Les modules JVM restent testables sans SDK Android. Les moteurs C++ peuvent aussi être validés directement avec un compilateur C++20 et CMake.

```bash
git clone https://github.com/GhostPunishR/RavenEmu.git
cd RavenEmu

# Tests
./gradlew test

# Tests natifs des deux cœurs
cmake -S native-core -B build/native-host -DRAVENEMU_BUILD_TESTS=ON
cmake --build build/native-host --parallel
ctest --test-dir build/native-host --output-on-failure

# Validation Android
./gradlew lint

# APK Test optimisé
./gradlew assembleProfil

# APK Debug local pour le développement
./gradlew assembleDebug
```

L'APK Test est produit dans `android/app/build/outputs/apk/profil/`, l'APK Debug dans `android/app/build/outputs/apk/debug/`.

Construire l'APK Test en local ne demande **aucun secret** : sans la clé Test du projet, la construction retombe sur la clé de débogage. L'APK obtenu est utilisable pour soi, mais la CI refuse de publier un APK signé autrement que par la clé dédiée. Les détails sont dans [RELEASING.md](RELEASING.md).

## Documentation

- [Wiki RavenEmu](https://github.com/GhostPunishR/RavenEmu/wiki)
- [Site officiel](https://ghostpunishr.github.io/RavenEmu/)
- [Installation](https://github.com/GhostPunishR/RavenEmu/wiki/Installation)
- [Premiers pas](https://github.com/GhostPunishR/RavenEmu/wiki/Premiers-pas)
- [Consoles et compatibilité](https://github.com/GhostPunishR/RavenEmu/wiki/Consoles-et-compatibilite)
- [Matrice de compatibilité des jeux](https://github.com/GhostPunishR/RavenEmu/wiki/Compatibilite-des-jeux)
- [Compilation](https://github.com/GhostPunishR/RavenEmu/wiki/Compilation)
- [Architecture](https://github.com/GhostPunishR/RavenEmu/wiki/Architecture)
- [Dépannage](https://github.com/GhostPunishR/RavenEmu/wiki/Depannage)
- [Guide de contribution](CONTRIBUTING.md)
- [Code de conduite](CODE_OF_CONDUCT.md)
- [Politique de sécurité](SECURITY.md)
- [Politique de confidentialité](PRIVACY.md)
- [Publication des versions](RELEASING.md)

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

### Sauvegardes et états

- Une sauvegarde `.sav` reproduit la mémoire brute de la cartouche : ce format ne dépend pas de RavenEmu et reste stable dans le temps. C'est le support à privilégier pour conserver une progression.
- Un état instantané utilise le format versionné `RVNS`, propre à RavenEmu. Il n'est pas compatible avec les états d'autres émulateurs, et une évolution du moteur peut rendre un ancien état illisible : il est alors **refusé**, jamais réinterprété au risque de produire un comportement faux.
- Un état refusé laisse la partie en cours intacte et jouable.
- Un état ne peut être chargé ni sur une autre ROM, ni sur une autre console : l'empreinte de la ROM et l'identifiant de console sont inscrits dans le fichier et vérifiés.

Voir [Sauvegardes et états](https://github.com/GhostPunishR/RavenEmu/wiki/Sauvegardes-et-etats) pour le détail.

### Distribution

- Aucune version numérotée n'est encore publiée : seul l'APK Test l'est, et il n'est pas une version stable.
- La compatibilité des jeux commerciaux n'est validée qu'au cas par cas ; consultez la [matrice de compatibilité](https://github.com/GhostPunishR/RavenEmu/wiki/Compatibilite-des-jeux).
- La vérification d'intégrité des dépendances Gradle n'est pas encore activée ; le wrapper et sa distribution, eux, sont épinglés et validés à chaque construction.

## Contribuer

Les contributions de code, de documentation et de validation sont bienvenues. Consultez le [guide de contribution](CONTRIBUTING.md) et le [code de conduite](CODE_OF_CONDUCT.md) avant de proposer une modification.

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
