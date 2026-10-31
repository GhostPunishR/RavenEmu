/* RetroAchievements for the C frontend (RavenEmu code, AGPLv3), after the
 * beta's adapter native/android/achievements_bridge.cpp: the same requests
 * and events, with the game's memory read here through its Libretro memory
 * map (rcheevos' rc_libretro) rather than through Java. */
#define _POSIX_C_SOURCE 200809L
#include <ravenemu/achievements.h>
#include "frontend_internal.h"

#include <rc_client.h>
#include <rc_error.h>
#include <rc_libretro.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define FIELDS 6
#define MAX_TEXT 200
/* Advancing the clock beyond the SDK's longest retry delay (120 s) settles
 * every scheduled retry with a local failure, without a request. */
#define CLOSING_SKEW_MS 180000

typedef struct pending_call {
    unsigned id;
    rc_client_server_callback_t callback;
    void *data;
} pending_call;

typedef struct request_record {
    unsigned id;
    char *url, *post_data, *content_type;
} request_record;

typedef struct event_record {
    char *field[FIELDS];
} event_record;

struct raven_achievements {
    raven_frontend *host;
    rc_client_t *rc;
    rc_libretro_memory_regions_t memory;
    int memory_ready;
    int closing;
    unsigned next_id;
    pending_call *pending;
    size_t pending_count, pending_capacity;
    request_record *requests;
    size_t request_count, request_capacity;
    request_record current_request;
    event_record *events;
    size_t event_count, event_capacity;
    event_record current_event;
    char *summary_text[3];
};

static const char closed_body[] = "{\"Success\":false,\"Error\":\"Session closed\"}";

static char *copy(const char *text) {
    const size_t length = text ? strlen(text) : 0;
    char *result = (char *)malloc(length + 1);
    if (!result) return NULL;
    if (length) memcpy(result, text, length);
    result[length] = 0;
    return result;
}

static int grow(void **array, size_t *capacity, size_t count, size_t element) {
    if (count < *capacity) return 1;
    const size_t next = *capacity ? *capacity * 2 : 8;
    void *larger = realloc(*array, next * element);
    if (!larger) return 0;
    *array = larger;
    *capacity = next;
    return 1;
}

static void free_request(request_record *record) {
    free(record->url);
    free(record->post_data);
    free(record->content_type);
    memset(record, 0, sizeof(*record));
}

static void free_event(event_record *record) {
    for (int i = 0; i < FIELDS; ++i) free(record->field[i]);
    memset(record, 0, sizeof(*record));
}

/* Server text shown to the player: one line, bounded, never cut inside a
 * character, without the spaces around it. */
static void server_text(const char *message, char *out, size_t size) {
    size_t length = 0;
    const char *text = message ? message : "";
    while (*text == ' ' || (*text > 0 && *text < 0x20)) ++text;
    for (; text[length] && length < size - 1; ++length) {
        const unsigned char c = (unsigned char)text[length];
        out[length] = c < 0x20 || c == 0x7f ? ' ' : (char)c;
    }
    out[length] = 0;
    if (text[length] && length >= MAX_TEXT) {
        size_t cut = MAX_TEXT;
        while (cut && ((unsigned char)out[cut] & 0xC0) == 0x80) --cut;
        out[cut] = 0;
        if (cut + 3 < size) memcpy(out + cut, "\xe2\x80\xa6", 4); /* … */
        length = strlen(out);
    }
    while (length && out[length - 1] == ' ') out[--length] = 0;
}

/* Six texts, NULL for "". A failed allocation drops the event, never half of it. */
static void push_event(raven_achievements *a, const char *kind, const char *title, const char *detail,
                  const char *extra, const char *image, const char *id) {
    if (a->closing) return;
    if (!grow((void **)&a->events, &a->event_capacity, a->event_count, sizeof(event_record))) return;
    event_record record = {{0}};
    const char *values[FIELDS] = {kind, title, detail, extra, image, id};
    for (int i = 0; i < FIELDS; ++i) {
        record.field[i] = copy(values[i]);
        if (!record.field[i]) {
            free_event(&record);
            return;
        }
    }
    a->events[a->event_count++] = record;
}

static raven_achievements *owner(const rc_client_t *rc) {
    return (raven_achievements *)rc_client_get_userdata(rc);
}

