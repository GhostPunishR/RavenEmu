#include <jni.h>
#include <ravenemu/bridge.h>
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <limits>
#include <vector>

namespace {
ravenemu_bridge* handle(jlong pointer) {
    return reinterpret_cast<ravenemu_bridge*>(static_cast<std::uintptr_t>(pointer));
}
jlong to_long(ravenemu_bridge* pointer) {
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(pointer));
}
ravenemu_link* link(jlong pointer) {
    return reinterpret_cast<ravenemu_link*>(static_cast<std::uintptr_t>(pointer));
}

// Câble vers un autre appareil : RavenCore remet chaque message à l'objet
// Kotlin, qui l'envoie sur le réseau. Appelé par le fil d'émulation ou par
// celui qui branche le câble.
struct RemoteLink {
    JavaVM* vm = nullptr;
    jobject sink = nullptr;  // référence globale
    jmethodID send = nullptr;
    ravenemu_remote_link* cable = nullptr;
};
RemoteLink* remote(jlong pointer) {
    return reinterpret_cast<RemoteLink*>(static_cast<std::uintptr_t>(pointer));
}
void send_to_kotlin(void* user, const uint8_t* message, size_t size) {
    auto* link = static_cast<RemoteLink*>(user);
    // Toujours un fil Java, déjà attaché à la machine virtuelle.
    JNIEnv* env = nullptr;
    if (link->vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return;
    auto bytes = env->NewByteArray(static_cast<jsize>(size));
    if (bytes) {
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(message));
        env->CallVoidMethod(link->sink, link->send, bytes);
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(bytes);
    } else if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}
}

