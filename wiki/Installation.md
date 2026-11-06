# Installation

L'application Android native n'a pas encore de version publique. Chaque compilation réussie de `main` produit un **APK de développement**, artefact GitHub Actions qu'il faut un compte GitHub pour télécharger.

1. Connecté à GitHub, ouvrez la [dernière compilation réussie de `main`](https://github.com/GhostPunishR/RavenEmu/actions/workflows/ci-applications.yml?query=branch%3Amain+is%3Asuccess), puis sa section « Artifacts ».
2. Téléchargez `ravenemu-android-dev-arm64` et extrayez `android-debug.apk` du ZIP. L'artefact est gardé 7 jours.
3. Installez l'APK, puis importez vos propres ROM.

| Élément | Valeur |
| --- | --- |
| Nom affiché | RavenEmu |
| Identifiant Android | `com.ravenemu.dev` |
| Système | Android 8.0 et plus, téléphone ARM64 (`arm64-v8a`) |
| Signature | Clé de débogage de la compilation : ce n'est pas une version publique signée |

L'APK de développement s'installe **à côté** de la bêta `com.ravenemu.preview`, sans lire ni modifier ses données. Pour reprendre vos parties, exportez vos sauvegardes depuis la bêta (`.sav`, `.srm` ou archive ZIP), puis importez-les depuis la fiche du jeu dans l'application native. Les états instantanés de la bêta ne s'importent pas.

Les anciennes préversions `android-beta-N` restent dans les [Releases](https://github.com/GhostPunishR/RavenEmu/releases), mais la bêta n'est plus développée.

Voir [RELEASING.md](https://github.com/GhostPunishR/RavenEmu/blob/main/RELEASING.md) et [Premiers pas](https://github.com/GhostPunishR/RavenEmu/blob/main/wiki/Premiers-pas.md).
