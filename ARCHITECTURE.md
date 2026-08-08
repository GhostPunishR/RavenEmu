# Architecture RavenEmu V2

Cette architecture a un objectif simple : un fichier doit avoir une responsabilité évidente et les dépendances doivent toujours aller vers les couches plus basses.

## Direction des dépendances

```text
android/app
    ↓
features / platform
    ↓
engine
    ↓
native/jni
    ↓
cores
```

Une couche basse ne connaît jamais une couche haute.

## `cores/` — émulation C++ uniquement

`cores/` contient les moteurs d'émulation et leurs primitives C++ communes.

Règles :

- aucun `JNIEnv`, `jobject` ou en-tête JNI ;
- aucune dépendance Android ou JVM ;
- aucune politique d'interface utilisateur, stockage Android ou cycle de vie ;
- les cœurs doivent pouvoir être configurés, compilés et testés directement avec `cmake -S cores` ;
- C++20 reste le standard du moteur tant qu'un besoin concret ne justifie pas un changement.

Le cœur historique `CGBRavenCore` reste actuellement commun à GB/GBC. Il ne sera pas séparé artificiellement dans cette refonte : cette opération touchera le comportement matériel et devra être couverte par des tests de parité dédiés avant tout déplacement.

## `native/` — frontière ABI/JNI

`native/` est le seul endroit autorisé à connaître à la fois la JVM et les cœurs C++.

```text
native/
├── CMakeLists.txt
└── jni/
    ├── build.gradle.kts
    ├── cpp/
    └── src/main/java/
```

Le bridge transporte des poignées, scalaires et tableaux primitifs. Les décisions d'émulation restent dans les cœurs ou dans le moteur Kotlin.

## `engine/` — moteur Kotlin pur

Les modules Gradle suivants représentent cette couche :

- `:engine:api` : contrats publics d'émulation ;
- `:engine:systems:gb` : adaptateur Kotlin GB/GBColor vers le cœur natif ;
- `:engine:systems:gba` : adaptateur Kotlin GBA vers le cœur natif.

Pour éviter une réécriture massive non fonctionnelle dans une seule PR, ces projets sont encore associés à leurs dossiers historiques par `settings.gradle.kts`. Le nom Gradle exprime déjà la responsabilité ; les déplacements physiques pourront ensuite être faits par lots mécaniques, une fois la nouvelle architecture validée en CI.

## `features/` — fonctions indépendantes

`features` contient les fonctions applicatives qui ne sont ni du matériel émulé ni des services Android.

Actuellement :

- `:features:skins` : lecture et modèle des skins Delta.

Les futures fonctions autonomes doivent être ajoutées ici plutôt que dans un dossier générique `utils`.

## `platform/android/` — services Android

Les dépendances spécifiques au système sont séparées par fonction :

- `:platform:android:storage` ;
- `:platform:android:renderer` ;
- `:platform:android:input` ;
- `:platform:android:settings`.

Ces modules peuvent dépendre du moteur ou des features, mais le moteur et les features ne doivent jamais dépendre d'eux.

## Bibliothèque ROM : zone gelée pour cette refonte

La bibliothèque est un changement majeur récent de RavenEmu et n'est pas refondue dans cette PR.

Les règles de cette migration sont donc :

- `core/rom-library/src/**` reste inchangé ;
- les écrans, modèles, ressources, navigation et comportement de la bibliothèque Android restent inchangés ;
- seul le `build.gradle.kts` du module peut référencer les nouveaux noms Gradle des mêmes dépendances ;
- aucune migration de données ou de format sérialisé n'est introduite.

Le module conserve volontairement son identité `:core:rom-library` pendant cette étape.

## Application Android

`android/app` reste le point de composition de l'application et contient encore des fonctions qui pourront être extraites plus tard. Cette PR ne déplace pas les écrans de bibliothèque afin de garantir leur stabilité.

La prochaine décomposition doit se faire fonction par fonction, avec tests et sans changement simultané de comportement.

## Chaîne de build de référence

- Android Gradle Plugin : 9.3.1
- Gradle : 9.5.0
- Kotlin : 2.4.10
- compileSdk : 37
- targetSdk : 35 pendant cette refonte structurelle
- NDK : 29.0.14206865 (r29 stable)
- Java/JVM bytecode : 17
- C++ : C++20
- CMake Android : 3.22.1 actuellement épinglé et validé par le projet

Le `targetSdk` n'est pas augmenté ici : une hausse du target change les règles d'exécution Android et doit être traitée séparément comme une migration comportementale.

## Règles pour les prochains changements

1. Pas de logique d'émulation dans Android.
2. Pas de JNI dans `cores/`.
3. Pas de dépendance Android dans `engine`, `features`, `native` ou les modules JVM purs.
4. Pas de dossier fourre-tout `utils` : une fonction réutilisable doit avoir un domaine clair.
5. Une extraction structurelle ne doit pas être accompagnée d'une réécriture fonctionnelle sans nécessité.
6. Un nouveau cœur doit pouvoir être testé sans SDK Android.
7. La séparation GB/GBC ne se fera qu'après ajout de tests capables de prouver la parité avant/après.
