# RavenEmu Android (application de développement)

This is an **independent experimental Android application** that does not
replace the released RavenEmu beta. Application ID: `com.ravenemu.dev`.
Requires Android 8.0+ and an **ARM64** phone (arm64-v8a).

## Interface d'origine sans Kotlin dans le module migré

Le lancement ouvre `HomeActivity.java`, une adaptation en **Java
Android Views** du design déjà présent dans la bêta RavenEmu. Les couleurs
(`#0B0C12`, `#191B28`, `#A78BFA`), la barre d'outils avec ⚙ / + / ⋮,
le champ de recherche, la grille/listes de jaquettes, les onglets par console,
le pied de page « RavenEmu By GhostPunishR » et la fiche du jeu reprennent
le code source de référence `shared/theme/RavenColors.kt`,
`shared/library/LibraryComponents.kt` et `LibraryDialogs.kt` **pour
l'apparence seulement**. Ces fichiers Kotlin ne sont pas compilés ni utilisés
dans `android`.

La couche Android de migration utilise uniquement **Java**, le frontend et
le rendu restent **C**, et RavenCore reste **C++20**. Les fichiers
`build.gradle.kts` sont seulement le langage de configuration Gradle déjà
utilisé par le dépôt ; aucune source Kotlin ou Compose n'est ajoutée au
nouveau module.

La bibliothèque Java restaure déjà les écrans centraux, mais ce n'est **pas
encore une parité visuelle totale** : les réglages et les fonctions avancées
du menu doivent encore être raccordés et le rendu validé sur téléphone.

## Calibrage sur les barres du téléphone

Android 15 (target SDK 35) impose le rendu bord à bord : la barre d'état
(heure, réseau, batterie) et la navigation système à trois boutons ou par
gestes peuvent chevaucher l'app si elle n'applique pas ses insets.

Les activités Java `HomeActivity` et `PlayerActivity` utilisent désormais
`SystemBarInsets.java` pour réserver les vrais insets `systemBars` et
`displayCutout` sur les quatre côtés, ainsi que l'inset clavier pour la
recherche. La barre de titre et le champ de recherche restent sous l'heure et
les icônes Android ; le pied de page « RavenEmu By GhostPunishR » et les
boutons tactiles restent au-dessus de la barre de navigation du téléphone.

Les marges sont recalculées depuis les valeurs initiales, sans double décalage
lors des changements d'orientation, d'affichage du clavier ou du retour dans
l'application. Des tests JVM valident cette géométrie. Les tests visuels
restent à effectuer en navigation **3 boutons** et **gestuelle**, portrait et
paysage, sur appareil physique.

Le décor de fenêtre est créé avant l'accès au contrôleur d'insets Android
30+, y compris avant `setContentView`. La recherche, la console sélectionnée,
le mode grille/liste et la cible d'une jaquette choisie dans Fichiers sont
conservés lors d'une recréation d'Activity. Un import déjà commencé peut finir
d'écrire dans le catalogue privé après fermeture, sans relancer l'exécuteur
de l'ancien écran. La fiche de jeu est défilable pour les écrans bas en paysage.
Les tests Java Robolectric couvrent ces parcours ; ils ne remplacent pas
la validation visuelle sur téléphone, notamment avec une grande police.

## Fiche glissante et commandes tactiles

La fiche s'ouvre directement dépliée, sans étape à mi-hauteur. Elle se ferme
par glissement vers le bas, Retour, un appui à l'extérieur ou « Fermer » ;
son contenu défile sur les écrans bas. Le jeu sélectionné est restauré après
recréation de l'Activity et les ouvertures répétées ne superposent pas de fiches.
`GameDetailsSheet.java` utilise le composant **Android Views** de Material
Components for Android 1.14.0 avec les couleurs RavenEmu et des coins supérieurs
arrondis. Sa licence Apache 2.0 et les attributions sont incluses dans
`src/main/assets/licenses/`. Cette dépendance n'ajoute ni Compose ni source
applicative Kotlin.

