/* The imported shader presets for the Java side (RavenEmu code, AGPLv3):
 * hardware/video/shaders/shader_state.h behind NativeShaders. Texts cross
 * as UTF-8 bytes, never as JNI's modified UTF-8: a file name may hold any
 * character. prepare, lut and describe run on a worker thread; apply and
 * clear on the GL thread. */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "shader_state.h"

#define TEXT_LIMIT 4096

static char *text_of(JNIEnv *env, jbyteArray bytes) {
    if (!bytes) return NULL;
    jsize length = (*env)->GetArrayLength(env, bytes);
    if (length < 0 || length > TEXT_LIMIT) return NULL;
    char *text = malloc((size_t)length + 1);
    if (!text) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)text);
    text[length] = '\0';
    if (memchr(text, '\0', (size_t)length)) {
        free(text);
        return NULL;
    }
    return text;
}

static jbyteArray bytes_of(JNIEnv *env, const char *text) {
    if (!text) return NULL;
    jsize length = (jsize)strlen(text);
    jbyteArray bytes = (*env)->NewByteArray(env, length);
    if (bytes) (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *)text);
    return bytes;
}

JNIEXPORT jbyteArray JNICALL
Java_com_ravenemu_app_bridge_NativeShaders_nativePrepare(JNIEnv *env, jclass type, jbyteArray folder,
                                                         jbyteArray preset) {
    (void)type;
    char *dir = text_of(env, folder), *path = text_of(env, preset);
    char error[512] = "Chemin de preset invalide";
    int result = dir && path ? raven_shaders_prepare(dir, path, error, sizeof(error)) : -1;
    free(dir);
    free(path);
    return result == 0 ? NULL : bytes_of(env, error);
}

/* The prepared preset's textures, one relative path per line. */
JNIEXPORT jbyteArray JNICALL
Java_com_ravenemu_app_bridge_NativeShaders_nativeLutPaths(JNIEnv *env, jclass type) {
    (void)type;
    char list[RAVEN_SHADER_MAX_LUTS * (RAVEN_SHADER_PATH + 1) + 1] = "";
    size_t used = 0;
    unsigned count = raven_shaders_lut_count();
    for (unsigned k = 0; k < count; ++k) {
        char path[RAVEN_SHADER_PATH];
        if (!raven_shaders_lut_path(k, path, sizeof(path))) break;
        used += (size_t)snprintf(list + used, sizeof(list) - used, "%s%s", k ? "\n" : "", path);
        if (used >= sizeof(list)) break;
    }
    return bytes_of(env, list);
}

/* Android's ARGB pixels (Bitmap.getPixels) as the GPU's RGBA bytes. */
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_app_bridge_NativeShaders_setLut(JNIEnv *env, jclass type, jint index, jint width, jint height,
                                                  jintArray argb) {
    (void)type;
    if (!argb || index < 0 || width <= 0 || height <= 0 || width > 2048 || height > 2048) return JNI_FALSE;
    jsize count = (*env)->GetArrayLength(env, argb);
    if ((long long)count != (long long)width * height) return JNI_FALSE;
    jint *pixels = (*env)->GetIntArrayElements(env, argb, NULL);
    uint8_t *rgba = pixels ? malloc((size_t)count * 4) : NULL;
    if (rgba) {
        for (jsize i = 0; i < count; ++i) {
            uint32_t p = (uint32_t)pixels[i];
            rgba[i * 4 + 0] = (uint8_t)(p >> 16);
            rgba[i * 4 + 1] = (uint8_t)(p >> 8);
            rgba[i * 4 + 2] = (uint8_t)p;
            rgba[i * 4 + 3] = (uint8_t)(p >> 24);
        }
    }
    if (pixels) (*env)->ReleaseIntArrayElements(env, argb, pixels, JNI_ABORT);
    int stored = rgba && raven_shaders_set_lut((unsigned)index, (unsigned)width, (unsigned)height, rgba);
    free(rgba);
    return stored ? JNI_TRUE : JNI_FALSE;
}

/* "" then one parameter per line: name, description, initial, minimum,
 * maximum and step, separated by tabs; or "!" and the error. */
JNIEXPORT jbyteArray JNICALL
Java_com_ravenemu_app_bridge_NativeShaders_nativeDescribe(JNIEnv *env, jclass type, jbyteArray folder,
                                                          jbyteArray preset) {
    (void)type;
    char *dir = text_of(env, folder), *path = text_of(env, preset);
    struct raven_shader_parameter *found = calloc(RAVEN_SHADER_MAX_PARAMETERS, sizeof(*found));
    char error[512] = "Chemin de preset invalide";
    int count = dir && path && found ? raven_shaders_describe(dir, path, found, RAVEN_SHADER_MAX_PARAMETERS,
                                                              error, sizeof(error)) : -1;
    free(dir);
    free(path);
    if (count < 0) {
        free(found);
        char failed[520];
        snprintf(failed, sizeof(failed), "!%s", error);
        return bytes_of(env, failed);
    }
    size_t size = (size_t)count * (RAVEN_SHADER_NAME + RAVEN_SHADER_DESCRIPTION + 64) + 2;
    char *list = malloc(size);
    if (!list) {
        free(found);
        return NULL;
    }
    size_t used = 0;
    list[0] = '\0';
    for (int k = 0; k < count; ++k) {
        for (char *c = found[k].description; *c; ++c) if (*c == '\t' || *c == '\n') *c = ' ';
        used += (size_t)snprintf(list + used, size - used, "\n%s\t%s\t%g\t%g\t%g\t%g", found[k].name,
                                 found[k].description, found[k].initial, found[k].minimum, found[k].maximum,
                                 found[k].step);
    }
    jbyteArray result = bytes_of(env, list);
    free(list);
    free(found);
    return result;
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeShaders_nativeSetParameter(JNIEnv *env, jclass type, jbyteArray name,
                                                              jfloat value) {
    (void)type;
    char *text = text_of(env, name);
    if (text) raven_shaders_set_parameter(text, value);
    free(text);
}

JNIEXPORT jbyteArray JNICALL
Java_com_ravenemu_app_bridge_NativeShaders_nativeApply(JNIEnv *env, jclass type) {
    (void)type;
    char error[512];
    return raven_shaders_apply(error, sizeof(error)) == 0 ? NULL : bytes_of(env, error);
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeShaders_clear(JNIEnv *env, jclass type) {
    (void)env;
    (void)type;
    raven_shaders_clear();
}
