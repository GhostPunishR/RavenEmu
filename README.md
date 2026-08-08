# RavenEmu

[![Android CI](https://github.com/GhostPunishR/RavenEmu/actions/workflows/android.yml/badge.svg)](https://github.com/GhostPunishR/RavenEmu/actions/workflows/android.yml)
[![Wiki](https://img.shields.io/badge/Documentation-Wiki-5b21b6.svg?logo=github&logoColor=white)](https://github.com/GhostPunishR/RavenEmu/wiki)
[![Site officiel](https://img.shields.io/badge/Site-RavenEmu-7c3aed.svg?logo=githubpages&logoColor=white)](https://ghostpunishr.github.io/RavenEmu/)
[![APK Test](https://img.shields.io/badge/APK-Test-a855f7.svg?logo=android&logoColor=white)](https://github.com/GhostPunishR/RavenEmu/releases/download/test-latest/RavenEmu-test.apk)
[![Licence MIT](https://img.shields.io/badge/Licence-MIT-22c55e.svg?logo=opensourceinitiative&logoColor=white)](LICENSE)

**RavenEmu** est un émulateur Game Boy, Game Boy Color et Game Boy Advance pour Android.

Les moteurs sont développés en **C++20** pour le projet à partir de documentation technique publique, avec une frontière JNI minimale vers l'application Android. RavenEmu n'intègre aucun cœur d'émulation tiers et ne fournit aucune ROM, aucun BIOS ni contenu protégé.

## Télécharger

La CI publie automatiquement l'APK de test issu de `main` :

### [Télécharger le dernier APK Test](https://github.com/GhostPunishR/RavenEmu/releases/download/test-latest/RavenEmu-test.apk)

L'APK Test est destiné aux essais et ne constitue pas une version stable. Les informations de signature, d'installation et de vérification sont détaillées dans le [wiki](https://github.com/GhostPunishR/RavenEmu/wiki/Installation).

## État du projet

| Console | État |
|---|---|
| Game Boy | Avancé |
| Game Boy Color | Intégré au moteur GB, extraction dédiée en cours |
| Game Boy Advance | Expérimental |

La compatibilité varie selon les jeux. Consultez la [matrice de compatibilité](https://github.com/GhostPunishR/RavenEmu/wiki/Compatibilite-des-jeux).

## Architecture

```text
app/android
    ↓
features + platform/android
    ↓
engine
    ↓
native/api + native/jni
    ↓
cores/common + cores/gb + cores/gbc + cores/gba
```

Les chemins Gradle correspondent aux dossiers physiques. Les cœurs restent indépendants d'Android et l'interface native est volontairement minimale.

Voir [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) et le [wiki Architecture](https://github.com/GhostPunishR/RavenEmu/wiki/Architecture) pour les détails.

## Compiler

Prérequis principaux : JDK, Android SDK avec `compileSdk 37`, NDK `29.0.14206865` et CMake `3.22.1`.

```bash
git clone https://github.com/GhostPunishR/RavenEmu.git
cd RavenEmu

./gradlew test
./gradlew lint
./gradlew :app:android:assembleDebug
```

Pour les tests C++ natifs :

```bash
cmake -S cores -B build/native-host -DRAVENEMU_BUILD_TESTS=ON
cmake --build build/native-host --parallel
ctest --test-dir build/native-host --output-on-failure
```

Le guide complet est disponible dans le [wiki Compilation](https://github.com/GhostPunishR/RavenEmu/wiki/Compilation).

## Documentation

- [Wiki RavenEmu](https://github.com/GhostPunishR/RavenEmu/wiki)
- [Installation](https://github.com/GhostPunishR/RavenEmu/wiki/Installation)
- [Premiers pas](https://github.com/GhostPunishR/RavenEmu/wiki/Premiers-pas)
- [Consoles et compatibilité](https://github.com/GhostPunishR/RavenEmu/wiki/Consoles-et-compatibilite)
- [Sauvegardes et états](https://github.com/GhostPunishR/RavenEmu/wiki/Sauvegardes-et-etats)
- [Compilation](https://github.com/GhostPunishR/RavenEmu/wiki/Compilation)
- [Architecture](https://github.com/GhostPunishR/RavenEmu/wiki/Architecture)
- [Dépannage](https://github.com/GhostPunishR/RavenEmu/wiki/Depannage)
- [Guide de contribution](CONTRIBUTING.md)
- [Publication des versions](RELEASING.md)

## Contribuer

Les contributions sont bienvenues. Consultez [CONTRIBUTING.md](CONTRIBUTING.md) et [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

Le code d'un autre émulateur ne doit pas être copié, traduit ou adapté dans RavenEmu. Les tests ne doivent contenir aucune ROM commerciale ni aucun BIOS protégé.

## Licence et marques

RavenEmu est distribué sous [licence MIT](LICENSE). Copyright 2026 GhostPunishR.

Game Boy, Game Boy Color et Game Boy Advance sont des marques de Nintendo. RavenEmu n'est ni affilié à Nintendo, ni approuvé par Nintendo.