`GameControlsView.java` reprend la croix, les boutons A/B décalés et les
touches SELECT/START ; L/R apparaissent pour la GBA. La croix permet les
diagonales et le glissement, avec plusieurs doigts sur les commandes.
Le mode paysage réserve les côtés aux commandes et garde le centre pour
l'image. Les contrôles libèrent leurs appuis sur annulation, perte de focus,
pause, ouverture du menu et changement de jeu. Les entrées tactiles et
manette sont combinées ; les actions d'accessibilité ont une durée bornée.

Le pilote C `input_driver.c` conserve les fronts d'appui jusqu'à une image
émulée, même si le doigt est déjà relevé. Son instantané reste stable pendant
cette image ; un simple rafraîchissement vidéo ne consomme pas l'appui.
La validation tactile et visuelle sur téléphone reste nécessaire avant de
conclure à une parité avec la bêta.

## Architecture
- `HomeActivity.java` : écran bibliothèque d'accueil et navigation en Java.
- `RavenLibraryAdapter.java` : grille/liste de jaquettes selon le style RavenEmu.
- `src/main/res/values/styles.xml` : couleurs du thème original.


- `PlayerActivity.java` : lecteur Java/JNI lancé depuis la fiche « Jouer »,
  avec la barre de jeu, contrôles tactiles, boucle GL et sauvegardes natives.
- `RomLibrary.java`: offline private GB/GBC/GBA catalogue, SHA-256 identities,
  verified ROM copies and atomic JSON index. No broad storage permission.
- `CoverRepository.java`: local-only cover import via SAF, bounded PNG
  conversion (maximum 512 px edge, 8 MiB source) and atomic private storage
  by ROM SHA-256; removal affects no ROMs, saves or other files.
- `CoverAdapter.java`: image thumbnails decoded on a background executor,
  with a bounded 32-image LRU cache and text fallback.
- `PlayerMenuSheet.java`: panneau de pause glissant Java Views, quatre
  emplacements, confirmations et navigation au contrôleur.
- `PlayerPauseState.java`: pause maintenue tant que le menu, une opération,
  l'arrière-plan ou la perte de focus le demandent.
- `StateSlotRepository.java`: versioned atomic state slots, ROM/core identity
  and SHA-256 validation; disk access stays on the storage executor shared
  between player Activities, so a recreated player reads after prior writes.
- `StatePreview.java`: bounded PNG encoding/decoding on the storage executor.
- `NativeBridge.java`: typed JNI entry points; `setButtons` and `clearButtons`
  update atomic input from the UI. Core calls use the GL thread while running;
  lifecycle cleanup accesses the session only after that thread is paused.
- `src/main/cpp/bridge.c`: Libretro session management, frame conversion to
  RGBA8888, OpenGL ES 2 texture drawing, atomic joypad input.
- `src/main/cpp/input_driver.c`: held/pending input masks and one stable
  joypad snapshot per emulated frame.
- `src/main/cpp/state_thumbnail.c`: bounded RGBA-to-ARGB frame copy for state
  thumbnails, with nearest-neighbor downscaling and preserved aspect ratio.
- The C bridge gates emulated frames and native audio together during pause;
  the Java sheet supplies the RavenEmu presentation instead of the prototype's
  pixel-font menu. Core capture/restore still uses the GL/emulation thread.
- `../frontend/`: C Libretro host, reused without code duplication.
- `GhostPunishR/RavenCore`: the pinned C++20 cores, compiled as three
  independent `.so` files.

## Build

```bash
./gradlew :android:assembleDebug --stacktrace
```

APK: `android/build/outputs/apk/debug/android-debug.apk`.

Run `./gradlew :android:testDebugUnitTest` for identity/path, insets,
Activity recreation/import, sheet gestures and multi-touch controls tests.
The standalone `raven_native_input_test` target in `frontend/CMakeLists.txt`
tests the input driver through CTest without Android or a commercial ROM.
The test SDK is resolved through Gradle;
the application remains Java-only, without Compose or `:shared`.

The build should package `libraven_android.so`,
`ravenemu_gb_libretro.so`, `ravenemu_gbc_libretro.so` and
`ravenemu_gba_libretro.so`. They are compiled for ARM64 only.