extern "C" {
JNIEXPORT jlong JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeCreate(JNIEnv*, jobject, jint system) {
    return to_long(ravenemu_create(system));
}
JNIEXPORT void JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeDestroy(JNIEnv*, jobject, jlong pointer) {
    ravenemu_destroy(handle(pointer));
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeLoad(
    JNIEnv* env, jobject, jlong pointer, jbyteArray rom, jbyteArray battery
) {
    if (!rom) return JNI_FALSE;
    jbyte* data = env->GetByteArrayElements(rom, nullptr);
    if (!data) return JNI_FALSE;
    jbyte* saved = battery ? env->GetByteArrayElements(battery, nullptr) : nullptr;
    if (battery && !saved) {
        env->ReleaseByteArrayElements(rom, data, JNI_ABORT);
        return JNI_FALSE;
    }
    const auto ok = ravenemu_load(
        handle(pointer),
        reinterpret_cast<const uint8_t*>(data),
        static_cast<size_t>(env->GetArrayLength(rom)),
        reinterpret_cast<const uint8_t*>(saved),
        battery ? static_cast<size_t>(env->GetArrayLength(battery)) : 0U
    );
    if (saved) env->ReleaseByteArrayElements(battery, saved, JNI_ABORT);
    env->ReleaseByteArrayElements(rom, data, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeWidth(JNIEnv*, jobject, jlong pointer) {
    return ravenemu_video_width(handle(pointer));
}
JNIEXPORT jint JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeHeight(JNIEnv*, jobject, jlong pointer) {
    return ravenemu_video_height(handle(pointer));
}
JNIEXPORT jdouble JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeFps(JNIEnv*, jobject, jlong pointer) {
    return ravenemu_video_fps(handle(pointer));
}
JNIEXPORT jint JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeAudioRate(JNIEnv*, jobject, jlong pointer) {
    return ravenemu_audio_rate(handle(pointer));
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeFrame(
    JNIEnv* env, jobject, jlong pointer, jintArray output
) {
    if (!output) return JNI_FALSE;
    jint* pixels = env->GetIntArrayElements(output, nullptr);
    if (!pixels) return JNI_FALSE;
    const auto ok = ravenemu_run_frame(
        handle(pointer),
        reinterpret_cast<uint32_t*>(pixels),
        static_cast<size_t>(env->GetArrayLength(output)));
    env->ReleaseIntArrayElements(output, pixels, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeReadAudio(
    JNIEnv* env, jobject, jlong pointer, jshortArray output
) {
    if (!output) return 0;
    jshort* samples = env->GetShortArrayElements(output, nullptr);
    if (!samples) return 0;
    const auto count = ravenemu_read_audio(
        handle(pointer),
        reinterpret_cast<int16_t*>(samples),
        static_cast<size_t>(env->GetArrayLength(output)));
    env->ReleaseShortArrayElements(output, samples, 0);
    return static_cast<jint>(count);
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeButton(
    JNIEnv*, jobject, jlong pointer, jint button, jboolean down
) {
    return ravenemu_set_button(handle(pointer), button, down == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeReset(JNIEnv*, jobject, jlong pointer) {
    return ravenemu_reset(handle(pointer)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jstring JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(ravenemu_last_error());
}
// Declared by NativeAchievementsBridge: the engine version in the User-Agent.
JNIEXPORT jstring JNICALL
Java_com_ravenemu_android_retro_NativeAchievementsBridge_engineVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(ravenemu_version());
}
JNIEXPORT jint JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeStateSize(JNIEnv*, jobject, jlong pointer) {
    const auto n = ravenemu_state_size(handle(pointer));
    return n <= static_cast<size_t>(std::numeric_limits<jint>::max()) ? static_cast<jint>(n) : 0;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeSaveState(
    JNIEnv* env, jobject, jlong pointer, jbyteArray output
) {
    if (!output) return JNI_FALSE;
    auto* bytes = env->GetByteArrayElements(output, nullptr);
    if (!bytes) return JNI_FALSE;
    const auto ok = ravenemu_save_state(handle(pointer),
        reinterpret_cast<uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(output)));
    env->ReleaseByteArrayElements(output, bytes, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeLoadState(
    JNIEnv* env, jobject, jlong pointer, jbyteArray input
) {
    if (!input) return JNI_FALSE;
    auto* bytes = env->GetByteArrayElements(input, nullptr);
    if (!bytes) return JNI_FALSE;
    const auto ok = ravenemu_load_state(handle(pointer),
        reinterpret_cast<const uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(input)));
    env->ReleaseByteArrayElements(input, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeRumbleActive(JNIEnv*, jobject, jlong pointer) {
    return ravenemu_rumble_active(handle(pointer)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeSensors(JNIEnv*, jobject, jlong pointer) {
    return ravenemu_sensors(handle(pointer));
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeSetSolarLight(
    JNIEnv*, jobject, jlong pointer, jint level
) {
    return ravenemu_set_solar_light(handle(pointer), level) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeSetRotation(
    JNIEnv*, jobject, jlong pointer, jint speed
) {
    return ravenemu_set_rotation(handle(pointer), speed) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeSetTilt(
    JNIEnv*, jobject, jlong pointer, jint x, jint y
) {
    return ravenemu_set_tilt(handle(pointer), x, y) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeBatteryDirty(JNIEnv*, jobject, jlong pointer) {
    return ravenemu_battery_dirty(handle(pointer)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeCopyBatteryGeneration(
    JNIEnv* env, jobject, jlong pointer, jbyteArray output, jlongArray generation_out
) {
    if (!output || !generation_out || env->GetArrayLength(generation_out) < 1) return JNI_FALSE;
    auto* bytes = env->GetByteArrayElements(output, nullptr);
    if (!bytes) return JNI_FALSE;
    std::uint64_t generation = 0;
    const auto ok = ravenemu_copy_battery_generation(
        handle(pointer), reinterpret_cast<std::uint8_t*>(bytes),
        static_cast<std::size_t>(env->GetArrayLength(output)), &generation);
    env->ReleaseByteArrayElements(output, bytes, ok ? 0 : JNI_ABORT);
    if (!ok) return JNI_FALSE;
    const auto value = static_cast<jlong>(generation);
    env->SetLongArrayRegion(generation_out, 0, 1, &value);
    return env->ExceptionCheck() ? JNI_FALSE : JNI_TRUE;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeAcknowledgeBattery(
    JNIEnv*, jobject, jlong pointer, jlong generation
) {
    return ravenemu_acknowledge_battery(
        handle(pointer), static_cast<std::uint64_t>(generation)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeBatterySize(JNIEnv*, jobject, jlong pointer) {
    const auto n = ravenemu_battery_size(handle(pointer));
    return n <= static_cast<size_t>(std::numeric_limits<jint>::max()) ? static_cast<jint>(n) : 0;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeCopyBattery(
    JNIEnv* env, jobject, jlong pointer, jbyteArray output
) {
    if (!output) return JNI_FALSE;
    auto* bytes = env->GetByteArrayElements(output, nullptr);
    if (!bytes) return JNI_FALSE;
    const auto ok = ravenemu_copy_battery(handle(pointer),
        reinterpret_cast<uint8_t*>(bytes), static_cast<size_t>(env->GetArrayLength(output)));
    env->ReleaseByteArrayElements(output, bytes, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jint JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeAchievementMemory(
    JNIEnv* env, jobject, jlong pointer, jint address, jbyteArray output
) {
    if (!output || address < 0) return 0;
    const jsize capacity = env->GetArrayLength(output);
    if (capacity <= 0 || capacity > 0x58000) return 0;
    auto* bytes = env->GetByteArrayElements(output, nullptr);
    if (!bytes) return 0;
    const auto count = ravenemu_read_achievement_memory(
        handle(pointer), static_cast<std::uint32_t>(address),
        reinterpret_cast<std::uint8_t*>(bytes), static_cast<std::size_t>(capacity));
    env->ReleaseByteArrayElements(output, bytes, count ? 0 : JNI_ABORT);
    return static_cast<jint>(count);
}

// Câble link entre les cœurs de deux jeux lancés côte à côte.
JNIEXPORT jlong JNICALL
Java_com_ravenemu_android_link_NativeLink_nativeCreate(JNIEnv* env, jobject, jlongArray handles) {
    if (!handles) return 0;
    const auto count = env->GetArrayLength(handles);
    std::vector<jlong> pointers(static_cast<std::size_t>(count));
    env->GetLongArrayRegion(handles, 0, count, pointers.data());
    std::vector<ravenemu_bridge*> bridges;
    for (const auto pointer : pointers) bridges.push_back(handle(pointer));
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(
        ravenemu_link_create(bridges.data(), bridges.size())));
}
JNIEXPORT void JNICALL
Java_com_ravenemu_android_link_NativeLink_nativeDestroy(JNIEnv*, jobject, jlong pointer) {
    ravenemu_link_destroy(link(pointer));
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_link_NativeLink_nativeFrame(
    JNIEnv* env, jobject, jlong pointer, jintArray first, jintArray second
) {
    if (!first || !second) return JNI_FALSE;
    const auto capacity = std::min(env->GetArrayLength(first), env->GetArrayLength(second));
    jint* first_pixels = env->GetIntArrayElements(first, nullptr);
    if (!first_pixels) return JNI_FALSE;
    jint* second_pixels = env->GetIntArrayElements(second, nullptr);
    if (!second_pixels) {
        env->ReleaseIntArrayElements(first, first_pixels, JNI_ABORT);
        return JNI_FALSE;
    }
    uint32_t* const frames[] = {
        reinterpret_cast<uint32_t*>(first_pixels), reinterpret_cast<uint32_t*>(second_pixels)};
    const auto ok = ravenemu_link_run_frame(link(pointer), frames, static_cast<size_t>(capacity));
    env->ReleaseIntArrayElements(second, second_pixels, ok ? 0 : JNI_ABORT);
    env->ReleaseIntArrayElements(first, first_pixels, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jstring JNICALL
Java_com_ravenemu_android_link_NativeLink_nativeLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(ravenemu_last_error());
}

// Le jeu se sert de son port link : le câble sans fil cherche un partenaire.
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_engine_NativeCore_nativeLinkWanted(JNIEnv*, jobject, jlong pointer) {
    return ravenemu_link_wanted(handle(pointer)) ? JNI_TRUE : JNI_FALSE;
}

// Câble link vers la console d'un autre appareil.
JNIEXPORT jlong JNICALL
Java_com_ravenemu_android_wireless_NativeRemoteLink_nativeCreate(
    JNIEnv* env, jobject, jlong core, jint player, jobject sink
) {
    if (!sink) return 0;
    auto type = env->GetObjectClass(sink);
    const auto send = env->GetMethodID(type, "send", "([B)V");
    env->DeleteLocalRef(type);
    if (!send) {
        env->ExceptionClear();
        return 0;
    }
    auto* link = new RemoteLink();
    env->GetJavaVM(&link->vm);
    link->sink = env->NewGlobalRef(sink);
    link->send = send;
    link->cable = ravenemu_remote_link_create(handle(core), player, send_to_kotlin, link);
    if (!link->cable) {
        env->DeleteGlobalRef(link->sink);
        delete link;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(link));
}
JNIEXPORT void JNICALL
Java_com_ravenemu_android_wireless_NativeRemoteLink_nativeDestroy(JNIEnv* env, jobject, jlong pointer) {
    auto* link = remote(pointer);
    if (!link) return;
    ravenemu_remote_link_destroy(link->cable);
    env->DeleteGlobalRef(link->sink);
    delete link;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_wireless_NativeRemoteLink_nativeReceive(JNIEnv* env, jobject, jlong pointer, jbyteArray message) {
    auto* link = remote(pointer);
    if (!link || !message) return JNI_FALSE;
    const auto size = env->GetArrayLength(message);
    // Un message du protocole tient en 14 octets.
    if (size <= 0 || size > 64) return JNI_FALSE;
    uint8_t bytes[64];
    env->GetByteArrayRegion(message, 0, size, reinterpret_cast<jbyte*>(bytes));
    return ravenemu_remote_link_receive(link->cable, bytes, static_cast<size_t>(size)) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL
Java_com_ravenemu_android_wireless_NativeRemoteLink_nativeSetConnected(JNIEnv*, jobject, jlong pointer, jboolean connected) {
    if (auto* link = remote(pointer)) ravenemu_remote_link_set_connected(link->cable, connected ? 1 : 0);
}
JNIEXPORT jint JNICALL
Java_com_ravenemu_android_wireless_NativeRemoteLink_nativeState(JNIEnv*, jobject, jlong pointer) {
    auto* link = remote(pointer);
    return link ? ravenemu_remote_link_state(link->cable) : RAVENEMU_REMOTE_UNPLUGGED;
}
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_android_wireless_NativeRemoteLink_nativeService(JNIEnv*, jobject, jlong pointer) {
    auto* link = remote(pointer);
    return link && ravenemu_remote_link_service(link->cable) ? JNI_TRUE : JNI_FALSE;
}
} // extern C
