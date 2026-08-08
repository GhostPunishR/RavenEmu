# Compilation

## Prérequis

- Git;
- JDK 21 recommandé, identique à la CI;
- Gradle Wrapper fourni par le dépôt;
- SDK Android avec `compileSdk 35` pour construire l'application;
- Android NDK `27.2.12479018` et CMake `3.22.1`.

Les modules JVM peuvent être testés sans SDK Android. Les moteurs C++ se testent séparément sur l'hôte avec un compilateur C++20 et CMake.

## Récupérer le code

```bash
git clone https://github.com/GhostPunishR/RavenEmu.git
cd RavenEmu
```

## Lancer les tests

```bash
./gradlew test
```

Cette commande vérifie les modules disponibles. Sans SDK Android, elle couvre les contrats, les adaptateurs, les bibliothèques JVM et les implémentations Kotlin de référence.

## Tester les moteurs C++

```bash
cmake -S native-core -B build/native-host \
  -DRAVENEMU_BUILD_TESTS=ON \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-host --parallel
ctest --test-dir build/native-host --output-on-failure
```

Pour compiler aussi la frontière JNI sur l'hôte, ajoutez `-DRAVENEMU_BUILD_JNI=ON`; CMake utilise alors les en-têtes du JDK installé.

## Lint Android

```bash
./gradlew lint
```

Le SDK Android doit être configuré.

## Construire l'APK Debug

```bash
./gradlew assembleDebug
```

Le fichier produit se trouve sous:

```text
android/app/build/outputs/apk/debug/
```

## Validation complète

```bash
cmake -S native-core -B build/native-host -DRAVENEMU_BUILD_TESTS=ON
cmake --build build/native-host --parallel
ctest --test-dir build/native-host --output-on-failure
./gradlew test lint assembleDebug
```

C'est la séquence principale exécutée par la CI Android.

## Configurer le SDK

Utilisez une des méthodes reconnues par Gradle:

- `sdk.dir` dans `local.properties`;
- variable `ANDROID_HOME`;
- variable `ANDROID_SDK_ROOT`.

Ne publiez pas un fichier `local.properties` contenant un chemin propre à votre machine.

## Release signée

La construction Release utilise des secrets de signature configurés dans GitHub Actions. Les clés et mots de passe ne doivent jamais être ajoutés au dépôt.

Voir aussi [[Architecture]] et [[Contribution]].
