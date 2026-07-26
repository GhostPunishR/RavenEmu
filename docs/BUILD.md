# RavenEmu : compilation

## Prérequis

- JDK 17 ou plus récent, le projet cible le bytecode Java 17.
- Gradle Wrapper fourni (`./gradlew`), Gradle 8.14.3.
- SDK Android avec compileSdk 35 uniquement pour les modules Android :
  variable `ANDROID_HOME` ou `ANDROID_SDK_ROOT`, ou `local.properties` avec
  `sdk.dir=…`.

Sans SDK Android, `settings.gradle.kts` n'inclut que les modules JVM
(`emulation-api`, `gameboy-core`, `gba-core`, `rom-library`) : les moteurs se
construisent et se testent sur une machine sans environnement Android complet.

## Commandes

```bash
# Tests unitaires des modules inclus dans le build courant
./gradlew test

# Tests des modules JVM
./gradlew jvmTest

# Analyse lint des modules Android
./gradlew lint

# APK Debug
./gradlew assembleDebug
# Fichier : app/build/outputs/apk/debug/app-debug.apk

# APK Release, signé si les variables de signature sont présentes
./gradlew assembleRelease
# Fichier : app/build/outputs/apk/release/app-release.apk

# App Bundle Release pour le Play Store
./gradlew bundleRelease
# Fichier : app/build/outputs/bundle/release/app-release.aab
```

## App Bundle

`bundleRelease` produit un Android App Bundle signé avec la même configuration
que l'APK Release. À partir de ce bundle, le Play Store génère des APK optimisés
par appareil, notamment pour la densité et la langue.

Le moteur étant en Kotlin pur, sans bibliothèque native, le découpage par ABI
n'est pas nécessaire. Le bloc `bundle { … }` de `app/build.gradle.kts` conserve
les découpages par densité et par langue.

Un fichier `.aab` ne s'installe pas directement sur un appareil. Il doit être
téléversé dans la Play Console ou converti localement avec
[`bundletool`](https://developer.android.com/tools/bundletool).

Exemple :

```bash
bundletool build-apks \
  --bundle=app-release.aab \
  --output=app.apks
```

Pour une installation directe, utilisez un APK Debug ou Release signé.

## Signature Release

Le keystore n'est jamais stocké dans le dépôt. Les fichiers `*.jks`,
`*.keystore` et `keystore.properties` sont exclus par `.gitignore`.

La signature est pilotée par les variables d'environnement suivantes :

| Variable | Rôle |
|---|---|
| `RAVENEMU_KEYSTORE_PATH` | Chemin du keystore |
| `RAVENEMU_KEYSTORE_PASSWORD` | Mot de passe du keystore |
| `RAVENEMU_KEY_ALIAS` | Alias de la clé |
| `RAVENEMU_KEY_PASSWORD` | Mot de passe de la clé |

En CI, le keystore provient du secret `RAVENEMU_KEYSTORE_BASE64`, décodé pendant
le job. Les mots de passe et l'alias proviennent également de secrets GitHub.

Sans secrets de signature, le job Release ne construit et ne publie aucun
livrable Release. Pour tester l'application, utilisez l'artefact
`ravenemu-debug-apk`, signé avec la clé de debug Android.

## Intégration continue

Le workflow `.github/workflows/android.yml` s'exécute sur les branches
principales et les pull requests.

Il effectue notamment :

- l'installation de Java ;
- les tests unitaires ;
- le lint Android ;
- la construction de l'APK Debug ;
- la publication des rapports ;
- la publication de l'APK Debug ;
- la construction conditionnelle de l'APK et de l'App Bundle Release lorsque
  les secrets de signature sont disponibles.

Les artefacts Release sont publiés sous les noms :

```text
ravenemu-release-apk
ravenemu-release-aab
```

Aucun livrable Release non signé ne doit être publié.