Launch **RavenEmu Native (Test)** and use the **+** button to copy your
`.gb`, `.gbc` or `.gba` into the app's private library. The library grid
reopens previously imported games, including after relaunching the app.
Tap a cover to open the details sheet, then tap **Jouer** to launch the
native player. Return with the back arrow to see the same library.
La bibliothèque d'accueil propose recherche sans accent et filtre GB/GBC/GBA.
La fiche présente le titre de cartouche et les informations du jeu.
Select **Image manuelle (facultatif)** to choose a JPEG/PNG or another Android-decodable
image from your phone. The app downscales and stores it privately; **Retirer
la jaquette** removes only that local image. This does not download box art or
change game ROMs and saves. Android can display the cover next to the title,
with an offline placeholder for games without artwork.
Header metadata is read locally without any online API.
**⋮ / Menu du jeu** ouvre le panneau glissant et met image émulée et audio en
pause. Choisir un des **quatre emplacements**, puis **Sauver**, **Charger** ou
**Supprimer**. Écrasement et suppression nécessitent confirmation. Une action
en cours bloque les doubles appuis ; fermer la fiche pendant une opération ne
reprend le jeu qu'après sa fin, si l'Activity est active et a le focus.
Retour, le glissement et **Reprendre le jeu** ferment la fiche ; la manette
navigue entre ses vues, A active et B revient. La flèche du lecteur ramène
à la bibliothèque d'accueil pour changer de jeu ou importer une ROM/jaquette.

Chaque nouvel état contient sa version de format, l'identité ROM, la révision
épinglée et l'identité du core, la date et une empreinte SHA-256 du contenu.
Les fichiers incompatibles/corrompus restent visibles et supprimables ; leur
chargement est refusé avant désérialisation. Un échec du core tente de restaurer
l'état courant capturé avant chargement ; si ce retour échoue aussi, le jeu
est fermé sans écraser la SRAM. L'ancien état rapide du seul package de test
reste disponible à l'emplacement 1 avec un avertissement et une confirmation
de compatibilité inconnue. L'enregistrer à nouveau crée une enveloppe versionnée.
Supprimer cet emplacement le masque avec un marqueur atomique ; le fichier
historique reste intact et ne réapparaît pas. Aucun fichier bêta n'est lu.

La carte montre désormais une **miniature du jeu au moment de la sauvegarde**,
centrée et sans filtrage des pixels. La capture C copie au plus 160×160 pixels
depuis la dernière image du core ; l'encodage/décodage PNG reste sur le fil de
stockage. Les nouveaux fichiers d'états (enveloppe v2) enregistrent ensemble
état et image lors d'un remplacement atomique. Les fichiers v1 restent lisibles
sans réécriture à l'ouverture. L'image est optionnelle, bornée à 256 Kio et
vérifiée séparément : sa corruption n'empêche pas de charger un état valide.
Sans image disponible, la carte garde son badge console. Après restauration,
une nouvelle miniature attend une image fraîche du core pour éviter d'utiliser
les pixels de la partie précédente.

Après un chargement réussi, **Annuler le chargement** revient à l'état courant
capturé juste avant le dernier chargement. Un seul point est gardé en mémoire,
pour la même ROM et le même core, puis consommé après une annulation réussie.
Si l'annulation échoue, le lecteur tente de rétablir la partie courante et
garde le point d'annulation pour une nouvelle tentative. Les fichiers des
emplacements et la SRAM ne sont pas supprimés par cette action. Le point est
abandonné à la fermeture/recréation du lecteur ou au changement de ROM.

Les autres options avancées du menu restent à porter. La recréation restaure
le panneau et le numéro d'emplacement ; le redémarrage du jeu repose encore
sur la SRAM, pas sur une restauration automatique de son état CPU exact.
No ROM or BIOS is bundled; no broad storage permission is requested.

## Options des cores et réglages d'image

