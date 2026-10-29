# RavenEmu Android Native Preview (Java + C + C++ / Libretro)

This is an **independent experimental Android application** that does not
replace the released RavenEmu beta. Application ID: `com.ravenemu.nativepreview`.
Requires Android 8.0+ and an **ARM64** phone (arm64-v8a).

## Interface d'origine sans Kotlin dans le module migré

Le lancement ouvre `NativeHomeActivity.java`, une adaptation en **Java
Android Views** du design déjà présent dans la bêta RavenEmu. Les couleurs
(`#0B0C12`, `#191B28`, `#A78BFA`), la barre d'outils avec ⚙ / + / ⋮,
le champ de recherche, la grille/listes de jaquettes, les onglets par console,
le pied de page « RavenEmu By GhostPunishR » et la fiche du jeu reprennent
le code source de référence `shared/theme/RavenColors.kt`,
`shared/library/LibraryComponents.kt` et `LibraryDialogs.kt` **pour
l'apparence seulement**. Ces fichiers Kotlin ne sont pas compilés ni utilisés
dans `androidNative`.

La couche Android de migration utilise uniquement **Java**, le frontend et
le rendu restent **C**, et RavenCore reste **C++20**. Les fichiers
`build.gradle.kts` sont seulement le langage de configuration Gradle déjà
utilisé par le dépôt ; aucune source Kotlin ou Compose n'est ajoutée au
nouveau module.

La bibliothèque Java restaure déjà les écrans centraux, mais ce n'est **pas
encore une parité visuelle totale** : le menu de jeu C et les réglages non
raccordés aux cores doivent encore être alignés.

## Calibrage sur les barres du téléphone

Android 15 (target SDK 35) impose le rendu bord à bord : la barre d'état
(heure, réseau, batterie) et la navigation système à trois boutons ou par
gestes peuvent chevaucher l'app si elle n'applique pas ses insets.

Les activités Java `NativeHomeActivity` et `MainActivity` utilisent désormais
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

Le pilote C `native_input.c` conserve les fronts d'appui jusqu'à une image
émulée, même si le doigt est déjà relevé. Son instantané reste stable pendant
cette image ; un simple rafraîchissement vidéo ne consomme pas l'appui.
La validation tactile et visuelle sur téléphone reste nécessaire avant de
conclure à une parité avec la bêta.

## Architecture
- `NativeHomeActivity.java` : écran bibliothèque d'accueil et navigation en Java.
- `RavenLibraryAdapter.java` : grille/liste de jaquettes selon le style RavenEmu.
- `src/main/res/values/styles.xml` : couleurs du thème original.


- `MainActivity.java` : lecteur Java/JNI lancé depuis la fiche « Jouer »,
  avec la barre de jeu, contrôles tactiles, boucle GL et sauvegardes natives.
- `RomLibrary.java`: offline private GB/GBC/GBA catalogue, SHA-256 identities,
  verified ROM copies and atomic JSON index. No broad storage permission.
- `CoverRepository.java`: local-only cover import via SAF, bounded PNG
  conversion (maximum 512 px edge, 8 MiB source) and atomic private storage
  by ROM SHA-256; removal affects no ROMs, saves or other files.
- `CoverAdapter.java`: image thumbnails decoded on a background executor,
  with a bounded 32-image LRU cache and text fallback.
- `NativeBridge.java`: typed JNI entry points; `setButtons` and `clearButtons`
  update atomic input from the UI. Core calls use the GL thread while running;
  lifecycle cleanup accesses the session only after that thread is paused.
- `src/main/cpp/bridge.c`: Libretro session management, frame conversion to
  RGBA8888, OpenGL ES 2 texture drawing, atomic joypad input.
- `src/main/cpp/native_input.c`: held/pending input masks and one stable
  joypad snapshot per emulated frame.
- `src/main/cpp/native_menu.c`: GLES2 pause/menu overlay, pixel glyphs,
  highlighted navigation and C state machine. Four actions: resume, library,
  save and load.
- `../frontend/`: C Libretro host, reused without code duplication.
- `GhostPunishR/RavenCore`: the pinned C++20 cores, compiled as three
  independent `.so` files.

## Build

```bash
./gradlew :androidNative:assembleDebug --stacktrace
```

APK: `androidNative/build/outputs/apk/debug/androidNative-debug.apk`.

Run `./gradlew :androidNative:testDebugUnitTest` for identity/path, insets,
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
The library dialog offers **text search** (filenames and cartridge titles,
accent-insensitive), **GB/GBC/GBA filtering** and sorting by name or system.
Long-press an entry to see the cartridge header title, ROM size and SHA-256.
Select **Choisir jaquette** to choose a JPEG/PNG or another Android-decodable
image from your phone. The app downscales and stores it privately; **Supprimer
jaquette** removes only that local image. This does not download box art or
change game ROMs and saves. Android can display the cover next to the title,
with an offline placeholder for games without artwork.
Header metadata is read locally without any online API.
**Menu C** displays the native pause menu above the game. Move with UP/DOWN
(on-screen or on a gamepad) and activate with A; use the library action to open
the accessible Android list. The Android document picker remains native to
Android, while menu rendering and selection state live in C.
No ROM or BIOS is bundled; no broad storage permission is requested.

## Current limits (intentional)

This is an integration prototype, not a release candidate.
- Audio is played through native AAudio (the core's reported sample rate is
  linearly resampled to the device stream rate, normally 48 kHz). Low-latency
  hardware behavior and underruns still need testing on phones.
- SRAM (`.srm`) is saved atomically on app pause, ROM change, quick-save and
  approximately every 30 seconds while playing. Save-state (`.state`) can
  be written/restored manually; incompatible core versions may reject a state.
- Saves and the newly imported ROM library are **isolated** from the original
  RavenEmu beta. Automatic import of that beta's ROMs and saves is not
  implemented. Storage is private; removing the test application deletes it.
- The Java home screen uses the RavenEmu-style grid/list. The player also
  offers a searchable Android library dialog; file selection remains Android
  SAF. The pause menu state, pixel-font rendering and navigation are native C.
- No RetroAchievements, wireless/local link cable or Delta skins yet.
- Cover images must be manually selected by the user from local documents.
  Import is limited to 8 MiB and 4096 x 4096 input, decoded into 512 px PNG
  thumbnails. No artwork is bundled or fetched online.
- A 60 Hz best-effort loop is used instead of the precise core FPS and audio
  clock. The GLSurfaceView is continuous, and may run at display refresh speed.
- Video and the first pause menu are OpenGL ES 2 native C paths, but this is
  not yet a complete RetroArch-style menu or final shader architecture.
- `RavenCore` still owns game emulation; no engine source has been duplicated.
- Only one Libretro core instance may exist at once.
- The Android APK and gameplay have to be validated on physical hardware;
  passing CI shows only that it compiled.

The existing `androidApp/`, `iosApp/`, app ID and saves are untouched.
