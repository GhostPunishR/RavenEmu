from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text()


def write(path: str, text: str) -> None:
    file = ROOT / path
    file.parent.mkdir(parents=True, exist_ok=True)
    file.write_text(text)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: motif attendu 1 fois, trouvé {count}")
    return text.replace(old, new, 1)

# Java JNI contract.
path = "native/jni/src/main/java/com/ravenemu/nativebridge/NativeCoreBridge.java"
text = read(path)
text = replace_once(
    text,
    "    public static native int readAudio(long handle, short[] destination);\n    public static native int framebufferFormat(long handle);",
    "    public static native int readAudio(long handle, short[] destination);\n    public static native boolean rumbleActive(long handle);\n    public static native int framebufferFormat(long handle);",
    "NativeCoreBridge rumble",
)
write(path, text)

# JNI C++ implementation.
path = "native/jni/src/jni_bridge.cpp"
text = read(path)
needle = '''extern "C" JNIEXPORT jint JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_framebufferFormat(
'''
insert = '''extern "C" JNIEXPORT jboolean JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_rumbleActive(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        return static_cast<jboolean>(
            core_from(handle).rumble_active() ? JNI_TRUE : JNI_FALSE
        );
    });
}

'''
if text.count(needle) != 1:
    raise RuntimeError("JNI rumble insertion point introuvable")
text = text.replace(needle, insert + needle, 1)
write(path, text)

# Kotlin public engine API.
path = "engine/api/src/main/kotlin/com/ravenemu/emulation/api/EmulatorCore.kt"
text = read(path)
text = replace_once(
    text,
    "    fun readAudio(buffer: ShortArray): Int\n\n    /** `true` si la cartouche chargée possède une RAM sauvegardée par pile. */",
    "    fun readAudio(buffer: ShortArray): Int\n\n    /** État instantané du moteur de vibration matériel de la cartouche. */\n    val rumbleActive: Boolean get() = false\n\n    /** `true` si la cartouche chargée possède une RAM sauvegardée par pile. */",
    "EmulatorCore rumble",
)
write(path, text)

# Kotlin GB adapter.
path = "engine/runtime/src/main/kotlin/com/ravenemu/core/gb/GameBoyCore.kt"
text = read(path)
text = replace_once(
    text,
    "    override fun readAudio(buffer: ShortArray): Int =\n        NativeCoreBridge.readAudio(handle(), buffer)\n\n    override val hasBatteryRam: Boolean",
    "    override fun readAudio(buffer: ShortArray): Int =\n        NativeCoreBridge.readAudio(handle(), buffer)\n\n    override val rumbleActive: Boolean\n        get() = native.takeIf { it.isInitialized() }\n            ?.let { NativeCoreBridge.rumbleActive(it.value.value()) }\n            ?: false\n\n    override val hasBatteryRam: Boolean",
    "GameBoyCore rumble",
)
write(path, text)

# Session engine: neutral output sink, edge-triggered.
path = "engine/session/src/main/kotlin/com/ravenemu/emulation/api/session/EmulationSession.kt"
text = read(path)
text = replace_once(
    text,
    "    private val audioSink: AudioSink? = null,\n    /**",
    "    private val audioSink: AudioSink? = null,\n    private val rumbleSink: RumbleSink? = null,\n    /**",
    "Session constructor rumble",
)
text = replace_once(
    text,
    "    interface AudioSink {",
    "    interface RumbleSink {\n        /** Active ou coupe la vibration continue demandée par la cartouche. */\n        fun setActive(active: Boolean)\n        fun release() { setActive(false) }\n    }\n\n    interface AudioSink {",
    "Session rumble interface",
)
text = replace_once(
    text,
    "    fun pause() {\n        paused = true\n        audioSink?.pause()\n    }",
    "    fun pause() {\n        paused = true\n        audioSink?.pause()\n        rumbleSink?.setActive(false)\n    }",
    "Session pause rumble",
)
text = replace_once(
    text,
    "        audioSink?.release()\n        return StopResult.CLEAN",
    "        audioSink?.release()\n        rumbleSink?.release()\n        return StopResult.CLEAN",
    "Session stop rumble",
)
text = replace_once(
    text,
    "        var lastBatteryCheck = System.nanoTime()\n\n        while (running) {",
    "        var lastBatteryCheck = System.nanoTime()\n        var lastRumbleActive = false\n\n        while (running) {",
    "Session rumble state",
)
text = replace_once(
    text,
    "            core.runFrame(framebuffer, renderVideo)\n            val frameWorkNanos = System.nanoTime() - workStart",
    "            core.runFrame(framebuffer, renderVideo)\n            val rumbleActive = core.rumbleActive\n            if (rumbleActive != lastRumbleActive) {\n                rumbleSink?.setActive(rumbleActive)\n                lastRumbleActive = rumbleActive\n            }\n            val frameWorkNanos = System.nanoTime() - workStart",
    "Session frame rumble",
)
text = replace_once(
    text,
    "        drainCommands()\n        saveBatteryIfDirty()\n    }",
    "        rumbleSink?.setActive(false)\n        drainCommands()\n        saveBatteryIfDirty()\n    }",
    "Session exit rumble",
)
write(path, text)

