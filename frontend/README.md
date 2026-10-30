# Frontend RavenEmu C / Libretro

Première tranche du nouveau frontend **C + Libretro**. Ce dossier est indépendant
du lecteur Kotlin actuel : il ne remplace ni les APK signés, ni la cible iOS.

- `src/libretro_host.c` : charge dynamiquement un core Libretro, établit les callbacks
  de vidéo, audio et commandes, charge une ROM en mémoire, exécute des images,\n  expose la SRAM et la sérialisation des états.
- `include/ravenemu/frontend.h` : API stable côté application (sans dépendre des
  détails de l'ABI Libretro dans les en-têtes publics).
- `tests/` : faux core Libretro et tests d'intégration, dont l'aller-retour\n  SRAM/save-state et le chargement des trois vrais cores.
- `CMakeLists.txt` : reprend le SHA épinglé de RavenCore et active ses trois
  bibliothèques Libretro. Le projet historique `native/` reste inchangé.

## Compiler et tester sur Linux

```sh
cmake -S frontend -B build/native-frontend -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-frontend --target raven_frontend_test raven_frontend_fake_core raven_frontend_real_core_test \
  raven_frontend_options_test raven_frontend_fake_core_v1 raven_frontend_fake_core_variables
ctest --test-dir build/native-frontend --output-on-failure
```

Pour compiler les moteurs Libretro avec RavenCore :

```sh
cmake --build build/native-frontend --target ravenemu_gb_libretro ravenemu_gbc_libretro ravenemu_gba_libretro
```

Un core est une bibliothèque partagée (`.so`) et doit être chargé par un frontend.
Les tests vérifient les callbacks, le chargement d'une fausse ROM et l'ouverture des trois vrais cores ; il ne prétend
pas que le jeu fonctionne sur Android.

## Limitations voulues de cette tranche

Un seul core actif (les callbacks Libretro sont process-global).
Le frontend C de ce dossier ne réalise pas lui-même les écritures de fichiers
ni le rendu graphique : les pilotes Android C/AAudio/GLES2, le JNI Java et
le stockage atomique par ROM vivent dans [android/](../android/).
RetroAchievements, les fonctions link, le rendu final et la migration des
données historiques restent à réaliser.

## Options des cores

Les options que déclare le core ouvert (options v2, v1 ou variables
historiques) sont gardées par l'hôte : `raven_frontend_option_count` et
`raven_frontend_get_option` les listent, `raven_frontend_set_option` choisit
une valeur proposée (une clé ou une valeur inconnue est refusée). Choisie avant
`raven_frontend_load_rom`, elle vaut dès le chargement ; changée en partie, le
core la reçoit à l'image suivante par `GET_VARIABLE_UPDATE`. Les tests
`raven_frontend_options` et `raven_frontend_real_cores` le vérifient, le second
avec une ROM Game Boy vide générée par le test.

Les buffers vidéo/PCM appartiennent aux cores. Ils doivent être copiés ou
consommés synchroniquement dans les callbacks. Ne jamais garder leurs pointeurs.

Lire aussi [le plan de migration](../docs/MIGRATION_NATIVE.md).

## Messages du core

Les messages qu'un core demande d'afficher (`RETRO_ENVIRONMENT_SET_MESSAGE`
ou `SET_MESSAGE_EXT`, hors journal seul et débogage) sont gardés par l'hôte ;
`raven_frontend_take_message` rend chacun une fois, en UTF-8 valide sans
caractère de 4 octets. Le test `raven_frontend_smoke` le vérifie.
