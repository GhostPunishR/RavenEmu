/* RetroAchievements for the Java side (RavenEmu code, AGPLv3): the player's
 * instance, bound to the game the bridge runs, and the account page's,
 * without a game. Java sends each request over HTTPS and gives the answer
 * back; texts cross as UTF-8 bytes, never as JNI's modified UTF-8.
 *
 * Every instance has one owner thread: the GL thread for the player's (or
 * the UI thread once GLSurfaceView paused it), the account page's worker for
 * its own. Only the table of handles is shared, under its lock. */
#include <jni.h>
#include <ravenemu/achievements.h>
#include "bridge_internal.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_INSTANCES 4
/* User name, password, token. */
#define MAX_CREDENTIAL_BYTES 1024u
/* The server's answer: a game's whole set stays far below. */
#define MAX_BODY_BYTES (8u * 1024u * 1024u)
#define MAX_ROM_BYTES (64u * 1024u * 1024u)
#define REQUEST_FIELDS 4
#define EVENT_FIELDS 6
#define SUMMARY_FIELDS 10

typedef struct instance {
    jlong handle;
    raven_achievements *achievements;
    int player;
} instance;

static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
static instance instances[MAX_INSTANCES];
static jlong last_handle;
/* The player's, which frames reach; its owner thread only. */
static raven_achievements *player;

/* A handle never comes back: a destroyed one finds nothing. */
static jlong keep(raven_achievements *achievements, int is_player) {
    jlong handle = 0;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_INSTANCES; ++i) {
        if (instances[i].achievements) continue;
        instances[i] = (instance){handle = ++last_handle, achievements, is_player};
        break;
    }
    pthread_mutex_unlock(&lock);
    return handle;
}

/* The instance of a handle; with `remove`, taken out of the table if it is
 * the kind asked for (the player's goes with its game only: handle 0). */
static raven_achievements *find(jlong handle, int remove, int is_player) {
    raven_achievements *found = NULL;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_INSTANCES; ++i) {
        const int match = handle > 0 ? instances[i].handle == handle
                                     : remove && is_player && instances[i].achievements == player;
        if (!match || !instances[i].achievements) continue;
        if (remove && instances[i].player != is_player) break;
        found = instances[i].achievements;
        if (remove) instances[i] = (instance){0, NULL, 0};
        break;
    }
    pthread_mutex_unlock(&lock);
    return found;
}

static void wipe(char *text) {
    if (!text) return;
    volatile char *byte = text;
    while (*byte) *byte++ = 0;
    free(text);
}

/* The bytes as a C string; NULL when absent, too long or holding a NUL. */
static char *text_of(JNIEnv *env, jbyteArray bytes, size_t limit) {
    if (!bytes) return NULL;
    const jsize size = (*env)->GetArrayLength(env, bytes);
    if (size < 0 || (size_t)size > limit) return NULL;
    char *text = (char *)malloc((size_t)size + 1);
    if (!text) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, size, (jbyte *)text);
    text[size] = 0;
    if (memchr(text, 0, (size_t)size)) {
        wipe(text);
        return NULL;
    }
    return text;
}

/* byte[][] of the texts, "" for NULL; NULL with an OutOfMemoryError pending. */
static jobjectArray texts(JNIEnv *env, const char *const *values, int count) {
    jclass bytes_class = (*env)->FindClass(env, "[B");
    if (!bytes_class) return NULL;
    jobjectArray result = (*env)->NewObjectArray(env, count, bytes_class, NULL);
    (*env)->DeleteLocalRef(env, bytes_class);
    for (int i = 0; result && i < count; ++i) {
        const char *value = values[i] ? values[i] : "";
        const jsize length = (jsize)strlen(value);
        jbyteArray item = (*env)->NewByteArray(env, length);
        if (!item) return NULL;
        (*env)->SetByteArrayRegion(env, item, 0, length, (const jbyte *)value);
        (*env)->SetObjectArrayElement(env, result, i, item);
        (*env)->DeleteLocalRef(env, item);
    }
    return result;
}

void raven_bridge_achievements_frame(void) {
    if (player) raven_achievements_frame(player);
}

void raven_bridge_achievements_idle(void) {
    if (player) raven_achievements_idle(player);
}

void raven_bridge_achievements_state_loaded(void) {
    if (player) raven_achievements_reset(player);
}

void raven_bridge_achievements_close(void) {
    if (!player) return;
    raven_achievements *closing = find(0, 1, 1);
    player = NULL;
    raven_achievements_destroy(closing);
}

/* The account page's: a login, without a game. 0 when out of memory. */
JNIEXPORT jlong JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_createAccount(JNIEnv *env, jclass clazz) {
    (void)env; (void)clazz;
    raven_achievements *achievements = raven_achievements_create(NULL, NULL);
    if (!achievements) return 0;
    const jlong handle = keep(achievements, 0);
    if (!handle) raven_achievements_destroy(achievements);
    return handle;
}

/* GL thread, once the game loaded: the player's instance, signed in with the
 * stored token, identifying the ROM. Replaces the previous one; 0 without a
 * game, a valid console or memory. */
