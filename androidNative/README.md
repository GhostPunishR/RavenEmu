# RavenEmu Android Native Preview (Java + C + Libretro)

This is an **independent experimental Android application** that does not
replace the released RavenEmu beta. Application ID: `com.ravenemu.nativepreview`.
Requires Android 8.0+ and an **ARM64** phone (arm64-v8a).

## Architecture

- `MainActivity.java`: Java Activity, SAF import, accessible system library
  dialog, touch controls, GL thread scheduler, save-state actions and lifecycle.
- `RomLibrary.java`: offline private GB/GBC/GBA catalogue, SHA-256 identities,
  verified ROM copies and atomic JSON index. No broad storage permission.
- `NativeBridge.java`: typed JNI entry points (only `setButtons` is
  allowed on the UI thread; all other emulation calls use the GL thread).
- `src/main/cpp/bridge.c`: Libretro session management, frame conversion to
  RGBA8888, OpenGL ES 2 texture drawing, atomic joypad input.
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

Run `./gradlew :androidNative:testDebugUnitTest` for identity/path tests.\n\nThe build should package `libraven_android.so`,
`ravenemu_gb_libretro.so`, `ravenemu_gbc_libretro.so` and
`ravenemu_gba_libretro.so`. They are compiled for ARM64 only.

Launch **RavenEmu Native (Test)** and use **Importer ROM** to copy your
`.gb`, `.gbc` or `.gba` into the app's private library. **Bibliothèque**
reopens previously imported games, including after relaunching the app.\nThe library dialog offers **text search** (filenames and cartridge titles,\naccent-insensitive), **GB/GBC/GBA filtering** and sorting by name or system.\nLong-press an entry to see the cartridge header title, ROM size and SHA-256.\nHeader metadata is read locally without any online API.
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
- Library UI uses an accessible searchable Android dialog (file selection remains Android
  SAF); the pause menu state, pixel-font rendering and navigation are native C.
- No RetroAchievements, wireless/local link cable or Delta skins yet.
- A 60 Hz best-effort loop is used instead of the precise core FPS and audio
  clock. The GLSurfaceView is continuous, and may run at display refresh speed.
- Video and the first pause menu are OpenGL ES 2 native C paths, but this is
  not yet a complete RetroArch-style menu or final shader architecture.
- `RavenCore` still owns game emulation; no engine source has been duplicated.
- Only one Libretro core instance may exist at once.
- The Android APK and gameplay have to be validated on physical hardware;
  passing CI shows only that it compiled.

The existing `androidApp/`, `iosApp/`, app ID and saves are untouched.
