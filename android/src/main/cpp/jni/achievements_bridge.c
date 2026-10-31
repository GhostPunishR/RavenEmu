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
#include <stdint.h>
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

/* GL thread, once the game loaded: the player's instance, in its mode,
 * signed in with the stored token, identifying the ROM. Replaces the
 * previous one; 0 without a game, a valid console or memory. */
JNIEXPORT jlong JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_startGame(
    JNIEnv *env, jclass clazz, jbyteArray user, jbyteArray token, jint console, jbyteArray rom, jboolean hardcore) {
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
        /* The mode first: the game is then loaded in it, without a reset.
         * The game waits for the login; the ROM is hashed before return. */
        raven_achievements_set_hardcore(achievements, hardcore == JNI_TRUE);
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

/* Fixed-size records, flattened: each field as text. */
static jobjectArray records(JNIEnv *env, const char *const *fields, size_t count) {
    return count > (size_t)INT32_MAX ? NULL : texts(env, fields, (int)count);
}

/* Ten fields per achievement: id, title, description, points, state,
 * progress, unlocked at, bucket, badge, percent. */
JNIEXPORT jobjectArray JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_entries(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    const raven_achievements_entry *entries;
    const size_t count = raven_achievements_entries(find(handle, 0, 0), &entries);
    const size_t fields = 10;
    const char **values = count ? (const char **)calloc(count * fields, sizeof(char *)) : NULL;
    char (*numbers)[6][32] = count ? calloc(count, sizeof(*numbers)) : NULL;
    if (count && (!values || !numbers)) {
        free(values);
        free(numbers);
        return NULL;
    }
    for (size_t i = 0; i < count; ++i) {
        const raven_achievements_entry *entry = &entries[i];
        snprintf(numbers[i][0], 32, "%u", (unsigned)entry->id);
        snprintf(numbers[i][1], 32, "%u", (unsigned)entry->points);
        snprintf(numbers[i][2], 32, "%u", entry->state);
        snprintf(numbers[i][3], 32, "%lld", (long long)entry->unlocked_at);
        snprintf(numbers[i][4], 32, "%u", entry->bucket);
        /* Nine significant digits keep the SDK's float, as in the beta. */
        snprintf(numbers[i][5], 32, "%.9g", (double)entry->percent);
        const char *row[10] = {numbers[i][0], entry->title, entry->description, numbers[i][1], numbers[i][2],
                               entry->progress, numbers[i][3], numbers[i][4], entry->badge, numbers[i][5]};
        memcpy(values + i * fields, row, sizeof(row));
    }
    jobjectArray result = records(env, values, count * fields);
    free(values);
    free(numbers);
    return result;
}

/* Six fields per leaderboard: id, title, description, tracker, state, lower is better. */
JNIEXPORT jobjectArray JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_boards(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    const raven_achievements_board *boards;
    const size_t count = raven_achievements_boards(find(handle, 0, 0), &boards);
    const size_t fields = 6;
    const char **values = count ? (const char **)calloc(count * fields, sizeof(char *)) : NULL;
    char (*numbers)[3][16] = count ? calloc(count, sizeof(*numbers)) : NULL;
    if (count && (!values || !numbers)) {
        free(values);
        free(numbers);
        return NULL;
    }
    for (size_t i = 0; i < count; ++i) {
        snprintf(numbers[i][0], 16, "%u", (unsigned)boards[i].id);
        snprintf(numbers[i][1], 16, "%u", boards[i].state);
        snprintf(numbers[i][2], 16, "%d", boards[i].lower_is_better ? 1 : 0);
        const char *row[6] = {numbers[i][0], boards[i].title, boards[i].description, boards[i].tracker,
                              numbers[i][1], numbers[i][2]};
        memcpy(values + i * fields, row, sizeof(row));
    }
    jobjectArray result = records(env, values, count * fields);
    free(values);
    free(numbers);
    return result;
}

JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_fetchScores(
    JNIEnv *env, jclass clazz, jlong handle, jint board, jboolean around) {
    (void)env; (void)clazz;
    if (board > 0) raven_achievements_fetch_scores(find(handle, 0, 0), (uint32_t)board, around == JNI_TRUE);
}

/* board, total, around; then five fields per score: rank, user, score,
 * submitted, is the player. */
JNIEXPORT jobjectArray JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_scores(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    raven_achievements *achievements = find(handle, 0, 0);
    if (!achievements) return NULL;
    uint32_t board = 0, total = 0;
    int around = 0;
    const raven_achievements_score *scores;
    const size_t count = raven_achievements_scores(achievements, &board, &total, &around, &scores);
    const size_t fields = 5, size = 3 + count * fields;
    const char **values = (const char **)calloc(size, sizeof(char *));
    char (*numbers)[3][32] = calloc(count + 1, sizeof(*numbers));
    if (!values || !numbers) {
        free(values);
        free(numbers);
        return NULL;
    }
    snprintf(numbers[count][0], 32, "%u", (unsigned)board);
    snprintf(numbers[count][1], 32, "%u", (unsigned)total);
    values[0] = numbers[count][0];
    values[1] = numbers[count][1];
    values[2] = around ? "1" : "0";
    for (size_t i = 0; i < count; ++i) {
        snprintf(numbers[i][0], 32, "%u", (unsigned)scores[i].rank);
        snprintf(numbers[i][1], 32, "%lld", (long long)scores[i].submitted);
        const char *row[5] = {numbers[i][0], scores[i].user, scores[i].score, numbers[i][1],
                              scores[i].is_player ? "1" : "0"};
        memcpy(values + 3 + i * fields, row, sizeof(row));
    }
    jobjectArray result = records(env, values, size);
    free(values);
    free(numbers);
    return result;
}

/* The Rich Presence, as UTF-8 bytes; empty without one. */
JNIEXPORT jbyteArray JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_richPresence(JNIEnv *env, jclass clazz, jlong handle) {
    (void)clazz;
    char presence[256];
    raven_achievements_rich_presence(find(handle, 0, 0), presence, sizeof(presence));
    const jsize length = (jsize)strlen(presence);
    jbyteArray result = (*env)->NewByteArray(env, length);
    if (result) (*env)->SetByteArrayRegion(env, result, 0, length, (const jbyte *)presence);
    return result;
}

/* Hardcore: before the login, or during a game (then "hardcore_reset"). */
JNIEXPORT void JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_setHardcore(JNIEnv *env, jclass clazz, jlong handle, jboolean on) {
    (void)env; (void)clazz;
    raven_achievements_set_hardcore(find(handle, 0, 0), on == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_hardcore(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    return raven_achievements_hardcore(find(handle, 0, 0)) ? JNI_TRUE : JNI_FALSE;
}

/* In Hardcore, rcheevos refuses a pause too soon after the last one; it
 * records the pause it allows. */
JNIEXPORT jboolean JNICALL
Java_com_ravenemu_app_bridge_NativeAchievements_canPause(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env; (void)clazz;
    raven_achievements *achievements = find(handle, 0, 0);
    return !achievements || raven_achievements_can_pause(achievements) ? JNI_TRUE : JNI_FALSE;
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