**Paramètres → Vidéo** reprend trois groupes de la bêta, traités par RavenCore
lui-même au travers de ses options Libretro : **Profil de couleurs** des jeux
Game Boy (Original, le vert de la console comme dans la bêta ; Pocket gris ;
Gris neutre), **Correction des couleurs LCD** pour la Game Boy Color et la Game
Boy Advance, et **Mélange d'images** (désactivé, mélange de deux images,
rémanence). `CoreOptions.java` traduit ces réglages en clés des cores
(`ravenemu_gb_palette`, `ravenemu_gbc_color_correction`…). `openRom` les passe
au frontend C avant le chargement du jeu ; au retour des paramètres,
`setCoreOptions` les change sur le fil GL et le core les applique à l'image
suivante, sans rouvrir le jeu. Ni la SRAM ni les états ne changent de format.
Le filtre « DMG vert » de la bêta n'a pas d'équivalent exact ; les options
de cartouche et les capteurs restent à porter.

## Console

**Paramètres → Console** reprend la page de la bêta. **Jeux Game Boy en
couleurs** ouvre les jeux Game Boy sur le core Game Boy Color, avec les
couleurs choisies (celles de la boot ROM ou une combinaison de touches),
changées aussitôt même en jeu. **Game Boy Color d'une Game Boy Advance** et le
**BIOS** de la GBA (RavenCore, vrai BIOS, vrai BIOS sans animation) valent au
lancement suivant. Le fichier du BIOS, choisi avec le sélecteur de documents,
doit faire 16 Kio ; il est copié en `gba_bios.bin` dans le stockage privé, que
le core lit comme dossier système (`GbaBiosRepository`). Aucun BIOS n'est
fourni. Un état pris sur une autre console ou avec un autre BIOS apparaît
incompatible ; la SRAM reste celle du jeu.

## Portage ciblé des pilotes RetroArch et crédits

Le backend AAudio et le chemin GLES2 adaptent désormais des portions de
RetroArch, épinglées à `7986022baf6822677ad2cec4bc215069e89bd8ce`.
[La provenance détaillée](../legal/attributions/retroarch.md) indique les
fonctions reprises, modifications et copyrights. Les fichiers dérivés restent
GPLv3 ou ultérieure ; RavenEmu et RavenCore restent AGPLv3.

AAudio demande la fréquence native du périphérique, deux bursts de buffer,
et une file PCM16 stéréo bornée avec purge à la pause/reprise. Le producteur
ne bloque pas ; un manque de données produit du silence. Le rééchantillonnage
linéaire RavenEmu reste distinct. Le pilote GLES2 conserve une texture entre
images et utilise `glTexSubImage2D` pour les nouvelles images de même taille,
avec les shaders opaques de base adaptés. Aspect, orientation, nearest et
miniatures CPU sont conservés.

Dans **Paramètres → À propos → Licences et crédits**, les textes sont locaux,
sélectionnables, et le bouton Sources cible la révision Git construite. Une
construction modifiée localement est identifiée comme telle.

La CI joint l'APK **et** `ravenemu-native-sources.tar.gz` au même artifact
`ravenemu-android-dev-arm64`. Depuis un checkout Git propre :

```sh
./gradlew :android:assembleDebug :android:collectDebugCorrespondingSources
python3 scripts/test_package_native_sources.py
python3 scripts/package_native_sources.py
```

L'archive inclut les fichiers suivis RavenEmu et RavenCore du checkout ARM64
utilisé par CMake, les licences et scripts, les sources Maven/POM et les
conteneurs de ressources des dépendances résolues. Elle exclut les fichiers
non suivis et les caches. Avant un paquet local, les nouveaux fichiers source
doivent donc être suivis par Git. Le manifeste enregistre révisions,
modifications et empreintes, dont celle de l'APK. `README-SOURCES.txt` indique
comment reconstruire sans `.git`, avec le RavenCore fourni, grâce aux
propriétés Gradle `ravenSourceRevision`, `ravenSourceModified` et
`ravenCoreSource`. Les outils Android et dépendances Gradle restent à résoudre.

Les tests de files audio et de GLES simulé vérifient les bornes, la concurrence,
les purges, les doublons d'image, changements de taille/contexte et reprises
après erreur. Ils ne mesurent pas la latence ni les performances d'un téléphone.
Le frontend complet, les resamplers sinc et la planification RetroArch ne sont
pas portés. La reconnexion automatique après changement de route audio reste
à implémenter et à tester.