static rc_clock_t now_millis(const rc_client_t *rc) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    const rc_clock_t millis = (rc_clock_t)now.tv_sec * 1000 + (rc_clock_t)(now.tv_nsec / 1000000);
    return millis + (owner(rc)->closing ? CLOSING_SKEW_MS : 0);
}

static uint32_t read_memory(uint32_t address, uint8_t *buffer, uint32_t count, rc_client_t *rc) {
    raven_achievements *a = owner(rc);
    if (!a->memory_ready) return 0;
    return rc_libretro_memory_read(&a->memory, address, buffer, count);
}

static void server_call(const rc_api_request_t *request, rc_client_server_callback_t callback,
                        void *data, rc_client_t *rc) {
    raven_achievements *a = owner(rc);
    const rc_api_server_response_t closed = {closed_body, sizeof(closed_body) - 1, 400};
    if (a->closing ||
        !grow((void **)&a->pending, &a->pending_capacity, a->pending_count, sizeof(pending_call)) ||
        !grow((void **)&a->requests, &a->request_capacity, a->request_count, sizeof(request_record))) {
        callback(&closed, data);
        return;
    }
    request_record record = {++a->next_id, copy(request->url), copy(request->post_data),
                             copy(request->content_type)};
    if (!record.url || !record.post_data || !record.content_type) {
        free_request(&record);
        callback(&closed, data);
        return;
    }
    a->pending[a->pending_count++] = (pending_call){record.id, callback, data};
    a->requests[a->request_count++] = record;
}

/* The SDK publishes avatar and badge addresses once the server sent them;
 * until then it composes them from the names, never inventing a host. */
static void user_image(const rc_client_user_t *user, char *out, size_t size) {
    out[0] = 0;
    if (!user) return;
    if (user->avatar_url && *user->avatar_url) snprintf(out, size, "%s", user->avatar_url);
    else if (rc_client_user_get_image_url(user, out, size) != RC_OK) out[0] = 0;
}

static void game_image(const rc_client_game_t *game, char *out, size_t size) {
    out[0] = 0;
    if (!game) return;
    if (game->badge_url && *game->badge_url) snprintf(out, size, "%s", game->badge_url);
    else if (rc_client_game_get_image_url(game, out, size) != RC_OK) out[0] = 0;
}

/* The unlocked badge, or the locked one; `colour` asks for the unlocked one
 * always (an achievement's progress). */
static void achievement_image(const rc_client_achievement_t *a, int colour, char *out, size_t size) {
    const int unlocked = colour || a->state == RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED;
    const char *ready = unlocked ? a->badge_url : a->badge_locked_url;
    out[0] = 0;
    if (ready && *ready) snprintf(out, size, "%s", ready);
    else if (rc_client_achievement_get_image_url(a, unlocked ? RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED : a->state,
                                                 out, size) != RC_OK) out[0] = 0;
}

/* No answer, or not one from the service: the network, not the account. */
static int unreachable(int result) { return result == RC_NO_RESPONSE || result == RC_INVALID_JSON; }

static void logged_in(int result, const char *message, rc_client_t *rc, void *userdata) {
    (void)userdata;
    raven_achievements *a = owner(rc);
    if (result == RC_OK) {
        const rc_client_user_t *user = rc_client_get_user_info(rc);
        char avatar[256];
        user_image(user, avatar, sizeof(avatar));
        push_event(a, "login", user ? user->username : "", user ? user->display_name : "",
              user ? user->token : "", avatar, NULL);
    } else {
        char reason[MAX_TEXT + 8];
        server_text(message, reason, sizeof(reason));
        push_event(a, "login_error", reason, NULL, unreachable(result) ? "offline" : "refused", NULL, NULL);
    }
}

/* A game to track, a ROM the service does not know, a service out of reach,
 * or a refusal: four outcomes the interface tells apart. */
