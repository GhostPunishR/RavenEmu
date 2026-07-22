# RavenEmu — Compilation

## Prérequis

- JDK 17 ou plus récent (le projet cible le bytecode Java 17).
- Gradle Wrapper fourni (`./gradlew`), Gradle 8.14.3.
- SDK Android (compileSdk 35) **uniquement** pour les modules Android :
  variable `ANDROID_HOME`/`ANDROID_SDK_ROOT` ou `local.properties` avec
  `sdk.dir=…`.

Sans SDK Android, `settings.gradle.kts` n'inclut que les modules JVM
(`emulation-api`, `gameboy-core`, `rom-library`) : le moteur se construit et
se teste sur n'importe quelle machine.

## Commandes

```bash
# Tests unitaires (tous modules inclus dans le build courant)
./gradlew test

# Tests des seuls modules JVM
./gradlew jvmTest

# Analyse lint des modules Android
./gradlew lint

# APK Debug
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# APK Release (signé si les variables de signature sont présentes)
./gradlew assembleRelease
```

## Signature Release

Le keystore n'est **jamais** stocké dans le dépôt (`.gitignore` exclut
`*.jks`, `*.keystore`, `keystore.properties`). La signature est pilotée par
variables d'environnement :

| Variable | Rôle |
|---|---|
| `RAVENEMU_KEYSTORE_PATH` | Chemin du keystore |
| `RAVENEMU_KEYSTORE_PASSWORD` | Mot de passe du keystore |
| `RAVENEMU_KEY_ALIAS` | Alias de la clé |
| `RAVENEMU_KEY_PASSWORD` | Mot de passe de la clé |

En CI, ces valeurs proviennent des secrets GitHub (`RAVENEMU_KEYSTORE_BASE64`
décodé au vol, plus les trois mots de passe/alias). Sans secrets, le job
`release` ne construit ni ne publie d'APK : Android refuse d'installer un
APK non signé, seul un APK signé est donc diffusé. Pour tester sans
signature, utilisez l'artefact `ravenemu-debug-apk` (APK Debug signé avec la
clé de debug, installable directement).

## Intégration continue

`.github/workflows/android.yml` : déclenchement sur branches principales et
pull requests ; Java 21 (Temurin) ; `test`, `lint`, `assembleDebug` avec
publication des rapports et de l'APK Debug ; job `release` produisant l'APK
Release (signé si secrets présents). La génération d'un `.aab`
(`bundleRelease`) pourra être ajoutée au même workflow.