## Cadence native et fréquence du core

Le frontend C calcule les images à exécuter depuis la fréquence Libretro
annoncée par RavenCore, avec une horloge monotone. Java transmet chaque rendu
et les raisons de pause ; il n'impose plus un délai de 60 Hz qui repart de
l'instant du dernier rendu. Le reste de période est conservé : des rendus à
90 ou 120 Hz ne changent pas la vitesse du jeu, et un écran plus lent peut
exécuter plusieurs images du core avant de présenter la dernière.

Le rattrapage est limité à trois images par rendu. Un retard plus grand
abandonne l'excédent ; il ne provoque pas une longue boucle de récupération.
Pause/reprise, changement de ROM, restauration d'état et recréation du contexte
remettent l'horloge à zéro, sans récupérer le temps passé dans les menus.
Les entrées sont échantillonnées à chaque image émulée et l'audio de chacune
est transmis au pilote. Un rendu sans image émulée réutilise la texture.
L'autosauvegarde périodique de SRAM attend également une image réellement
exécutée. Une fréquence absente ou hors plage prise en charge (1–240 Hz)
refuse le chargement plutôt que de supposer 60 Hz.

Les tests simulent plusieurs fréquences de core et des écrans de 29,97 à 144 Hz,
du jitter, une heure d'exécution, de grands timestamps, des retards et reprises.
Ils prouvent la cadence calculée ; les FPS, la consommation et la latence sur
téléphone restent à mesurer. Cette horloge suit le temps monotone : elle
n'est pas encore asservie à l'horloge du périphérique audio, et ne change pas
la fréquence de l'écran Android. Les changements de timing Libretro pendant
une partie restent à prendre en charge ; la fréquence est lue au chargement.

## Réglages audio et vidéo persistants

La bibliothèque ouvre les pages **Audio** et **Vidéo** depuis Paramètres.
Le menu de pause propose aussi **Paramètres audio et vidéo** ; le retour au
lecteur conserve la pause du panneau jusqu'à « Reprendre le jeu ».
Les pages Java reprennent la barre, les groupes, libellés et couleurs de la
bêta pour les cinq réglages raccordés :

- **Activer le son** et **Volume** (0–100 %) : couper le son conserve la
  cadence d'émulation. Le volume choisi reste mémorisé quand le son est coupé.
- **Pixels entiers** : plus grand multiple entier qui tient dans la surface,
  centré ; si même 1× ne tient pas, retour au cadrage proportionnel sans rognage.
- **Luminosité** (−50 à +50) et **Contraste** (50–150 %) : formule de la bêta
  appliquée dans le shader, sans nouveau traitement CPU des images ni transfert
  de texture pour un changement de réglage seul.

« Rétablir l'affichage d'origine » remet seulement les trois valeurs vidéo
à leurs défauts, sans changer le son. Les préférences privées de la
prévisualisation sont conservées après recréation/redémarrage ; aucune
préférence de la bêta n'est lue. Les valeurs invalides sont bornées et les
types incorrects reviennent au défaut du champ concerné.

Le lecteur recharge les préférences à la reprise et les applique sur GL
avant de retirer la pause de premier plan. Mute et gain restent distincts
de cette pause. Un changement audio purge les anciens échantillons de la
file logicielle ; les échantillons déjà soumis au périphérique peuvent
encore finir de jouer. Les miniatures d'états gardent les pixels bruts du core.

Les tests C simulés vérifient gain, silence, purges, concurrence et géométrie.
Le test **GLES logiciel réel** compare aussi les pixels du shader à la formule
de la bêta, avec image d'origine exacte et tolérance d'un niveau pour les
ajustements. Sur les GPU limités à `mediump`, une légère différence d'arrondi
reste possible. Sur un hôte avec Python 3, EGL et OpenGL/Mesa :

```sh
cmake -S frontend -B build/native-frontend -DRAVEN_VIDEO_PIXEL_TESTS=ON
cmake --build build/native-frontend --target raven_native_video_pixels
ctest --test-dir build/native-frontend -R raven_native_video_pixels --output-on-failure
```

