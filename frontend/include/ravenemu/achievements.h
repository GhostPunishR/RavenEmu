#ifndef RAVENEMU_ACHIEVEMENTS_H
#define RAVENEMU_ACHIEVEMENTS_H

#include <ravenemu/frontend.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* RetroAchievements for the loaded game, through the official rcheevos SDK
 * (rc_client, MIT): the account, the game's identification by its hash, the
 * evaluation of its achievements once per shown frame against the core's
 * memory map, and Hardcore. RavenEmu code, after the beta's adapter
 * (native/android/achievements_bridge.cpp).
 *
 * Emulation thread only, like every call to the core: rcheevos reads the
 * game's memory from the calls below. The platform does the networking:
 * it takes each request, sends it, and gives the answer back with
 * raven_achievements_respond, on this same thread. Texts are UTF-8 (not
 * Java's modified UTF-8), copied, and valid until the next call. */
typedef struct raven_achievements raven_achievements;

/* NULL host_url: https://retroachievements.org. Hardcore starts off. */
raven_achievements *raven_achievements_create(raven_frontend *host, const char *host_url);
/* Answers every request still out with a local failure first. */
void raven_achievements_destroy(raven_achievements *achievements);

void raven_achievements_login_password(raven_achievements *achievements, const char *user, const char *password);
void raven_achievements_login_token(raven_achievements *achievements, const char *user, const char *token);
void raven_achievements_logout(raven_achievements *achievements);

/* Once the frontend loaded the game: reads its memory map and identifies the
 * ROM by its hash. console: RetroAchievements' id (4 GB, 6 GBC, 5 GBA). The
 * console being known, the ROM is hashed during the call and never read
 * after it: the caller may free it on return. */
#define RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY 4
#define RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY_COLOR 6
#define RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY_ADVANCE 5
void raven_achievements_load_game(raven_achievements *achievements, unsigned console, const void *rom, size_t size);
void raven_achievements_unload_game(raven_achievements *achievements);
/* After a reset of the game (the event "hardcore_reset" asks for one). */
void raven_achievements_reset(raven_achievements *achievements);
/* After each frame shown, never for frames replayed behind it. */
void raven_achievements_frame(raven_achievements *achievements);
/* While paused: keeps the session alive and retries what failed. */
void raven_achievements_idle(raven_achievements *achievements);

/* Enabling it during a game raises "hardcore_reset": reset the game. */
void raven_achievements_set_hardcore(raven_achievements *achievements, int enabled);
int raven_achievements_hardcore(const raven_achievements *achievements);
/* rcheevos refuses a pause too soon after the last one, in Hardcore. */
int raven_achievements_can_pause(raven_achievements *achievements);

typedef struct raven_achievements_request {
    unsigned id;
    const char *url;
    const char *post_data;     /* "" for a GET */
    const char *content_type;  /* "" when the SDK set none */
} raven_achievements_request;
/* The next request to send, oldest first; 0 when none is waiting. */
int raven_achievements_next_request(raven_achievements *achievements, raven_achievements_request *request);
/* The server's answer: its body and HTTP status; a negative status when no
 * answer came (no network). An unknown or answered id is ignored. */
void raven_achievements_respond(raven_achievements *achievements, unsigned id, const char *body, size_t size, int status);

/* What happened, for the interface to say. Six texts, "" when unused:
 *  login            user name, display name, token, avatar url
 *  login_error      server's reason, -, "offline" or "refused"
 *  game             title, achievements count, unsupported count, badge url, game id
 *  game_unknown     (the service does not know this ROM)
 *  game_offline     (no answer: try again later)
 *  game_error       server's reason
 *  achievement      title, description, points, badge url, achievement id
 *  completed / mastered   game title, unlocked count, total count, badge url, points
 *  offline / online       (awards wait for, then reach, the service)
 *  server_error     server's reason
 *  hardcore_reset   (reset the game now)
 *  challenge_show / challenge_hide / progress_show   title, progress, -, badge url, achievement id
 *  progress_hide
 *  tracker_show / tracker_hide   value, -, -, -, leaderboard id
 *  leaderboard_started / leaderboard_failed / leaderboard_submitted   title, description, -, -, id
 *  leaderboard_scoreboard   title, "Score : … · Rang : r/n", best score, -, id
 *  scores / scores_error    see raven_achievements_fetch_scores
 * Server texts come on one line, at most 200 bytes, never cut in a character. */
typedef struct raven_achievements_event {
    const char *kind, *title, *detail, *extra, *image, *id;
} raven_achievements_event;
int raven_achievements_next_event(raven_achievements *achievements, raven_achievements_event *event);

/* The account and the game, for a summary; zero/empty without them. */
typedef struct raven_achievements_summary {
    const char *user, *display_name, *game_title;
    uint32_t score, softcore_score, game_id;
    uint32_t unlocked, total, points_unlocked, points_total;
} raven_achievements_summary;
void raven_achievements_get_summary(raven_achievements *achievements, raven_achievements_summary *summary);

/* The game's achievements, in rcheevos' order (grouped by progress: recently
 * unlocked, challenges, almost there, locked, unlocked, unsupported). A copy,
 * valid until the next call or the game's unload; 0 without a game. */
typedef struct raven_achievements_entry {
    uint32_t id, points;
    const char *title, *description;
    const char *badge;     /* unlocked badge if unlocked, else the locked one */
    const char *progress;  /* measured, such as "3/10"; "" without */
    unsigned state;        /* rcheevos: 0 inactive, 1 active, 2 unlocked, 3 disabled */
    unsigned bucket;       /* RC_CLIENT_ACHIEVEMENT_BUCKET_* */
    float percent;         /* measured, 0 to 100 */
    int64_t unlocked_at;   /* seconds since 1970, 0 while locked */
} raven_achievements_entry;
size_t raven_achievements_entries(raven_achievements *achievements, const raven_achievements_entry **entries);

/* The game's leaderboards; same lifetime. tracker: the attempt's value. */
typedef struct raven_achievements_board {
    uint32_t id;
    const char *title, *description, *tracker;
    unsigned state;
    int lower_is_better;
} raven_achievements_board;
size_t raven_achievements_boards(raven_achievements *achievements, const raven_achievements_board **boards);

/* Asks the service for twenty scores of a leaderboard: the best, or those
 * around the player. The event "scores" (detail: total, extra: "top" or
 * "around", id: board) says they came; "scores_error" (title: reason,
 * extra: "offline" or "refused", id: board) that they did not. Replaces the
 * query still out. Then raven_achievements_scores reads them, until the next
 * fetch or the game's unload. */
void raven_achievements_fetch_scores(raven_achievements *achievements, uint32_t board, int around);
typedef struct raven_achievements_score {
    uint32_t rank;
    const char *user, *score;
    int64_t submitted;
    int is_player;
} raven_achievements_score;
size_t raven_achievements_scores(raven_achievements *achievements, uint32_t *board, uint32_t *total, int *around,
                                 const raven_achievements_score **scores);

/* The game's Rich Presence, on one line; "" without one. */
void raven_achievements_rich_presence(raven_achievements *achievements, char *buffer, size_t size);

/* rcheevos' clause for the User-Agent header the platform sends, such as
 * "rcheevos/12.0": RetroAchievements asks every client to name its SDK. */
void raven_achievements_user_agent_clause(char *buffer, size_t size);

#ifdef __cplusplus
}
#endif

#endif
