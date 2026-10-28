# RavenEmu Android Native Preview (Java + C + Libretro)

This is an **independent experimental Android application** that does not
replace the released RavenEmu beta. Application ID: `com.ravenemu.nativepreview`.
Requires Android 8.0+ and an **ARM64** phone (arm64-v8a).

## Architecture

- `MainActivity.java`: Java Activity, SAF document picker, touch controls,
  GL thread scheduler, save-state actions and the lifecycle.
- `NativeBridge.java`: typed JNI entry points (only `setButtons` is
  allowed on the UI thread; all other emulation calls use the GL thread).
- `src/main/cpp/bridge.c`: Libretro session management, frame conversion to
  RGBA8888, OpenGL ES 2 texture drawing, atomic joypad input.
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

Launch **RavenEmu Native (Test)**, select a personally provided
`.gb`, `.gbc` or `.gba` file with the Android file picker, then
use the on-screen buttons or a mapped physical gamepad.
No ROM or BIOS is bundled; no broad storage permission is requested.

## Current limits (intentional)

This is an integration prototype, not a release candidate.
- Audio is played through native AAudio (the core's reported sample rate is
  linearly resampled to the device stream rate, normally 48 kHz). Low-latency
  hardware behavior and underruns still need testing on phones.
- SRAM (`.srm`) is saved atomically on app pause, ROM change, quick-save and
  approximately every 30 seconds while playing. Save-state (`.state`) can
  be written/restored manually; incompatible core versions may reject a state.
- Saves are **new and isolated** from the original RavenEmu beta. Automatic
  import of older RavenEmu saves is not implemented.
- No RetroAchievements, wireless/local link cable or Delta skins yet.
- A 60 Hz best-effort loop is used instead of the precise core FPS and audio
  clock. The GLSurfaceView is continuous, and may run at display refresh speed.
- Video is OpenGL ES 2 on a native C texture path; it is not the final shader,
  overlay, menu, or renderer architecture.
- `RavenCore` still owns game emulation; no engine source has been duplicated.
- Only one Libretro core instance may exist at once.
- The Android APK and gameplay have to be validated on physical hardware;
  passing CI shows only that it compiled.

The existing `androidApp/`, `iosApp/`, app ID and saves are untouched.
