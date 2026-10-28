#include <jni.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <libretro.h>
#include <ravenemu/frontend.h>

#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "RavenNative", __VA_ARGS__)
#define MAX_ROM_BYTES (64u * 1024u * 1024u)
#define MAX_FRAME_SIZE (2048u * 2048u)

static raven_frontend *session;
static _Atomic unsigned buttons;
static uint8_t *rgba_pixels;
static size_t rgba_capacity;
static int image_width, image_height;
static int image_dirty, image_ready;
static GLuint program, texture, vertex_shader, fragment_shader;
static GLint attribute_position, attribute_uv, uniform_texture;

static const char *vertex_source =
    "attribute vec2 aPosition;\n"
    "attribute vec2 aUv;\n"
    "varying vec2 vUv;\n"
    "void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vUv = aUv; }\n";
static const char *fragment_source =
    "precision mediump float;\n"
    "varying vec2 vUv;\n"
    "uniform sampler2D uTexture;\n"
    "void main() { gl_FragColor = texture2D(uTexture, vUv); }\n";

static GLuint compile_shader(GLenum type, const char *source) {
    GLuint shader = glCreateShader(type);
    if (!shader) return 0;
    glShaderSource(shader, 1, &source, NULL);
    glCompileShader(shader);
    GLint success = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &success);
    if (!success) {
        char message[512] = {0};
        glGetShaderInfoLog(shader, sizeof(message), NULL, message);
        LOGE("GLSL compilation failed: %s", message);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

static void init_gl(void) {
    /* onSurfaceCreated can run again after an EGL context loss.
     * The previous GL handles no longer refer to valid objects. */
    program = texture = vertex_shader = fragment_shader = 0;
    vertex_shader = compile_shader(GL_VERTEX_SHADER, vertex_source);
    fragment_shader = compile_shader(GL_FRAGMENT_SHADER, fragment_source);
    if (!vertex_shader || !fragment_shader) return;
    program = glCreateProgram();
    glAttachShader(program, vertex_shader);
    glAttachShader(program, fragment_shader);
    glLinkProgram(program);
    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("OpenGL ES 2 shader program linking failed");
        glDeleteProgram(program);
        program = 0;
        return;
    }
    attribute_position = glGetAttribLocation(program, "aPosition");
    attribute_uv = glGetAttribLocation(program, "aUv");
    uniform_texture = glGetUniformLocation(program, "uTexture");
    glGenTextures(1, &texture);
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    image_dirty = image_ready;
    /* The texture dimensions need reallocation with every EGL context. */
    image_width = image_ready ? image_width : 0;
    image_height = image_ready ? image_height : 0;
}

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
}

static void on_audio(const int16_t *samples, size_t count, void *userdata) {
    /* Native audio driver is deliberately a separate upcoming stage. */
    (void)samples;
    (void)count;
    (void)userdata;
}

static int16_t on_input(unsigned port, unsigned device, unsigned index,
                        unsigned id, void *userdata) {
    (void)index;
    (void)userdata;
    if (port || device != RETRO_DEVICE_JOYPAD || id >= 16) return 0;
    return (atomic_load(&buttons) & (1u << id)) ? 1 : 0;
}

static void clear_frame(void) {
    image_ready = image_dirty = 0;
    image_width = image_height = 0;
}

static jstring failure(JNIEnv *env, const char *error) {
    return (*env)->NewStringUTF(env, error ? error : "Unknown native error");
}

JNIEXPORT void JNICALL
Java_com_ravenemu_nativepreview_NativeBridge_initGl(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    init_gl();
}

JNIEXPORT jstring JNICALL
Java_com_ravenemu_nativepreview_NativeBridge_openRom(
    JNIEnv *env, jclass clazz, jstring lib_dir, jstring files_dir,
    jstring core_name, jbyteArray rom, jstring filename) {
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
    clear_frame();
    if (!raven_frontend_open_core(session, library_path, files, files)) {
        result = failure(env, raven_frontend_last_error(session));
        goto done;
    }
    raven_frontend_callbacks callbacks = {on_video, on_audio, on_input, NULL};
    raven_frontend_set_callbacks(session, &callbacks);
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    if (!bytes) {
        result = failure(env, "Unable to read ROM contents");
        goto done;
    }
    int loaded = raven_frontend_load_rom(session, bytes, (size_t)size, name);
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    if (!loaded) result = failure(env, raven_frontend_last_error(session));

done:
    (*env)->ReleaseStringUTFChars(env, lib_dir, lib);
    (*env)->ReleaseStringUTFChars(env, files_dir, files);
    (*env)->ReleaseStringUTFChars(env, core_name, core);
    (*env)->ReleaseStringUTFChars(env, filename, name);
    return result;
}

JNIEXPORT void JNICALL
Java_com_ravenemu_nativepreview_NativeBridge_setButtons(
    JNIEnv *env, jclass clazz, jint mask) {
    (void)env;
    (void)clazz;
    atomic_store(&buttons, (unsigned)mask);
}

JNIEXPORT void JNICALL
Java_com_ravenemu_nativepreview_NativeBridge_drawFrame(
    JNIEnv *env, jclass clazz, jint surface_width, jint surface_height,
    jboolean advance) {
    (void)env;
    (void)clazz;
    if (advance && session) raven_frontend_run_frame(session);
    glClearColor(0.05f, 0.06f, 0.08f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!program || !texture || !image_ready || !rgba_pixels ||
        surface_width <= 0 || surface_height <= 0) return;

    glBindTexture(GL_TEXTURE_2D, texture);
    if (image_dirty) {
        // Allocate each time dimensions change; first draft keeps this simple.
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, image_width, image_height,
                     0, GL_RGBA, GL_UNSIGNED_BYTE, rgba_pixels);
        image_dirty = 0;
    }
    float scale = (float)image_width / (float)image_height;
    float viewport_scale = (float)surface_width / (float)surface_height;
    int w = surface_width, h = surface_height;
    if (viewport_scale > scale) w = (int)((float)h * scale);
    else h = (int)((float)w / scale);
    glViewport((surface_width - w) / 2, (surface_height - h) / 2, w, h);
    const GLfloat vertices[] = {
        -1.f, -1.f, 0.f, 1.f,
         1.f, -1.f, 1.f, 1.f,
        -1.f,  1.f, 0.f, 0.f,
         1.f,  1.f, 1.f, 0.f
    };
    glUseProgram(program);
    glActiveTexture(GL_TEXTURE0);
    glUniform1i(uniform_texture, 0);
    glEnableVertexAttribArray((GLuint)attribute_position);
    glEnableVertexAttribArray((GLuint)attribute_uv);
    glVertexAttribPointer((GLuint)attribute_position, 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(GLfloat), vertices);
    glVertexAttribPointer((GLuint)attribute_uv, 2, GL_FLOAT, GL_FALSE,
                          4 * sizeof(GLfloat), vertices + 2);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray((GLuint)attribute_position);
    glDisableVertexAttribArray((GLuint)attribute_uv);
}

JNIEXPORT void JNICALL
Java_com_ravenemu_nativepreview_NativeBridge_closeRom(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (session) {
        raven_frontend_destroy(session);
        session = NULL;
    }
    atomic_store(&buttons, 0u);
    clear_frame();
    free(rgba_pixels);
    rgba_pixels = NULL;
    rgba_capacity = 0;
}
