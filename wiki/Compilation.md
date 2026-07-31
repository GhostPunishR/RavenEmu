# Compilation

## Prérequis

- Git;
- JDK 21 recommandé, identique à la CI;
- Gradle Wrapper fourni par le dépôt;
- SDK Android avec `compileSdk 35` pour construire l'application.

Les moteurs Kotlin/JVM peuvent être testés sans SDK Android. Dans ce cas, seuls les modules JVM sont inclus.

## Récupérer le code

```bash
git clone https://github.com/GhostPunishR/RavenEmu.git
cd RavenEmu
```

## Lancer les tests

```bash
./gradlew test
```

Cette commande vérifie les modules disponibles. Sans SDK Android, elle couvre les moteurs et bibliothèques JVM.

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
