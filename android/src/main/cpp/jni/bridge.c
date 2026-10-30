#include <jni.h>
#include <android/log.h>
#include <libretro.h>
#include <ravenemu/frontend.h>
#include <ravenemu/frame_pacer.h>
#include "audio_pipeline.h"
#include "gles2_renderer.h"
#include "input_driver.h"
#include "sensor_driver.h"
#include "state_thumbnail.h"
#include <time.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RavenNative", __VA_ARGS__)
#define MAX_ROM_BYTES (64u * 1024u * 1024u)
#define MAX_FRAME_SIZE (2048u * 2048u)
#define MAX_CORE_OPTIONS 64u

static raven_frontend *session;
static raven_input input = RAVEN_INPUT_INITIALIZER;
/* Owned by the GL/emulation thread; Java combines the reasons to pause. */
static int playback_paused = 1;
static uint8_t *rgba_pixels;
static size_t rgba_capacity;
static int image_width, image_height;
static int image_dirty, image_ready;
static int preview_valid;
/* Locked on the display when it divides the core rate, else the console clock. */
static raven_frame_pacer frame_pacer;
/* The display's refresh rate, as Android reports it; kept across games. */
static double display_rate;
/* The cartridge's motor, as the core last set it: strong and weak. */
static uint16_t rumble_strength[2];
static uint8_t stretch5(uint32_t value) {
    return (uint8_t)((value << 3u) | (value >> 2u));
}
static uint8_t stretch6(uint32_t value) {
    return (uint8_t)((value << 2u) | (value >> 4u));
}

static void on_video(const raven_video_frame *frame, void *userdata) {
    (void)userdata;
    if (!frame || !frame->pixels) return;  /* Libretro frame duplication. */
    unsigned w = frame->width, h = frame->height;
    if (!w || !h || (size_t)w * h > MAX_FRAME_SIZE) return;
    const size_t required = (size_t)w * h * 4;
    if (required > rgba_capacity) {
        uint8_t *expanded = (uint8_t *)realloc(rgba_pixels, required);
        if (!expanded) return;
        rgba_pixels = expanded;
        rgba_capacity = required;
    }
    const unsigned bytes_per_pixel = frame->pixel_format == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
    if (frame->pitch < (size_t)w * bytes_per_pixel) return;
    for (unsigned y = 0; y < h; ++y) {
        const uint8_t *line = (const uint8_t *)frame->pixels + y * frame->pitch;
        for (unsigned x = 0; x < w; ++x) {
            uint8_t r, g, b;
            if (frame->pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t px;
                memcpy(&px, line + x * 4u, 4);
                r = (uint8_t)(px >> 16u);
                g = (uint8_t)(px >> 8u);
                b = (uint8_t)px;
            } else {
                uint16_t px;
                memcpy(&px, line + x * 2u, 2);
                if (frame->pixel_format == RETRO_PIXEL_FORMAT_RGB565) {
                    r = stretch5((px >> 11u) & 31u);
                    g = stretch6((px >> 5u) & 63u);
                    b = stretch5(px & 31u);
                } else {
                    r = stretch5((px >> 10u) & 31u);
                    g = stretch5((px >> 5u) & 31u);
                    b = stretch5(px & 31u);
                }
            }
            uint8_t *dest = rgba_pixels + ((size_t)y * w + x) * 4;
            dest[0] = r; dest[1] = g; dest[2] = b; dest[3] = 255;
        }
    }
    image_width = (int)w;
    image_height = (int)h;
    image_ready = image_dirty = 1;
    preview_valid = 1;
}

static void on_audio(const int16_t *samples, size_t count, void *userdata) {
    (void)userdata;
    raven_audio_write(samples, count);
}

static int16_t on_input(unsigned port, unsigned device, unsigned index,
                        unsigned id, void *userdata) {
    (void)userdata;
    if (port || device != RETRO_DEVICE_JOYPAD || index) return 0;
    return raven_input_pressed(&input, id);
}

