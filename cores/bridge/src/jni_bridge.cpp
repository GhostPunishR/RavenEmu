#include "ravenemu/core.hpp"

#include <jni.h>

#include <array>
#include <cstdint>
#include <limits>
#include <memory>
#include <optional>
#include <span>
#include <stdexcept>
#include <string>
#include <type_traits>
#include <utility>
#include <vector>

namespace {

using ravenemu::Core;

void throw_kotlin_exception(JNIEnv* env, const char* class_name, const char* message) {
    const auto klass = env->FindClass(class_name);
    if (klass == nullptr) return;
    const auto constructor = env->GetMethodID(
        klass,
        "<init>",
        "(Ljava/lang/String;Ljava/lang/Throwable;)V"
    );
    if (constructor == nullptr) {
        env->DeleteLocalRef(klass);
        return;
    }
    const auto text = env->NewStringUTF(message);
    if (text == nullptr) {
        env->DeleteLocalRef(klass);
        return;
    }
    const auto exception = static_cast<jthrowable>(
        env->NewObject(klass, constructor, text, nullptr)
    );
    if (exception != nullptr) env->Throw(exception);
    env->DeleteLocalRef(exception);
    env->DeleteLocalRef(text);
    env->DeleteLocalRef(klass);
}

void translate_exception(JNIEnv* env) noexcept {
    try {
        throw;
    } catch (const ravenemu::RomLoadError& error) {
        throw_kotlin_exception(
            env,
            "com/ravenemu/emulation/api/RomLoadException",
            error.what()
        );
    } catch (const ravenemu::SaveStateError& error) {
        throw_kotlin_exception(
            env,
            "com/ravenemu/emulation/api/SaveStateException",
            error.what()
        );
    } catch (const std::invalid_argument& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), error.what());
    } catch (const std::logic_error& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), error.what());
    } catch (...) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), "Erreur native RavenEmu inconnue");
    }
}

template <typename Result, typename Function>
Result guarded(JNIEnv* env, Result fallback, Function&& function) noexcept {
    try {
        return std::forward<Function>(function)();
    } catch (...) {
        translate_exception(env);
        return fallback;
    }
}

template <typename Function>
void guarded_void(JNIEnv* env, Function&& function) noexcept {
    try {
        std::forward<Function>(function)();
    } catch (...) {
        translate_exception(env);
    }
}

Core& core_from(jlong handle) {
    if (handle == 0) throw std::logic_error("Cœur natif RavenEmu fermé");
    return *reinterpret_cast<Core*>(static_cast<std::uintptr_t>(handle));
}

std::optional<ravenemu::GbaSaveType> gba_save_type_from(jint value) {
    if (value < 0) return std::nullopt;
    if (value > static_cast<jint>(ravenemu::GbaSaveType::eeprom_8k)) {
        throw std::invalid_argument("Type de sauvegarde GBA inconnu");
    }
    return static_cast<ravenemu::GbaSaveType>(value);
}

std::vector<std::uint8_t> read_bytes(JNIEnv* env, jbyteArray source, bool nullable) {
    if (source == nullptr) {
        if (nullable) return {};
        throw std::invalid_argument("Tableau d'octets absent");
    }
    const auto length = env->GetArrayLength(source);
    std::vector<std::uint8_t> result(static_cast<std::size_t>(length));
    if (length > 0) {
        env->GetByteArrayRegion(
            source,
            0,
            length,
            reinterpret_cast<jbyte*>(result.data())
        );
        if (env->ExceptionCheck()) throw std::runtime_error("Lecture d'un tableau Java impossible");
    }
    return result;
}

jbyteArray make_byte_array(JNIEnv* env, std::span<const std::uint8_t> source) {
    if (source.size() > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        throw std::length_error("Tableau natif trop volumineux");
    }
    const auto result = env->NewByteArray(static_cast<jsize>(source.size()));
    if (result != nullptr && !source.empty()) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(source.size()),
            reinterpret_cast<const jbyte*>(source.data())
        );
    }
    return result;
}

