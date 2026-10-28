# Frontend natif expérimental (migration RetroArch)

Première tranche du nouveau frontend **C + Libretro**. Ce dossier est indépendant
du lecteur Kotlin actuel : il ne remplace ni les APK signés, ni la cible iOS.

- `src/libretro_host.c` : charge dynamiquement un core Libretro, établit les callbacks
  de vidéo, audio et commandes, charge une ROM en mémoire et exécute des images.
- `include/ravenemu/frontend.h` : API stable côté application (sans dépendre des
  détails de l'ABI Libretro dans les en-têtes publics).
- `tests/` : faux core Libretro et test d'intégration du chargeur.
- `CMakeLists.txt` : reprend le SHA épinglé de RavenCore et active ses trois
  bibliothèques Libretro. Le projet historique `native/` reste inchangé.

## Compiler et tester sur Linux

```sh
cmake -S frontend -B build/native-frontend -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-frontend --target raven_frontend_test raven_frontend_fake_core
ctest --test-dir build/native-frontend --output-on-failure
```

Pour compiler les moteurs Libretro avec RavenCore :

```sh
cmake --build build/native-frontend --target ravenemu_gb_libretro ravenemu_gbc_libretro ravenemu_gba_libretro
```

Un core est une bibliothèque partagée (`.so`) et doit être chargé par un frontend.
Le test vérifie les callbacks et le chargement d'une fausse ROM ; il ne prétend
pas que le jeu fonctionne sur Android.

## Limitations voulues de cette tranche

Un seul core actif (les callbacks Libretro sont process-global). Les options
personnalisées, le rendu GPU, les périphériques réels, RetroAchievements,
l'audio Android, les sauvegardes persistantes, les systèmes de fichiers Android
et le link cable seront raccordés par étapes. La couche JNI Java et la
migration des données restent à effectuer.

Les buffers vidéo/PCM appartiennent aux cores. Ils doivent être copiés ou
consommés synchroniquement dans les callbacks. Ne jamais garder leurs pointeurs.

Lire aussi [le plan de migration](../docs/MIGRATION_NATIVE.md).