JNIEXPORT jlong JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_startGame(
    JNIEnv *env, jclass clazz, jbyteArray user, jbyteArray token, jint console, jbyteArray rom) {
    (void)clazz;
    raven_bridge_achievements_close();
    raven_frontend *session = raven_bridge_session();
    if (!session || !rom || (console != RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY &&
            console != RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY_COLOR &&
            console != RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY_ADVANCE)) return 0;
    const jsize size = (*env)->GetArrayLength(env, rom);
    if (size <= 0 || (size_t)size > MAX_ROM_BYTES) return 0;
    char *name = text_of(env, user, MAX_CREDENTIAL_BYTES);
    char *secret = text_of(env, token, MAX_CREDENTIAL_BYTES);
    jbyte *bytes = name && secret ? (*env)->GetByteArrayElements(env, rom, NULL) : NULL;
    raven_achievements *achievements = bytes ? raven_achievements_create(session, NULL) : NULL;
    jlong handle = achievements ? keep(achievements, 1) : 0;
    if (handle) {
        /* The game waits for the login; the ROM is hashed before return. */
        raven_achievements_login_token(achievements, name, secret);
        raven_achievements_load_game(achievements, (unsigned)console, bytes, (size_t)size);
        player = achievements;
    } else if (achievements) {
        raven_achievements_destroy(achievements);
    }
    if (bytes) (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    wipe(name);
    wipe(secret);
    return handle;
}

/* The account page's; the player's goes with its game (stopGame). */
JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_destroy(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    raven_achievements_destroy(find(handle, 1, 0));
}

/* GL thread: the game plays on without RetroAchievements; a handle of an
 * earlier game is ignored. */
JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_stopGame(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    if (player && find(handle, 0, 0) == player) raven_bridge_achievements_close();
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_loginPassword(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray user, jbyteArray password) {
    (void)clazz;
    raven_achievements *achievements = find(handle, 0, 0);
    char *name = text_of(env, user, MAX_CREDENTIAL_BYTES);
    char *secret = text_of(env, password, MAX_CREDENTIAL_BYTES);
    if (achievements && name && secret) raven_achievements_login_password(achievements, name, secret);
    wipe(name);
    wipe(secret);
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_loginToken(
    JNIEnv *env, jclass clazz, jlong handle, jbyteArray user, jbyteArray token) {
    (void)clazz;
    raven_achievements *achievements = find(handle, 0, 0);
    char *name = text_of(env, user, MAX_CREDENTIAL_BYTES);
    char *secret = text_of(env, token, MAX_CREDENTIAL_BYTES);
    if (achievements && name && secret) raven_achievements_login_token(achievements, name, secret);
    wipe(name);
    wipe(secret);
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_idle(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    raven_achievements_idle(find(handle, 0, 0));
}

/* {id, url, post data, content type}, or NULL when none waits. */
JNIEXPORT jobjectArray JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_nextRequest(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    raven_achievements_request request;
    if (!raven_achievements_next_request(find(handle, 0, 0), &request)) return NULL;
    char id[16];
    snprintf(id, sizeof(id), "%u", request.id);
    const char *fields[REQUEST_FIELDS] = {id, request.url, request.post_data, request.content_type};
    return texts(env, fields, REQUEST_FIELDS);
}

/* status < 0: no answer came. The body is bounded; a larger one counts as none. */
JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_respond(
    JNIEnv *env, jclass clazz, jlong handle, jint id, jbyteArray body, jint status) {
    (void)clazz;
    raven_achievements *achievements = find(handle, 0, 0);
    if (!achievements || id <= 0) return;
    const jsize size = body ? (*env)->GetArrayLength(env, body) : 0;
    char *text = size > 0 && (size_t)size <= MAX_BODY_BYTES ? (char *)malloc((size_t)size + 1) : NULL;
    if (text) {
        (*env)->GetByteArrayRegion(env, body, 0, size, (jbyte *)text);
        text[size] = 0;
        raven_achievements_respond(achievements, (unsigned)id, text, (size_t)size, status);
        free(text);
    } else {
        raven_achievements_respond(achievements, (unsigned)id, "", 0, size > 0 ? -1 : status);
    }
}

/* {kind, title, detail, extra, image, id}, or NULL when nothing happened. */
JNIEXPORT jobjectArray JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_nextEvent(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    raven_achievements_event event;
    if (!raven_achievements_next_event(find(handle, 0, 0), &event)) return NULL;
    const char *fields[EVENT_FIELDS] = {event.kind, event.title, event.detail, event.extra, event.image,
                                        event.id};
    return texts(env, fields, EVENT_FIELDS);
}

/* {user, display name, game, score, softcore score, game id, unlocked,
 * total, points unlocked, points total}; NULL for an unknown handle. */
JNIEXPORT jobjectArray JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_summary(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    raven_achievements *achievements = find(handle, 0, 0);
    if (!achievements) return NULL;
    raven_achievements_summary summary;
    raven_achievements_get_summary(achievements, &summary);
    char numbers[7][16];
    const uint32_t values[7] = {summary.score, summary.softcore_score, summary.game_id, summary.unlocked,
                                summary.total, summary.points_unlocked, summary.points_total};
    for (int i = 0; i < 7; ++i) snprintf(numbers[i], sizeof(numbers[i]), "%u", (unsigned)values[i]);
    const char *fields[SUMMARY_FIELDS] = {summary.user, summary.display_name, summary.game_title,
                                          numbers[0], numbers[1], numbers[2], numbers[3], numbers[4],
                                          numbers[5], numbers[6]};
    return texts(env, fields, SUMMARY_FIELDS);
}

/* "rcheevos/x.y" and RavenCore's version, for the User-Agent. ASCII. */
JNIEXPORT jstring JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_sdkVersion(JNIEnv *env, jclass clazz) {
    (void)clazz;
    char clause[64];
    raven_achievements_user_agent_clause(clause, sizeof(clause));
    return (*env)->NewStringUTF(env, clause);
}

JNIEXPORT jstring JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_engineVersion(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, RAVEN_CORE_VERSION);
}