/* One phone, one motor and one set of sensors: the first player's. */
static int on_rumble(unsigned port, unsigned effect, uint16_t strength, void *userdata) {
    (void)userdata;
    if (port != 0 || effect > RETRO_RUMBLE_WEAK) return 0;
    rumble_strength[effect] = strength;
    return 1;
}

static int on_sensor_state(unsigned port, unsigned action, unsigned rate, void *userdata) {
    (void)userdata;
    return port == 0 && raven_sensors_set_state(action, rate);
}

static float on_sensor_input(unsigned port, unsigned id, void *userdata) {
    (void)userdata;
    return port == 0 ? raven_sensors_read(id) : 0.0f;
}

static void clear_frame(void) {
    image_ready = image_dirty = 0;
    preview_valid = 0;
    raven_video_reset();
    image_width = image_height = 0;
}

/* Core options from Java as alternating keys and values. A key the open core
 * does not declare, or a value it does not offer, is skipped: the core keeps
 * its own. Called on the emulation (GL) thread, like every core call. */
static void apply_core_options(JNIEnv *env, jobjectArray options) {
    if (!options || !session) return;
    jsize count = (*env)->GetArrayLength(env, options);
    if (count < 0 || (size_t)count > 2u * MAX_CORE_OPTIONS) {
        LOGE("Too many core options: %d", (int)count);
        return;
    }
    for (jsize i = 0; i + 1 < count; i += 2) {
        jstring key = (jstring)(*env)->GetObjectArrayElement(env, options, i);
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, options, i + 1);
        const char *k = key ? (*env)->GetStringUTFChars(env, key, NULL) : NULL;
        const char *v = value ? (*env)->GetStringUTFChars(env, value, NULL) : NULL;
        if (k && v && !raven_frontend_set_option(session, k, v))
            LOGE("Core option %s=%s not offered by this core", k, v);
        if (k) (*env)->ReleaseStringUTFChars(env, key, k);
        if (v) (*env)->ReleaseStringUTFChars(env, value, v);
        if (key) (*env)->DeleteLocalRef(env, key);
        if (value) (*env)->DeleteLocalRef(env, value);
    }
}