Ce test est activé dans la CI native. Il ne remplace pas les essais de son,
de rendu et de navigation sur téléphone. Profils DMG/Pocket, correction LCD,
mélange d'images et autres pages de paramètres restent à porter.

## Current limits (intentional)

This is an integration prototype, not a release candidate.
- Audio is played through native AAudio (the core's reported sample rate is
  linearly resampled to the device stream rate, normally 48 kHz). Low-latency
  hardware behavior and underruns still need testing on phones.
- SRAM (`.srm`) is captured at app pause, state save and approximately every
  30 seconds while playing, then written atomically off the GL/UI threads.
  Four state slots are saved/restored manually, with core/ROM compatibility
  checked before loading. SRAM and states are independent.
- Saves and the newly imported ROM library are **isolated** from the original
  RavenEmu beta. Automatic import of that beta's ROMs and saves is not
  implemented. Storage is private; removing the test application deletes it.
- The Java home screen uses the RavenEmu-style grid/list; file selection
  remains Android SAF. The Java pause sheet controls the C pause/audio gate.
- No RetroAchievements, wireless/local link cable or Delta skins yet.
- Cover images must be manually selected by the user from local documents.
  Import is limited to 8 MiB and 4096 x 4096 input, decoded into 512 px PNG
  thumbnails. No artwork is bundled or fetched online.
- Native pacing follows the core FPS, with bounded catch-up. It is not yet
  synchronized to the audio device clock. GLSurfaceView still renders
  continuously at the display refresh rate.
- Video uses OpenGL ES 2 in C; shader architecture and the advanced pause
  options remain incomplete.
- `RavenCore` still owns game emulation; no engine source has been duplicated.
- Only one Libretro core instance may exist at once.
- The Android APK and gameplay have to be validated on physical hardware;
  passing CI shows only that it compiled.

The existing `androidApp/`, `iosApp/`, app ID and saves are untouched.

## Structure actuelle

- `src/main/java/com/ravenemu/app/` : application Java, bibliothèque, lecteur, stockage et réglages.
- `src/main/cpp/jni/` : liaison JNI.
- `src/main/cpp/hardware/audio/` : conversion PCM et pilote AAudio.
- `src/main/cpp/hardware/video/gles2/` : pilote graphique GLES2.
- `src/main/cpp/hardware/input/` : commandes natives.
- `src/main/cpp/media/` : captures d'états.
- `../../tests/hardware/` : tests hôtes des adaptateurs.
- `../../legal/attributions/` : provenance des sources adaptées.

L'identifiant de développement est `com.ravenemu.dev`, distinct de la bêta et de l'ancienne prévisualisation. Les données privées de l'ancienne prévisualisation ne sont ni migrées ni supprimées automatiquement.

## Sous-packages Java

Les sources Java sont organisées en vrais packages sous `src/main/java/com/ravenemu/app/` :

- `ui/library/` : accueil, liste/grille et présentation des jeux ;
- `ui/common/` : composant de fiche glissante partagé ;
- `ui/player/` : écran de lecture, contrôles et menu de pause ;
- `ui/settings/` : réglages et écran des licences ;
- `data/library/` : ROMs, jaquettes et métadonnées ;
- `data/saves/` : sauvegardes SRAM, états et miniatures ;
- `settings/` : modèle et persistance des préférences de lecture ;
- `bridge/` : contrat JNI Java ;
- `platform/` : adaptation aux insets Android.

Les tests JVM reflètent les mêmes packages. Le manifeste déclare les classes d'activités avec leurs sous-packages ; le pont C utilise le préfixe JNI correspondant à `bridge.NativeBridge`.

## Icône de l'APK de développement

L'APK `com.ravenemu.dev` utilise le logo Raven fourni pour la migration dans
`src/main/res/drawable-nodpi/ravenemu_launcher.webp`. Le lanceur Android
résout `@mipmap/ic_launcher` vers une icône adaptative sur Android 8.0+.
Ce changement est limité à l'application de migration ; l'icône de
`androidApp/` (bêta) n'est pas modifiée.
