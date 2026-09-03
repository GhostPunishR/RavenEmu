#include "ravenemu/native/core_api.hpp"

#include <ravenemu/read_exactly.hpp>

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

/**
 * Lève une exception Java sans argument de cause.
 *
 * `ThrowNew` déréférence la classe qu'on lui passe. `FindClass` retourne
 * `nullptr` quand elle échoue, et lui transmettre ce `nullptr` ferait tomber le
 * processus au lieu de remonter l'erreur qu'on essayait précisément de
 * signaler : le contrôle n'est pas défensif, il est ce qui distingue une
 * exception Kotlin d'un arrêt brutal.
 */
void throw_simple_exception(JNIEnv* env, const char* class_name, const char* message) noexcept {
    const auto klass = env->FindClass(class_name);
    if (klass == nullptr) return;
    env->ThrowNew(klass, message);
    env->DeleteLocalRef(klass);
}

void translate_exception(JNIEnv* env) noexcept {
    // Une exception Java déjà en attente est la cause première de l'exception
    // C++ qu'on s'apprête à traduire : c'est elle qui porte l'information, et
    // la remplacer par un message de repli ferait perdre le diagnostic.
    //
    // Elle doit surtout être laissée en place plutôt que contournée : appeler
    // JNI alors qu'une exception est en attente est un comportement indéfini,
    // et `FindClass` est en droit d'y retourner `nullptr`. Traduire malgré tout
    // revenait donc à transformer une erreur signalable en plantage natif.
    if (env->ExceptionCheck()) return;

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
        throw_simple_exception(env, "java/lang/IllegalArgumentException", error.what());
    } catch (const std::logic_error& error) {
        throw_simple_exception(env, "java/lang/IllegalStateException", error.what());
    } catch (const std::exception& error) {
        throw_simple_exception(env, "java/lang/RuntimeException", error.what());
    } catch (...) {
        throw_simple_exception(env, "java/lang/RuntimeException", "Erreur native RavenEmu inconnue");
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

/**
 * Réglage tri-état de l'horloge de cartouche, transporté en entier faute
 * d'`Optional` à la frontière JNI : négatif rend la main à la détection, zéro
 * impose l'absence, positif impose la présence.
 */
std::optional<bool> forced_rtc_from(jint value) {
    if (value < 0) return std::nullopt;
    return value != 0;
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
        // L'exception Java levée par la copie est laissée en attente : c'est
        // elle qui remontera à Kotlin. L'exception C++ ne sert qu'à interrompre
        // le travail natif en cours, `translate_exception` la voit et s'efface.
        if (env->ExceptionCheck()) throw std::runtime_error("Lecture d'un tableau Java impossible");
    }
    return result;
}

/**
 * Convertit une taille native en longueur de tableau Java.
 *
 * `jsize` est un entier **signé** 32 bits. Une conversion sans contrôle
 * enroulerait une taille trop grande vers une longueur négative ou tronquée, et
 * le tableau rendu à Kotlin ne contiendrait alors pas ce que le natif croit y
 * avoir écrit. Le contrôle est ici plutôt que chez chaque appelant : dupliqué,
 * il finissait par manquer à l'un d'eux.
 */
jsize checked_length(std::size_t size) {
    if (size > static_cast<std::size_t>(std::numeric_limits<jsize>::max())) {
        throw std::length_error("Tableau natif trop volumineux");
    }
    return static_cast<jsize>(size);
}

/**
 * Signale l'échec d'une allocation ou d'une recherche côté JVM.
 *
 * Ces appels rendent `nullptr` après avoir armé une exception Java. Poursuivre
 * les appels JNI dans cet état est indéfini, et rendre le `nullptr` au chaînage
 * qui suit revient exactement à cela : il finirait déréférencé par le prochain
 * appel. On interrompt donc le travail natif, et `translate_exception` laisse
 * remonter l'exception Java déjà en attente.
 */