static jstring failure(JNIEnv *env, const char *error) {
    return (*env)->NewStringUTF(env, error ? error : "Unknown native error");
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_initGl(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    raven_video_init_gl();
    raven_frame_pacer_reset(&frame_pacer);
    image_dirty = image_ready;
}

JNIEXPORT jstring JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_openRom(
    JNIEnv *env, jclass clazz, jstring lib_dir, jstring files_dir,
    jstring core_name, jbyteArray rom, jstring filename, jobjectArray core_options) {
    (void)clazz;
    if (!lib_dir || !files_dir || !core_name || !rom || !filename)
        return failure(env, "Missing ROM or core details");
    jsize size = (*env)->GetArrayLength(env, rom);
    if (size <= 0 || (size_t)size > MAX_ROM_BYTES)
        return failure(env, "ROM empty or larger than 64 MiB");
    const char *lib = (*env)->GetStringUTFChars(env, lib_dir, NULL);
    const char *files = (*env)->GetStringUTFChars(env, files_dir, NULL);
    const char *core = (*env)->GetStringUTFChars(env, core_name, NULL);
    const char *name = (*env)->GetStringUTFChars(env, filename, NULL);
    if (!lib || !files || !core || !name) {
        if (lib) (*env)->ReleaseStringUTFChars(env, lib_dir, lib);
        if (files) (*env)->ReleaseStringUTFChars(env, files_dir, files);
        if (core) (*env)->ReleaseStringUTFChars(env, core_name, core);
        if (name) (*env)->ReleaseStringUTFChars(env, filename, name);
        return failure(env, "Failed to read ROM metadata");
    }
    char library_path[2048];
    int length = -1;
    if (!strcmp(core, "gb") || !strcmp(core, "gbc") || !strcmp(core, "gba"))
        length = snprintf(library_path, sizeof(library_path),
                          "%s/ravenemu_%s_libretro.so", lib, core);
    jstring result = NULL;
    if (length <= 0 || (size_t)length >= sizeof(library_path)) {
        result = failure(env, "Invalid Libretro core path");
        goto done;
    }
    if (!session) session = raven_frontend_create();
    if (!session) {
        result = failure(env, "Out of memory");
        goto done;
    }
    raven_audio_close();
    frame_pacer = (raven_frame_pacer){0};
    raven_frame_pacer_set_display(&frame_pacer, display_rate);
    raven_input_clear_buttons(&input);
    clear_frame();
    /* The next game asks for its own sensors, as it loads. */
    raven_sensors_close();
    raven_sensors_set_active(!playback_paused);
    rumble_strength[0] = rumble_strength[1] = 0;
    if (!raven_frontend_open_core(session, library_path, files, files)) {
        result = failure(env, raven_frontend_last_error(session));
        goto done;
    }
    raven_frontend_callbacks callbacks = {
        .video = on_video, .audio = on_audio, .input = on_input,
        .rumble = on_rumble, .sensor_state = on_sensor_state, .sensor_input = on_sensor_input};
    raven_frontend_set_callbacks(session, &callbacks);
    /* Before the game loads: the options read only then apply to it. */
    apply_core_options(env, core_options);
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    if (!bytes) {
        result = failure(env, "Unable to read ROM contents");
        goto done;
    }
    int loaded = raven_frontend_load_rom(session, bytes, (size_t)size, name);
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    if (!loaded) {
        result = failure(env, raven_frontend_last_error(session));
    } else {
        raven_core_info info = {0};
        if (!raven_frontend_get_info(session, &info) ||
                !raven_frame_pacer_configure(&frame_pacer, info.fps)) {
            result = failure(env, "Unsupported Libretro core frame rate");
            raven_frontend_unload(session);
            goto done;
        }
        if (!raven_audio_open(info.sample_rate)) {
            LOGE("Audio output unavailable; running game without sound");
        }
        raven_audio_set_running(!playback_paused);
    }

done:
    (*env)->ReleaseStringUTFChars(env, lib_dir, lib);
    (*env)->ReleaseStringUTFChars(env, files_dir, files);
    (*env)->ReleaseStringUTFChars(env, core_name, core);
    (*env)->ReleaseStringUTFChars(env, filename, name);
    return result;
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_setButtons(
    JNIEnv *env, jclass clazz, jint mask) {
    (void)env;
    (void)clazz;
    raven_input_set_buttons(&input, (unsigned)mask);
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_clearButtons(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    raven_input_clear_buttons(&input);
}

JNIEXPORT jint JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_drawFrame(
    JNIEnv *env, jclass clazz, jint surface_width, jint surface_height,
    jboolean advance) {
    (void)env;
    (void)clazz;
    unsigned advanced = 0;
    /* Headphones or a Bluetooth device gone: AAudio only says so. */
    raven_audio_recover();
    if (playback_paused || !advance) {
        raven_input_clear_buttons(&input);
        raven_frame_pacer_reset(&frame_pacer);
    } else if (session) {
        struct timespec now;
        if (clock_gettime(CLOCK_MONOTONIC, &now) == 0) {
            const uint64_t now_ns = (uint64_t)now.tv_sec * UINT64_C(1000000000) +
                                    (uint64_t)now.tv_nsec;
            const unsigned due = raven_frame_pacer_tick(&frame_pacer, now_ns);
            /* Locked on the display, the game runs at its pace: so does the sound. */
            raven_audio_set_speed(raven_frame_pacer_speed(&frame_pacer));
            if (due) raven_sensors_poll();
            for (unsigned i = 0; i < due; ++i) {
                raven_input_begin_frame(&input);
                if (!raven_frontend_run_frame(session)) {
                    raven_frame_pacer_reset(&frame_pacer);
                    break;
                }
                ++advanced;
            }
        }
    }
    raven_video_draw(image_ready ? rgba_pixels : NULL,
        (unsigned)image_width, (unsigned)image_height, image_dirty,
        surface_width, surface_height);
    image_dirty = 0;
    return (jint)advanced;
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_setPaused(
    JNIEnv *env, jclass clazz, jboolean paused) {
    (void)env; (void)clazz;
    const int next_paused = paused == JNI_TRUE;
    if (playback_paused != next_paused) raven_frame_pacer_reset(&frame_pacer);
    playback_paused = next_paused;
    if (playback_paused) raven_input_clear_buttons(&input);
    raven_audio_set_running(!playback_paused);
    /* The menu open, the sensors rest: the battery lasts. */
    raven_sensors_set_active(!playback_paused);
}

/* GL thread: Display.getRotation(), for the tilt along the screen. */
JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_setDisplayRotation(
    JNIEnv *env, jclass clazz, jint rotation) {
    (void)env; (void)clazz;
    raven_sensors_set_rotation(rotation);
}

/* GL thread, after each drawn frame: whether the cartridge's motor runs. A
 * paused game never vibrates. */
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_rumbling(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    return session && !playback_paused && (rumble_strength[0] || rumble_strength[1]) ? JNI_TRUE : JNI_FALSE;
}

/* GL thread: the display's refresh rate, on start and whenever it changes. */
JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_setDisplayRate(
    JNIEnv *env, jclass clazz, jfloat hertz) {
    (void)env; (void)clazz;
    display_rate = hertz;
    raven_frame_pacer_set_display(&frame_pacer, display_rate);
}

/* GL thread: the core's latest on-screen message, once; NULL without one.
 * The frontend keeps it to modified UTF-8, which NewStringUTF requires. */
JNIEXPORT jstring JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_takeMessage(JNIEnv *env, jclass clazz) {
    (void)clazz;
    const char *message = session ? raven_frontend_take_message(session) : NULL;
    return message ? (*env)->NewStringUTF(env, message) : NULL;
}

/* GL thread: the open game's frame rate, 0 without one. */
JNIEXPORT jdouble JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_frameRate(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    raven_core_info info = {0};
    return session && raven_frontend_get_info(session, &info) ? info.fps : 0.0;
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_setPlaybackOptions(
    JNIEnv *env, jclass clazz, jboolean audio_enabled, jint audio_volume,
    jboolean integer_scale, jint brightness, jint contrast, jint screen_effect) {
    (void)env; (void)clazz;
    raven_audio_set_output(audio_enabled == JNI_TRUE, audio_volume);
    raven_video_set_options(integer_scale == JNI_TRUE, brightness, contrast);
    raven_video_set_effect(screen_effect);
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_setCoreOptions(
    JNIEnv *env, jclass clazz, jobjectArray core_options) {
    (void)clazz;
    apply_core_options(env, core_options);
}

JNIEXPORT jstring JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_stateCompatibility(JNIEnv *env, jclass clazz) {
    (void)clazz;
    raven_core_info info = {0};
    if (!raven_frontend_get_info(session, &info) || !info.name || !info.version) return NULL;
    char identity[512];
    int count = snprintf(identity, sizeof(identity), "libretro-1|%s|%s|%s|state-v1",
                         RAVEN_CORE_REVISION, info.name, info.version);
    if (count <= 0 || (size_t)count >= sizeof(identity)) return NULL;
    return (*env)->NewStringUTF(env, identity);
}

/* A limit on JNI allocations; Libretro save RAM/state sizes are untrusted. */
#define MAX_BATTERY_BYTES (1024u * 1024u)
#define MAX_STATE_BYTES (16u * 1024u * 1024u)

JNIEXPORT jbyteArray JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_saveBattery(JNIEnv *env, jclass clazz) {
    (void)clazz;
    size_t size = raven_frontend_battery_size(session);
    if (!size || size > MAX_BATTERY_BYTES) return NULL;
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)size);
    if (!bytes) return NULL;
    void *temp = malloc(size);
    if (!temp) return NULL;
    if (!raven_frontend_read_battery(session, temp, size)) {
        free(temp);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)size, (const jbyte *)temp);
    free(temp);
    return bytes;
}

JNIEXPORT jboolean JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_restoreBattery(
    JNIEnv *env, jclass clazz, jbyteArray saved) {
    (void)clazz;
    if (!saved) return JNI_FALSE;
    jsize size = (*env)->GetArrayLength(env, saved);
    if (size <= 0 || (size_t)size > MAX_BATTERY_BYTES ||
        (size_t)size != raven_frontend_battery_size(session)) return JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, saved, NULL);
    if (!bytes) return JNI_FALSE;
    int restored = raven_frontend_restore_battery(session, bytes, (size_t)size);
    (*env)->ReleaseByteArrayElements(env, saved, bytes, JNI_ABORT);
    return restored ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_saveState(JNIEnv *env, jclass clazz) {
    (void)clazz;
    size_t size = raven_frontend_state_size(session);
    if (!size || size > MAX_STATE_BYTES) return NULL;
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)size);
    if (!bytes) return NULL;
    void *temp = malloc(size);
    if (!temp) return NULL;
    int saved = raven_frontend_save_state(session, temp, size);
    if (saved) (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)size, (const jbyte *)temp);
    free(temp);
    return saved ? bytes : NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_restoreState(
    JNIEnv *env, jclass clazz, jbyteArray saved) {
    (void)clazz;
    if (!saved) return JNI_FALSE;
    jsize size = (*env)->GetArrayLength(env, saved);
    if (size <= 0 || (size_t)size > MAX_STATE_BYTES) return JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, saved, NULL);
    if (!bytes) return JNI_FALSE;
    int result = raven_frontend_load_state(session, bytes, (size_t)size);
    raven_frame_pacer_reset(&frame_pacer);
    // Keep displaying the previous frame, but never attach its pixels to a
    // restored state until the core has supplied a fresh video callback.
    preview_valid = 0;
    (*env)->ReleaseByteArrayElements(env, saved, bytes, JNI_ABORT);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jintArray JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_capturePreview(JNIEnv *env, jclass clazz) {
    (void)clazz;
    if (!preview_valid || !image_ready || image_width <= 0 || image_height <= 0) return NULL;
    uint32_t *pixels = malloc(RAVEN_PREVIEW_CAPACITY * sizeof(*pixels));
    if (!pixels) return NULL;
    size_t count = raven_preview_argb(rgba_pixels, rgba_capacity,
        (unsigned)image_width, (unsigned)image_height, pixels, RAVEN_PREVIEW_CAPACITY);
    jintArray result = count ? (*env)->NewIntArray(env, (jsize)count) : NULL;
    if (result) (*env)->SetIntArrayRegion(env, result, 0, (jsize)count, (const jint *)pixels);
    free(pixels);
    return result;
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_setAudioEnabled(
    JNIEnv *env, jclass clazz, jboolean enabled) {
    (void)env; (void)clazz;
    raven_audio_set_running(enabled == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeBridge_closeRom(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    raven_audio_close();
    frame_pacer = (raven_frame_pacer){0};
    raven_frame_pacer_set_display(&frame_pacer, display_rate);
    if (session) {
        raven_frontend_destroy(session);
        session = NULL;
    }
    raven_sensors_close();
    rumble_strength[0] = rumble_strength[1] = 0;
    raven_input_clear_buttons(&input);
    clear_frame();
    free(rgba_pixels);
    rgba_pixels = NULL;
    rgba_capacity = 0;
}