static void loaded(int result, const char *message, rc_client_t *rc, void *userdata) {
    (void)userdata;
    raven_achievements *a = owner(rc);
    const rc_client_game_t *game = rc_client_get_game_info(rc);
    if (result == RC_OK && game && game->id) {
        rc_client_user_game_summary_t summary;
        memset(&summary, 0, sizeof(summary));
        rc_client_get_user_game_summary(rc, &summary);
        char count[16], unsupported[16], id[16], badge[256];
        snprintf(count, sizeof(count), "%u", summary.num_core_achievements);
        snprintf(unsupported, sizeof(unsupported), "%u", summary.num_unsupported_achievements);
        snprintf(id, sizeof(id), "%u", game->id);
        game_image(game, badge, sizeof(badge));
        push_event(a, "game", game->title, count, unsupported, badge, id);
    } else if (result == RC_ABORTED) {
        /* Superseded by an unload or another game: nothing to say. */
    } else if (result == RC_NO_GAME_LOADED || result == RC_OK) {
        push_event(a, "game_unknown", NULL, NULL, NULL, NULL, NULL);
    } else if (unreachable(result)) {
        push_event(a, "game_offline", NULL, NULL, NULL, NULL, NULL);
    } else {
        char reason[MAX_TEXT + 8];
        server_text(message, reason, sizeof(reason));
        push_event(a, "game_error", reason, NULL, NULL, NULL, NULL);
    }
}

static void event_handler(const rc_client_event_t *event, rc_client_t *rc) {
    raven_achievements *a = owner(rc);
    char number[16], badge[256], text[MAX_TEXT + 8];
    if (a->closing) return;
    switch (event->type) {
        case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED: {
            const rc_client_achievement_t *achievement = event->achievement;
            if (!achievement) return;
            char points[16];
            snprintf(points, sizeof(points), "%u", achievement->points);
            snprintf(number, sizeof(number), "%u", achievement->id);
            achievement_image(achievement, 0, badge, sizeof(badge));
            push_event(a, "achievement", achievement->title, achievement->description, points, badge, number);
            break;
        }
        case RC_CLIENT_EVENT_GAME_COMPLETED: {
            const rc_client_game_t *game = rc_client_get_game_info(rc);
            rc_client_user_game_summary_t summary;
            memset(&summary, 0, sizeof(summary));
            rc_client_get_user_game_summary(rc, &summary);
            char unlocked[16], total[16], points[16];
            snprintf(unlocked, sizeof(unlocked), "%u", summary.num_unlocked_achievements);
            snprintf(total, sizeof(total), "%u", summary.num_core_achievements);
            snprintf(points, sizeof(points), "%u", summary.points_unlocked);
            game_image(game, badge, sizeof(badge));
            push_event(a, rc_client_get_hardcore_enabled(rc) ? "mastered" : "completed",
                  game ? game->title : "", unlocked, total, badge, points);
            break;
        }
        case RC_CLIENT_EVENT_DISCONNECTED: push_event(a, "offline", NULL, NULL, NULL, NULL, NULL); break;
        case RC_CLIENT_EVENT_RECONNECTED: push_event(a, "online", NULL, NULL, NULL, NULL, NULL); break;
        case RC_CLIENT_EVENT_SERVER_ERROR:
            server_text(event->server_error ? event->server_error->error_message : NULL, text, sizeof(text));
            push_event(a, "server_error", text, NULL, NULL, NULL, NULL);
            break;
        case RC_CLIENT_EVENT_RESET: push_event(a, "hardcore_reset", NULL, NULL, NULL, NULL, NULL); break;
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_HIDE:
            /* The SDK's timer sends one global hide, without achievement. */
            push_event(a, "progress_hide", NULL, NULL, NULL, NULL, NULL);
            break;
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_SHOW:
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_HIDE:
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW:
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE: {
            const rc_client_achievement_t *achievement = event->achievement;
            if (!achievement) return;
            const char *kind = event->type == RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_SHOW ? "challenge_show"
                : event->type == RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_HIDE ? "challenge_hide"
                : "progress_show";
            snprintf(number, sizeof(number), "%u", achievement->id);
            achievement_image(achievement, kind[0] == 'p', badge, sizeof(badge));
            push_event(a, kind, achievement->title, achievement->measured_progress, NULL, badge, number);
            break;
        }
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_SHOW:
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_UPDATE:
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE:
            if (!event->leaderboard_tracker) return;
            snprintf(number, sizeof(number), "%u", event->leaderboard_tracker->id);
            push_event(a, event->type == RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE ? "tracker_hide" : "tracker_show",
                  event->leaderboard_tracker->display, NULL, NULL, NULL, number);
            break;
        case RC_CLIENT_EVENT_LEADERBOARD_STARTED:
        case RC_CLIENT_EVENT_LEADERBOARD_FAILED:
        case RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED:
            if (!event->leaderboard) return;
            snprintf(number, sizeof(number), "%u", event->leaderboard->id);
            push_event(a, event->type == RC_CLIENT_EVENT_LEADERBOARD_STARTED ? "leaderboard_started"
                     : event->type == RC_CLIENT_EVENT_LEADERBOARD_FAILED ? "leaderboard_failed"
                     : "leaderboard_submitted",
                  event->leaderboard->title, event->leaderboard->description, NULL, NULL, number);
            break;
        default:
            break;
    }
}