template <typename Handle>
Handle require_jvm(Handle handle, const char* what) {
    if (handle == nullptr) throw std::runtime_error(what);
    return handle;
}

jbyteArray make_byte_array(JNIEnv* env, std::span<const std::uint8_t> source) {
    const auto length = checked_length(source.size());
    const auto result = require_jvm(
        env->NewByteArray(length),
        "Allocation d'un tableau d'octets Java impossible"
    );
    if (length > 0) {
        env->SetByteArrayRegion(
            result,
            0,
            length,
            reinterpret_cast<const jbyte*>(source.data())
        );
    }
    return result;
}

jintArray make_int_array(JNIEnv* env, std::span<const std::int32_t> source) {
    static_assert(sizeof(jint) == sizeof(std::int32_t));
    const auto length = checked_length(source.size());
    const auto result = require_jvm(
        env->NewIntArray(length),
        "Allocation d'un tableau d'entiers Java impossible"
    );
    if (length > 0) {
        env->SetIntArrayRegion(
            result,
            0,
            length,
            reinterpret_cast<const jint*>(source.data())
        );
    }
    return result;
}

/** Classe Java attendue par le pont : son absence est une erreur de build. */
jclass require_class(JNIEnv* env, const char* name) {
    return require_jvm(env->FindClass(name), name);
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
        } else if (console_storage_id == static_cast<jint>(ravenemu::Console::nintendo_ds)) {
            // Le type de sauvegarde imposé ne concerne que le Game Boy Advance :
            // la Nintendo DS n'a pas encore de mémoire de cartouche, et le
            // paramètre est ignoré plutôt que refusé, l'appelant le passant
            // uniformément pour toutes les consoles.
            core = ravenemu::make_nds_core();
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

/**
 * Charge une image depuis un descripteur de fichier ouvert.
 *
 * ### Pourquoi ce chemin existe
 *
 * Le chemin ordinaire fait traverser l'image par un tableau Java : pour une
 * cartouche Nintendo DS de deux cent cinquante-six mégaoctets, cela demande au
 * tas Java de la tenir entière, en plus de l'exemplaire que le cœur garde. Le
 * tas Java d'une application Android est plafonné bien en dessous de ce que la
 * mémoire de l'appareil permettrait, et c'est ce plafond, non le matériel, qui
 * refusait la cartouche.
 *
 * Lue ici, l'image ne touche jamais le tas Java, et le cœur la **prend** au
 * lieu de la recopier : un seul exemplaire existe, en mémoire native.
 *
 * ### Pourquoi un numéro et non un `FileDescriptor`
 *
 * L'objet `java.io.FileDescriptor` ne publie pas son numéro, et le lire par
 * JNI revient à toucher un champ interne : Android en restreint l'accès, et un
 * champ refusé lève une `NoSuchFieldError`. Une erreur n'est pas une exception,
 * et elle traverse les rattrapages ordinaires jusqu'à tuer l'application. Le
 * numéro arrive donc tel quel, par l'interface publique qui le donne.
 *
 * ### Ce que cette fonction ne fait pas
 *
 * Elle ne ferme pas le descripteur : il appartient à l'appelant, qui l'a
 * ouvert et qui sait quand le rendre. Le fermer ici doublerait une fermeture
 * que la couche Android fait déjà, ce qui, sur un descripteur recyclé entre
 * temps, coupe un fichier sans rapport.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_loadRomFromDescriptor(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint descriptor,
    jlong size_bytes,
    jbyteArray battery
) {
    guarded_void(env, [&] {
        if (descriptor < 0) throw std::invalid_argument("Descripteur de fichier invalide");
        if (size_bytes < 0) throw std::invalid_argument("Taille de ROM négative");
        if (static_cast<std::uint64_t>(size_bytes) >
            static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max())) {
            throw std::invalid_argument("ROM trop volumineuse pour cette machine");
        }

        auto rom = ravenemu::read_exactly(descriptor, static_cast<std::size_t>(size_bytes));
        const auto battery_bytes = read_bytes(env, battery, true);
        core_from(handle).load_rom_owned(std::move(rom), battery_bytes);
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
        if (button < 0 || button > static_cast<jint>(ravenemu::Button::y)) {
            throw std::invalid_argument("Bouton RavenEmu inconnu");
        }
        core_from(handle).set_button(
            static_cast<ravenemu::Button>(button),
            pressed != JNI_FALSE
        );
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_setTouch(
    JNIEnv* env,
    jclass,
    jlong handle,
    jboolean down,
    jint x,
    jint y
) {
    guarded_void(env, [&] {
        // Les coordonnées ne sont pas contrôlées ici : le cœur ramène lui-même
        // un contact hors de l'écran sur son bord, et refuser ce que le cœur
        // accepte ferait disparaître un doigt qui glisse au-delà d'une lisière.
        core_from(handle).set_touch(down != JNI_FALSE, x, y);
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

extern "C" JNIEXPORT jboolean JNICALL
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
        const auto klass = require_class(env, "com/ravenemu/nativebridge/NativeBatterySnapshot");
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_gbaRtcActive(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jboolean>(env, JNI_FALSE, [&] {
        return static_cast<jboolean>(core_from(handle).gba_rtc_active() ? JNI_TRUE : JNI_FALSE);
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_setGbaForcedRtc(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint forced_rtc
) {
    guarded_void(env, [&] {
        core_from(handle).set_gba_forced_rtc(forced_rtc_from(forced_rtc));
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
        const auto klass = require_class(env, "com/ravenemu/nativebridge/NativeGbaDebugSnapshot");
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

/**
 * Relevé de la Nintendo DS, rendu comme une suite de nombres.
 *
 * L'ordre est **le contrat** avec la couche Java, qui les renomme. Il est figé :
 * une valeur s'ajoute à la fin, jamais au milieu, sinon le relevé se lirait de
 * travers sans que rien ne s'en plaigne. Une vérification tient les deux
 * longueurs ensemble.
 */
extern "C" JNIEXPORT jintArray JNICALL
Java_com_ravenemu_nativebridge_NativeCoreBridge_ndsDebugSnapshot(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    return guarded<jintArray>(env, nullptr, [&]() -> jintArray {
        const auto snapshot = core_from(handle).nds_debug_snapshot();
        if (!snapshot) return nullptr;
        const auto& value = *snapshot;
        const std::array<std::int32_t, 24> scalars{
            value.main_instructions, value.secondary_instructions,
            value.main_program_counter, value.secondary_program_counter,
            value.main_halted ? 1 : 0, value.secondary_halted ? 1 : 0,
            value.main_undefined_count, value.main_first_undefined,
            value.secondary_undefined_count, value.secondary_first_undefined,
            value.main_unimplemented_io, value.main_first_unimplemented_io,
            value.secondary_unimplemented_io, value.secondary_first_unimplemented_io,
            value.main_unsupported_swi, value.secondary_unsupported_swi,
            value.main_display_control, value.secondary_display_control,
            value.unimplemented_layers, value.unimplemented_display,
            value.unimplemented_objects, value.non_black_pixels,
            value.screens_swapped ? 1 : 0, value.cartridge_unsupported,
        };
        return make_int_array(env, scalars);
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
        const auto string_class = require_class(env, "java/lang/String");
        const auto details = require_jvm(
            env->NewObjectArray(checked_length(messages.size()), string_class, nullptr),
            "Allocation du tableau de diagnostics Java impossible"
        );
        for (std::size_t index = 0; index < messages.size(); ++index) {
            const auto text = env->NewStringUTF(messages[index].detail.c_str());
            env->SetObjectArrayElement(details, static_cast<jsize>(index), text);
            env->DeleteLocalRef(text);
        }
        const auto klass = require_class(env, "com/ravenemu/nativebridge/NativeDiagnosticBatch");
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