# Android platform implementation.
write(
    "platform/android/vibration/src/main/kotlin/com/ravenemu/platform/vibration/AndroidRumbleSink.kt",
    '''package com.ravenemu.platform.vibration

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.ravenemu.emulation.api.session.EmulationSession

/** Sortie Android du moteur de vibration des cartouches (MBC5 rumble). */
class AndroidRumbleSink(context: Context) : EmulationSession.RumbleSink {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var active = false

    override fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        if (active) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, PULSE_MILLIS),
                    0,
                ),
            )
        } else {
            vibrator.cancel()
        }
    }

    override fun release() {
        active = false
        vibrator.cancel()
    }

    private companion object {
        const val PULSE_MILLIS = 1_000L
    }
}
''',
)

path = "platform/android/vibration/build.gradle.kts"
text = read(path)
if "dependencies {" not in text:
    text += '''

dependencies {
    implementation(project(":engine:session"))
}
'''
write(path, text)

write(
    "platform/android/vibration/src/main/AndroidManifest.xml",
    '''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.VIBRATE" />
</manifest>
''',
)

# App composition: provide the platform sink to the session.
path = "app/android/src/main/kotlin/com/ravenemu/app/emulation/EmulationActivity.kt"
text = read(path)
text = replace_once(
    text,
    "import com.ravenemu.storage.SnapshotStore\n",
    "import com.ravenemu.storage.SnapshotStore\nimport com.ravenemu.platform.vibration.AndroidRumbleSink\n",
    "Activity rumble import",
)
text = replace_once(
    text,
    "            this,\n            audioSink,\n            // Le groupe d'ordonnancement Android décide, sur un processeur",
    "            this,\n            audioSink,\n            AndroidRumbleSink(this),\n            // Le groupe d'ordonnancement Android décide, sur un processeur",
    "Activity rumble sink",
)
write(path, text)

# Session unit test: output changes only on edges and is stopped on pause/stop.
path = "engine/session/src/test/kotlin/com/ravenemu/emulation/api/session/EmulationSessionStopTest.kt"
text = read(path)
# Keep existing tests untouched; a separate focused test avoids relying on internal fixtures.
write(path, text)
write(
    "engine/session/src/test/kotlin/com/ravenemu/emulation/api/session/RumbleSinkTest.kt",
    '''package com.ravenemu.emulation.api.session

import com.ravenemu.emulation.api.AudioSpec
import com.ravenemu.emulation.api.BatteryRamSnapshot
import com.ravenemu.emulation.api.ConsoleType
import com.ravenemu.emulation.api.EmulatorButton
import com.ravenemu.emulation.api.EmulatorCore
import com.ravenemu.emulation.api.VideoSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RumbleSinkTest {
    @Test
    fun `la session relaie et coupe le rumble`() {
        val core = FakeCore()
        val on = CountDownLatch(1)
        val off = CountDownLatch(1)
        val sink = object : EmulationSession.RumbleSink {
            @Volatile var active = false
            override fun setActive(active: Boolean) {
                this.active = active
                if (active) on.countDown() else off.countDown()
            }
        }
        val session = EmulationSession(core, NoopCallbacks, rumbleSink = sink)
        session.start()
        core.rumble = true
        assertTrue(on.await(1, TimeUnit.SECONDS), "le rumble actif n'a pas été relayé")
        assertTrue(sink.active)
        session.pause()
        assertFalse(sink.active)
        session.stop()
        assertTrue(off.await(1, TimeUnit.SECONDS))
    }

    private class FakeCore : EmulatorCore {
        @Volatile var rumble = false
        override val console = ConsoleType.GAME_BOY
        override val video = VideoSpec(1, 1, 120.0)
        override val audio = AudioSpec(32_768, 2)
        override val rumbleActive get() = rumble
        override fun loadRom(rom: ByteArray, batteryRam: ByteArray?) = Unit
        override fun reset() = Unit
        override fun runFrame(framebuffer: IntArray) { framebuffer[0] = 0 }
        override fun setButton(button: EmulatorButton, pressed: Boolean) = Unit
        override fun readAudio(buffer: ShortArray) = 0
        override val hasBatteryRam = false
        override val batteryRamDirty = false
        override fun snapshotBatteryRam(): BatteryRamSnapshot? = null
        override fun acknowledgeBatteryRamSaved(generation: Long) = Unit
        override fun saveState() = byteArrayOf()
        override fun loadState(state: ByteArray) = Unit
    }

    private object NoopCallbacks : EmulationSession.Callbacks {
        override fun onFrame(framebuffer: IntArray) = Unit
        override fun onStats(fps: Double, frameTimeMs: Double) = Unit
        override fun onBatterySave(data: ByteArray) = true
    }
}
''',
)

print("GBC rumble bridge applied")