raven_achievements *raven_achievements_create(raven_frontend *host, const char *host_url) {
    raven_achievements *a = (raven_achievements *)calloc(1, sizeof(raven_achievements));
    if (!a) return NULL;
    a->host = host;
    a->rc = rc_client_create(read_memory, server_call);
    if (!a->rc) {
        free(a);
        return NULL;
    }
    rc_client_set_userdata(a->rc, a);
    rc_client_set_get_time_millisecs_function(a->rc, now_millis);
    rc_client_set_host(a->rc, host_url ? host_url : "https://retroachievements.org");
    rc_client_set_hardcore_enabled(a->rc, 0);
    /* Memory is read on the emulation thread only, inside the calls above. */
    rc_client_set_allow_background_memory_reads(a->rc, 0);
    rc_client_set_event_handler(a->rc, event_handler);
    return a;
}

static void forget_memory(raven_achievements *a) {
    if (a->memory_ready) rc_libretro_memory_destroy(&a->memory);
    memset(&a->memory, 0, sizeof(a->memory));
    a->memory_ready = 0;
}

void raven_achievements_destroy(raven_achievements *a) {
    if (!a) return;
    a->closing = 1;
    /* The SDK's callbacks own allocations: settle them before destroying it. */
    const rc_api_server_response_t closed = {closed_body, sizeof(closed_body) - 1, 400};
    while (a->pending_count) {
        const pending_call call = a->pending[--a->pending_count];
        call.callback(&closed, call.data);
    }
    rc_client_idle(a->rc);
    rc_client_destroy(a->rc);
    forget_memory(a);
    for (size_t i = 0; i < a->request_count; ++i) free_request(&a->requests[i]);
    for (size_t i = 0; i < a->event_count; ++i) free_event(&a->events[i]);
    free_request(&a->current_request);
    free_event(&a->current_event);
    for (int i = 0; i < 3; ++i) free(a->summary_text[i]);
    free(a->pending);
    free(a->requests);
    free(a->events);
    free(a);
}

void raven_achievements_login_password(raven_achievements *a, const char *user, const char *password) {
    if (a && user && password) rc_client_begin_login_with_password(a->rc, user, password, logged_in, NULL);
}

void raven_achievements_login_token(raven_achievements *a, const char *user, const char *token) {
    if (a && user && token) rc_client_begin_login_with_token(a->rc, user, token, logged_in, NULL);
}

void raven_achievements_logout(raven_achievements *a) {
    if (a) rc_client_logout(a->rc);
}

/* rc_libretro asks for the core's own memory without userdata; one game runs. */
static raven_achievements *memory_owner;

static void core_memory_info(uint32_t id, rc_libretro_core_memory_info_t *info) {
    size_t size = 0;
    info->data = memory_owner ? (uint8_t *)raven_frontend_core_memory(memory_owner->host, id, &size) : NULL;
    info->size = info->data ? size : 0;
}

void raven_achievements_load_game(raven_achievements *a, unsigned console, const void *rom, size_t size) {
    if (!a || !rom || !size) return;
    forget_memory(a);
    memory_owner = a;
    a->memory_ready = rc_libretro_memory_init(&a->memory, raven_frontend_memory_map(a->host),
                                              core_memory_info, console) != 0;
    rc_client_begin_identify_and_load_game(a->rc, console, NULL, (const uint8_t *)rom, size, loaded, NULL);
}