jintArray make_int_array(JNIEnv* env, std::span<const std::int32_t> source) {
    static_assert(sizeof(jint) == sizeof(std::int32_t));
    const auto result = env->NewIntArray(static_cast<jsize>(source.size()));
    if (result != nullptr && !source.empty()) {
        env->SetIntArrayRegion(
            result,
            0,
            static_cast<jsize>(source.size()),
            reinterpret_cast<const jint*>(source.data())
        );
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_create(
    JNIEnv* env,
    jclass,
    jint console_storage_id,
    jint forced_save_type
) {
    return guarded<jlong>(env, 0, [&] {
        std::unique_ptr<Core> core;
        if (console_storage_id == static_cast<jint>(ravenemu::Console::game_boy)) {
            core = ravenemu::make_game_boy_core();
        } else if (console_storage_id == static_cast<jint>(ravenemu::Console::game_boy_advance)) {
            core = ravenemu::make_gba_core(gba_save_type_from(forced_save_type));
        } else {
            throw std::invalid_argument("Console RavenEmu inconnue");
        }
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(core.release()));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Core*>(static_cast<std::uintptr_t>(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_loadRom(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray rom,
    jbyteArray battery
) {
    guarded_void(env, [&] {
        const auto rom_bytes = read_bytes(env, rom, false);
        const auto battery_bytes = read_bytes(env, battery, true);
        core_from(handle).load_rom(rom_bytes, battery_bytes);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_reset(JNIEnv* env, jclass, jlong handle) {
    guarded_void(env, [&] { core_from(handle).reset(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_runFrame(
    JNIEnv* env,
    jclass,
    jlong handle,
    jintArray framebuffer,
    jboolean render_video
) {
    guarded_void(env, [&] {
        if (framebuffer == nullptr) throw std::invalid_argument("Framebuffer absent");
        const auto length = env->GetArrayLength(framebuffer);
        auto* elements = env->GetIntArrayElements(framebuffer, nullptr);
        if (elements == nullptr) throw std::runtime_error("Framebuffer Java inaccessible");
        try {
            core_from(handle).run_frame(
                std::span<std::int32_t>{
                    reinterpret_cast<std::int32_t*>(elements),
                    static_cast<std::size_t>(length)
                },
                render_video != JNI_FALSE
            );
        } catch (...) {
            env->ReleaseIntArrayElements(framebuffer, elements, 0);
            throw;
        }
        env->ReleaseIntArrayElements(framebuffer, elements, 0);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_setButton(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint button,
    jboolean pressed
) {
    guarded_void(env, [&] {
        if (button < 0 || button > static_cast<jint>(ravenemu::Button::r)) {
            throw std::invalid_argument("Bouton RavenEmu inconnu");
        }
        core_from(handle).set_button(
            static_cast<ravenemu::Button>(button),
            pressed != JNI_FALSE
        );
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_readAudio(
    JNIEnv* env,
    jclass,
    jlong handle,
    jshortArray destination
) {
    return guarded<jint>(env, 0, [&] {
        if (destination == nullptr) throw std::invalid_argument("Tampon audio absent");
        const auto length = env->GetArrayLength(destination);
        auto* elements = env->GetShortArrayElements(destination, nullptr);
        if (elements == nullptr) throw std::runtime_error("Tampon audio Java inaccessible");
        std::size_t count{};
        try {
            count = core_from(handle).read_audio(
                std::span<std::int16_t>{
                    reinterpret_cast<std::int16_t*>(elements),
                    static_cast<std::size_t>(length)
                }
            );
        } catch (...) {
            env->ReleaseShortArrayElements(destination, elements, 0);
            throw;
        }
        env->ReleaseShortArrayElements(destination, elements, 0);
        return static_cast<jint>(count);
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_framebufferFormat(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jint>(env, 0, [&] {
        return static_cast<jint>(core_from(handle).framebuffer_format());
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_hasBatteryRam(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        return static_cast<jboolean>(
            core_from(handle).has_battery_ram() ? JNI_TRUE : JNI_FALSE
        );
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_batteryRamDirty(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        return static_cast<jboolean>(
            core_from(handle).battery_ram_dirty() ? JNI_TRUE : JNI_FALSE
        );
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_snapshotBatteryRam(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jobject>(env, nullptr, [&]() -> jobject {
        auto snapshot = core_from(handle).snapshot_battery_ram();
        if (!snapshot) return nullptr;
        const auto data = make_byte_array(env, snapshot->data);
        const auto klass = env->FindClass("com/ravenemu/nativebridge/NativeBatterySnapshot");
        const auto constructor = env->GetMethodID(klass, "<init>", "([BJ)V");
        const auto result = env->NewObject(
            klass,
            constructor,
            data,
            static_cast<jlong>(snapshot->generation)
        );
        env->DeleteLocalRef(data);
        env->DeleteLocalRef(klass);
        return result;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_acknowledgeBatteryRamSaved(
    JNIEnv* env,
    jclass,
    jlong handle,
    jlong generation
) {
    guarded_void(env, [&] {
        core_from(handle).acknowledge_battery_ram_saved(
            static_cast<std::uint64_t>(generation)
        );
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_saveState(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jbyteArray>(env, nullptr, [&] {
        const auto state = core_from(handle).save_state();
        return make_byte_array(env, state);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_loadState(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray state
) {
    guarded_void(env, [&] {
        const auto bytes = read_bytes(env, state, false);
        core_from(handle).load_state(bytes);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_setClockEpoch(
    JNIEnv* env,
    jclass,
    jlong handle,
    jboolean overridden,
    jlong epoch_seconds
) {
    guarded_void(env, [&] {
        core_from(handle).set_clock_epoch(
            overridden != JNI_FALSE
                ? std::optional<std::int64_t>{static_cast<std::int64_t>(epoch_seconds)}
                : std::nullopt
        );
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_gbaSaveType(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jint>(env, 0, [&] {
        return static_cast<jint>(core_from(handle).gba_save_type());
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_setGbaForcedSaveType(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint forced_save_type
) {
    guarded_void(env, [&] {
        core_from(handle).set_gba_forced_save_type(
            gba_save_type_from(forced_save_type)
        );
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_setMeasuringTime(
    JNIEnv* env,
    jclass,
    jlong handle,
    jboolean enabled
) {
    guarded_void(env, [&] {
        core_from(handle).set_measuring_time(enabled != JNI_FALSE);
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_measuringTime(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        return static_cast<jboolean>(
            core_from(handle).measuring_time() ? JNI_TRUE : JNI_FALSE
        );
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_debugSnapshot(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jobject>(env, nullptr, [&]() -> jobject {
        const auto snapshot = core_from(handle).debug_snapshot();
        if (!snapshot) return nullptr;
        const auto& value = *snapshot;
        const std::array<std::int32_t, 39> scalars{
            value.instructions_per_frame, value.program_counter,
            value.thumb ? 1 : 0, value.halted ? 1 : 0,
            value.last_swi, value.last_interrupt_mask, value.vcount,
            value.last_dma_channel, value.dma_active ? 1 : 0,
            value.fifo_a_size, value.fifo_b_size,
            value.fifo_a_empty_reads, value.fifo_b_empty_reads,
            value.audio_underruns, value.unsupported_swi_count,
            value.undefined_instruction_count, value.unsupported_access_count,
            value.missing_interrupt_count, value.decompression_error_count,
            value.first_unsupported_address, value.dispcnt,
            value.bg0_control, value.bg1_control, value.bg2_control,
            value.bg3_control, value.blend_control, value.blend_alpha,
            value.blend_brightness, value.window_inside, value.window_outside,
            value.luma_min, value.luma_max, value.luma_mean,
            value.bg2_reference_x, value.bg2_reference_y,
            value.bg2_scale_x, value.bg2_scale_y,
            value.bg2_matrix_writes, value.bg2_reference_writes,
        };
        const auto scalar_array = make_int_array(env, scalars);
        const auto layer_array = make_int_array(env, value.layer_pixels);
        const auto swi_array = make_int_array(env, value.swi_counts);
        const auto timings = env->NewDoubleArray(3);
        const std::array<jdouble, 3> timing_values{
            value.ppu_millis,
            value.dma_millis,
            value.apu_millis,
        };
        env->SetDoubleArrayRegion(timings, 0, 3, timing_values.data());
        const auto klass = env->FindClass("com/ravenemu/nativebridge/NativeGbaDebugSnapshot");
        const auto constructor = env->GetMethodID(klass, "<init>", "([I[I[I[D)V");
        const auto result = env->NewObject(
            klass,
            constructor,
            scalar_array,
            layer_array,
            swi_array,
            timings
        );
        env->DeleteLocalRef(scalar_array);
        env->DeleteLocalRef(layer_array);
        env->DeleteLocalRef(swi_array);
        env->DeleteLocalRef(timings);
        env->DeleteLocalRef(klass);
        return result;
    });
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_drainDiagnostics(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jobject>(env, nullptr, [&]() -> jobject {
        const auto messages = core_from(handle).drain_diagnostics();
        std::vector<std::int32_t> event_values;
        event_values.reserve(messages.size());
        for (const auto& message : messages) {
            event_values.push_back(static_cast<std::int32_t>(message.event));
        }
        const auto events = make_int_array(env, event_values);
        const auto string_class = env->FindClass("java/lang/String");
        const auto details = env->NewObjectArray(
            static_cast<jsize>(messages.size()),
            string_class,
            nullptr
        );
        for (std::size_t index = 0; index < messages.size(); ++index) {
            const auto text = env->NewStringUTF(messages[index].detail.c_str());
            env->SetObjectArrayElement(details, static_cast<jsize>(index), text);
            env->DeleteLocalRef(text);
        }
        const auto klass = env->FindClass("com/ravenemu/nativebridge/NativeDiagnosticBatch");
        const auto constructor = env->GetMethodID(
            klass,
            "<init>",
            "([I[Ljava/lang/String;)V"
        );
        const auto result = env->NewObject(klass, constructor, events, details);
        env->DeleteLocalRef(events);
        env->DeleteLocalRef(details);
        env->DeleteLocalRef(string_class);
        env->DeleteLocalRef(klass);
        return result;
    });
}
