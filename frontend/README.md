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
  raven_frontend_options_test raven_frontend_fake_core_v1 raven_frontend_fake_core_variables \
  raven_frontend_preempt_test raven_frontend_fake_core_lag raven_frontend_fake_core_lag_incomplete \
  raven_frontend_achievements_test
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

## Codes de triche

`raven_frontend_set_cheats` remplace la liste des codes du jeu, chacun actif,
dans l'ordre, par `retro_cheat_reset` puis `retro_cheat_set` (symboles
facultatifs : sans eux, -2). RavenCore nomme un code refusé dans un message
(« code de triche N refusé (raison) ») : le frontend rend l'indice du premier
code refusé et sa raison, et garde ce message hors de
`raven_frontend_take_message`, pour que l'application l'affiche une seule
fois, sous le formulaire. Les images préventives repartent de zéro. Testé sur
le faux core et sur les vrais cores GB, GBC et GBA : un code GameShark ou
Codebreaker écrit la mémoire du jeu (l'état diffère), un code illisible est
refusé avec sa raison.

## Deux jeux reliés (câble link)

Un core qui déclare le sous-système `link` (`SET_SUBSYSTEM_INFO` : deux jeux,
une SRAM chacun, comme RavenCore) charge deux jeux par
`raven_frontend_load_linked` (`retro_load_game_special`, symbole facultatif).
L'image contient les deux joueurs côte à côte, le joueur 1 à gauche ; le port
n est la manette du joueur n+1. `raven_frontend_players` rend 1 ou 2, et
`raven_frontend_player_battery_size`, `read_player_battery` et
`restore_player_battery` donnent la SRAM de chaque joueur (les fonctions sans
joueur restent celles du joueur 1). Les images préventives restent coupées
avec deux jeux. Testé sur le faux core (sans sous-système : refus net) et sur
les vrais cores GB, GBC et GBA : image double, SRAM de chaque joueur, état
des deux jeux, retour à un jeu seul.

## Vibration et capteurs

`raven_frontend_callbacks` accepte trois rappels facultatifs : `rumble`
(moteur d'un port, effet fort ou faible, force 0 à 65535), `sensor_state`
(action `retro_sensor_action` et fréquence) et `sensor_input` (accélération
en m/s² dans le sens de l'écran, rotation en rad/s, lumière en lux). Sans
eux, `GET_RUMBLE_INTERFACE` et `GET_SENSOR_INTERFACE` répondent faux et le
core s'en passe. Le test `raven_frontend_smoke` les vérifie avec le faux core.

## Images préventives

`raven_frontend_set_preemptive_frames(host, n)` (0 à 3, désactivé par défaut)
garde l'état du core avant chacune des n dernières images. Quand les boutons
du port 0 changent, `raven_frontend_run_frame` revient n images en arrière et
les rejoue avec les nouveaux boutons, sans image ni son
(`GET_AUDIO_VIDEO_ENABLE` répond 0 : RavenCore saute alors son rendu), puis
exécute l'image demandée. Un jeu qui répond à un appui k images plus tard
répond min(n, k) images plus tôt ; au-delà de k, l'image saute.
L'historique repart de zéro à chaque chargement, état restauré, SRAM restaurée
et `raven_frontend_forget_preemption` (pause). Un core qui déclare des états
incomplets (`SET_SERIALIZATION_QUIRKS`) n'est jamais rejoué.
`raven_replay_guard_overloaded` (`frame_pacer.h`) dit quand une image rejouée
dépasse trop souvent les trois quarts de la période du jeu.

Tests : `raven_frontend_preempt` avec `fake_core_lag.c`, un jeu à deux images
de retard (gain mesuré pour n = 0 à 3, chronologie rejouée identique à celle
d'une partie aux appuis avancés de n images, appuis d'une image compris,
historique oublié, quirks) ; `raven_frontend_real_cores` (les trois cores
RavenCore finissent dans le même état octet pour octet) ; `raven_frame_pacer`
(garde de coût).

## RetroAchievements

`include/ravenemu/achievements.h` porte les succès RetroAchievements sur le
SDK officiel **rcheevos** (MIT, révision `1433173`, téléchargé par
`cmake/rcheevos.cmake` avec les deux correctifs de sûreté de la bêta,
[provenance](../legal/attributions/rcheevos.md)). L'hôte garde la carte
mémoire que le core déclare (`RETRO_ENVIRONMENT_SET_MEMORY_MAPS`) ; rcheevos
la lit par `rc_libretro`, sur le fil d'émulation seulement. Le module gère le
compte (mot de passe ou jeton), l'identification du jeu par son empreinte,
l'évaluation à chaque image montrée (jamais pendant les images préventives),
le mode Hardcore, un résumé, la liste des succès, les classements et leurs
scores (meilleurs ou autour du joueur) et la Rich Presence. La plateforme fait le réseau : elle prend
chaque requête (`raven_achievements_next_request`), l'envoie et rend la
réponse (`raven_achievements_respond`) ; l'interface lit les événements
(`raven_achievements_next_event`). La fermeture règle elle-même les requêtes
encore en cours.

`raven_frontend_reset` redémarre le jeu en place (`retro_reset`, symbole
facultatif) : le Hardcore de rcheevos le demande quand il s'active pendant un
jeu.

Sur Android, `android/src/main/cpp/jni/achievements_bridge.c` expose le module
à Java (`NativeAchievements`) : l'instance du lecteur, liée au jeu du pont et
évaluée à chaque image montrée, et celle de la page de compte, sans jeu. Les
textes passent en octets UTF-8, jamais en UTF-8 « modifié » de JNI ; une
poignée détruite ne retrouve rien. Le test `raven_jni_achievements`
(`tests/jni/`) le vérifie sur la JVM de l'hôte, avec la vraie source Java et
le core Game Boy de RavenCore ; il est omis sans JDK.

Test : `raven_frontend_achievements`, contre un faux service aux réponses du
format de rcheevos et le vrai core Game Boy de RavenCore, qui exécute une ROM
construite par le test : elle compte les images en WRAM (`$C001`) et le succès
« 0xHc001=60 » se débloque à la soixantième image, en mode normal comme en
Hardcore (requête `h=1`, jeu « maîtrisé »). Le test couvre aussi les refus et
pannes de connexion, le jeton, l'activation du Hardcore en cours de jeu (remise
à zéro demandée), un service injoignable ou qui refuse le jeu, et la fermeture
avec des requêtes en cours. Sans la carte mémoire, le succès ne se débloque
jamais : le test la vérifie bien.
