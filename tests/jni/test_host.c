/* Stands in for the player's bridge (bridge.c): one frontend, its game, the
 * frames shown. Test only. */
#include <jni.h>
#include <ravenemu/frontend.h>
#include "bridge_internal.h"

static raven_frontend *host;

raven_frontend *raven_bridge_session(void) { return host; }

JNIEXPORT jboolean JNICALL
Java_com_ravenemu_app_bridge_AchievementsBridgeCheck_open(JNIEnv *env, jclass clazz, jstring core, jbyteArray rom) {
    (void)clazz;
    const char *path = (*env)->GetStringUTFChars(env, core, NULL);
    host = raven_frontend_create();
    int opened = path && host && raven_frontend_open_core(host, path, ".", ".");
    if (path) (*env)->ReleaseStringUTFChars(env, core, path);
    const jsize size = (*env)->GetArrayLength(env, rom);
    jbyte *bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    opened = opened && bytes && raven_frontend_load_rom(host, bytes, (size_t)size, "ravencount.gb");
    if (bytes) (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    return opened ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_ravenemu_app_bridge_AchievementsBridgeCheck_frame(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    if (!raven_frontend_run_frame(host)) return JNI_FALSE;
    raven_bridge_achievements_frame();
    return JNI_TRUE;
}

/* As closeRom: the achievements go before the game. */
JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_AchievementsBridgeCheck_close(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    raven_bridge_achievements_close();
    raven_frontend_destroy(host);
    host = 0;
}
