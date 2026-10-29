#!/usr/bin/env python3
"""Fail CI when the RavenEmu native migration regresses to mixed folders."""
from pathlib import Path

root = Path(__file__).resolve().parent.parent
required = [
    "frontend/src/libretro_host.c",
    "android/src/main/java/com/ravenemu/app/ui/library/HomeActivity.java",
    "android/src/main/java/com/ravenemu/app/ui/player/PlayerActivity.java",
    "android/src/main/cpp/jni/bridge.c",
    "android/src/main/cpp/hardware/audio/audio_pipeline.c",
    "android/src/main/cpp/hardware/audio/aaudio/aaudio_driver.c",
    "android/src/main/cpp/hardware/video/gles2/gles2_renderer.c",
    "android/src/main/cpp/hardware/input/input_driver.c",
    "android/src/main/cpp/media/state_thumbnail.c",
    "tests/hardware/CMakeLists.txt",
    "legal/attributions/retroarch.md",
]
missing = [p for p in required if not (root / p).is_file()]
if missing:
    raise SystemExit(f"Missing architectural components: {missing}")
for legacy in ["androidNative", "third_party"]:
    if (root / legacy).exists():
        raise SystemExit(f"Legacy directory must not be restored: {legacy}")
source = root / "android/src/main/java/com/ravenemu/app"
for file in source.rglob("*.java"):
    content = file.read_text(encoding="utf-8")
    if file.parent == source:
        raise SystemExit(f"Java file must be in a feature subpackage: {file}")
    expected = "com.ravenemu.app." + ".".join(file.parent.relative_to(source).parts)
    if not content.startswith("package " + expected + ";"):
        raise SystemExit(f"Unexpected Java namespace in {file}; expected {expected}")
bridge = (root / "android/src/main/cpp/jni/bridge.c").read_text(encoding="utf-8")
if "Java_com_ravenemu_app_bridge_NativeBridge_" not in bridge or "Java_com_ravenemu_app_NativeBridge_" in bridge:
    raise SystemExit("Outdated JNI package prefix")
gradle = (root / "android/build.gradle.kts").read_text(encoding="utf-8")
if 'namespace = "com.ravenemu.app"' not in gradle or 'applicationId = "com.ravenemu.dev"' not in gradle:
    raise SystemExit("Unexpected Java namespace or development application ID")
print("RavenEmu native layout validated")
