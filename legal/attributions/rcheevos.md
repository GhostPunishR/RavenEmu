# RetroAchievements : rcheevos

Les succès RetroAchievements du frontend C (`frontend/src/achievements.c`)
s'appuient sur le SDK officiel de RetroAchievements, **rcheevos**, sous
**licence MIT** (Copyright (c) 2018 RetroAchievements.org) :

| Composant | Source | Révision | Licence |
| --- | --- | --- | --- |
| rcheevos (rc_client, rc_libretro, rhash, rapi) | [RetroAchievements/rcheevos](https://github.com/RetroAchievements/rcheevos) | `1433173220a7eaede6a9ed7a18e94117be1821e0` | MIT |

- **Provenance** : le SDK est téléchargé à la compilation par CMake
  (`frontend/cmake/rcheevos.cmake`), à cette révision fixe, la même que la
  bêta. Aucun fichier n'est copié dans le dépôt.
- **Modification** : deux correctifs de sûreté de la bêta
  (`native/cmake/rcheevos_safety.cmake`), appliqués à une copie de
  `rc_client.c` et vérifiés à chaque configuration. Une mise à jour du SDK
  qui les rend inapplicables arrête la compilation.
- **Notre code** : `achievements.c` est le code de RavenEmu (AGPLv3), d'après
  l'adaptateur de la bêta (`native/android/achievements_bridge.cpp`). Il lit
  la mémoire du jeu par la carte mémoire Libretro que publie RavenCore.
- RavenCore, le moteur, ne contient aucune ligne de rcheevos.

- **Distribution** : le SDK est lié dans `libraven_android.so`. Le texte de la
  licence MIT et l'avis de copyright accompagnent l'APK
  (`android/src/main/assets/licenses/MIT-RCHEEVOS.txt`, onglet « MIT
  rcheevos » de **Paramètres → À propos → Licences et crédits**, et
  `NOTICE.txt`). L'archive de sources jointe à l'APK de test contient le
  checkout réellement compilé, sous `rcheevos/`, vérifié contre la révision
  épinglée (`scripts/package_native_sources.py`).