void raven_achievements_unload_game(raven_achievements *a) {
    if (!a) return;
    rc_client_unload_game(a->rc);
    forget_memory(a);
}

void raven_achievements_reset(raven_achievements *a) {
    if (a) rc_client_reset(a->rc);
}

void raven_achievements_frame(raven_achievements *a) {
    if (a) rc_client_do_frame(a->rc);
}

void raven_achievements_idle(raven_achievements *a) {
    if (a) rc_client_idle(a->rc);
}

void raven_achievements_set_hardcore(raven_achievements *a, int enabled) {
    if (a) rc_client_set_hardcore_enabled(a->rc, enabled ? 1 : 0);
}

int raven_achievements_hardcore(const raven_achievements *a) {
    return a && rc_client_get_hardcore_enabled(a->rc);
}

int raven_achievements_can_pause(raven_achievements *a) {
    return !a || rc_client_can_pause(a->rc, NULL);
}

int raven_achievements_next_request(raven_achievements *a, raven_achievements_request *request) {
    if (!a || !request || !a->request_count) return 0;
    free_request(&a->current_request);
    a->current_request = a->requests[0];
    memmove(a->requests, a->requests + 1, (a->request_count - 1) * sizeof(request_record));
    --a->request_count;
    request->id = a->current_request.id;
    request->url = a->current_request.url;
    request->post_data = a->current_request.post_data;
    request->content_type = a->current_request.content_type;
    return 1;
}

void raven_achievements_respond(raven_achievements *a, unsigned id, const char *body, size_t size, int status) {
    if (!a) return;
    for (size_t i = 0; i < a->pending_count; ++i) {
        if (a->pending[i].id != id) continue;
        const pending_call call = a->pending[i];
        memmove(a->pending + i, a->pending + i + 1, (a->pending_count - i - 1) * sizeof(pending_call));
        --a->pending_count;
        const rc_api_server_response_t response = {body ? body : "", body ? size : 0, status};
        call.callback(&response, call.data);
        return;
    }
}

int raven_achievements_next_event(raven_achievements *a, raven_achievements_event *event) {
    if (!a || !event || !a->event_count) return 0;
    free_event(&a->current_event);
    a->current_event = a->events[0];
    memmove(a->events, a->events + 1, (a->event_count - 1) * sizeof(event_record));
    --a->event_count;
    event->kind = a->current_event.field[0];
    event->title = a->current_event.field[1];
    event->detail = a->current_event.field[2];
    event->extra = a->current_event.field[3];
    event->image = a->current_event.field[4];
    event->id = a->current_event.field[5];
    return 1;
}

void raven_achievements_get_summary(raven_achievements *a, raven_achievements_summary *summary) {
    if (!summary) return;
    memset(summary, 0, sizeof(*summary));
    summary->user = summary->display_name = summary->game_title = "";
    if (!a) return;
    const rc_client_user_t *user = rc_client_get_user_info(a->rc);
    const rc_client_game_t *game = rc_client_get_game_info(a->rc);
    for (int i = 0; i < 3; ++i) {
        free(a->summary_text[i]);
        a->summary_text[i] = NULL;
    }
    a->summary_text[0] = copy(user && user->username ? user->username : "");
    a->summary_text[1] = copy(user && user->display_name ? user->display_name : "");
    a->summary_text[2] = copy(game && game->title ? game->title : "");
    if (a->summary_text[0]) summary->user = a->summary_text[0];
    if (a->summary_text[1]) summary->display_name = a->summary_text[1];
    if (a->summary_text[2]) summary->game_title = a->summary_text[2];
    if (user) {
        summary->score = user->score;
        summary->softcore_score = user->score_softcore;
    }
    if (game && game->id) {
        rc_client_user_game_summary_t progress;
        memset(&progress, 0, sizeof(progress));
        rc_client_get_user_game_summary(a->rc, &progress);
        summary->game_id = game->id;
        summary->unlocked = progress.num_unlocked_achievements;
        summary->total = progress.num_core_achievements;
        summary->points_unlocked = progress.points_unlocked;
        summary->points_total = progress.points_core;
    }
}

void raven_achievements_user_agent_clause(char *buffer, size_t size) {
    if (!buffer || !size) return;
    buffer[0] = 0;
    rc_client_get_user_agent_clause(NULL, buffer, size);
}
