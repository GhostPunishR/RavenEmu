#include <ravenemu/achievements.h>
#include <ravenemu/frontend.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* RetroAchievements end to end against a fake service: canned answers in the
 * formats of the pinned rcheevos, and RavenCore's real Game Boy core running
 * a ROM built here that counts frames in WRAM ($C001), which the achievement
 * watches through the core's memory map. No account, network, game or BIOS. */
#define CHECK(c) do { if (!(c)) { \
    fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); \
} } while (0)

#define LOGIN_OK "{\"Success\":true,\"User\":\"SyntheticUser\",\"Token\":\"SyntheticToken\"," \
                 "\"Score\":1,\"SoftcoreScore\":2,\"Messages\":0}"
#define GAME_SETS "{\"Success\":true,\"GameId\":1234,\"Title\":\"Synthetic game\",\"ConsoleId\":4," \
    "\"ImageIconUrl\":\"https://media.retroachievements.org/Images/000001.png\",\"RichPresenceGameId\":1234," \
    "\"RichPresencePatch\":\"\",\"Sets\":[{\"AchievementSetId\":1111,\"GameId\":1234,\"Title\":null," \
    "\"Type\":\"core\",\"ImageIconUrl\":\"https://media.retroachievements.org/Images/000001.png\"," \
    "\"Achievements\":[{\"ID\":5501,\"Title\":\"Sixty frames\",\"Description\":\"Count to sixty\"," \
    "\"Flags\":3,\"Points\":5,\"MemAddr\":\"0xHc001=60\",\"Author\":\"Test\",\"BadgeName\":\"00234\"," \
    "\"Created\":1367266583,\"Modified\":1376929305}],\"Leaderboards\":[]}]}"
#define AWARD_LAST "{\"Success\":true,\"Score\":1,\"SoftcoreScore\":7,\"AchievementID\":5501," \
                   "\"AchievementsRemaining\":0}"

/* The same set with a leaderboard that never starts, and its scores. */
#define GAME_SETS_BOARD "{\"Success\":true,\"GameId\":1234,\"Title\":\"Synthetic game\",\"ConsoleId\":4," \
    "\"ImageIconUrl\":\"https://media.retroachievements.org/Images/000001.png\",\"RichPresenceGameId\":1234," \
    "\"RichPresencePatch\":\"\",\"Sets\":[{\"AchievementSetId\":1111,\"GameId\":1234,\"Title\":null," \
    "\"Type\":\"core\",\"ImageIconUrl\":\"https://media.retroachievements.org/Images/000001.png\"," \
    "\"Achievements\":[{\"ID\":5501,\"Title\":\"Sixty frames\",\"Description\":\"Count to sixty\"," \
    "\"Flags\":3,\"Points\":5,\"MemAddr\":\"0xHc001=60\",\"Author\":\"Test\",\"BadgeName\":\"00234\"," \
    "\"Created\":1367266583,\"Modified\":1376929305}],\"Leaderboards\":[{\"ID\":4401,\"Title\":\"Course\"," \
    "\"Description\":\"Le plus haut\",\"Mem\":\"STA:0=1::CAN:0=1::SUB:0=1::VAL:0xHc001\",\"Format\":\"SCORE\"," \
    "\"LowerIsBetter\":false,\"Hidden\":false}]}]}"
#define SCORES "{\"Success\":true,\"LeaderboardData\":{\"LBID\":4401,\"LBFormat\":\"SCORE\",\"LowerIsBetter\":0," \
    "\"LBTitle\":\"Course\",\"LBDesc\":\"Le plus haut\",\"LBMem\":\"STA:0=1::CAN:0=1::SUB:0=1::VAL:0xHc001\"," \
    "\"GameID\":1234,\"LBAuthor\":\"Test\",\"LBCreated\":\"2013-10-20 22:12:21\"," \
    "\"LBUpdated\":\"2013-10-20 22:12:21\",\"Entries\":[{\"User\":\"Corbeau\",\"Rank\":1,\"Index\":1," \
    "\"Score\":120,\"DateSubmitted\":1615654895},{\"User\":\"SyntheticUser\",\"Rank\":2,\"Index\":2," \
    "\"Score\":60,\"DateSubmitted\":1615634566}],\"TotalEntries\":2}}"

static const char *gb_core;
static unsigned char rom[32 * 1024];

/* No mapper: waits for line 144 (vertical blank), adds one to $C001, waits
 * for the line to pass, again. One count per frame. */
static void make_counter_rom(void) {
    memset(rom, 0, sizeof(rom));
    const unsigned char entry[] = {0x00, 0xc3, 0x50, 0x01};  /* nop; jp $0150 */
    memcpy(rom + 0x100, entry, sizeof(entry));
    memcpy(rom + 0x134, "RAVENCOUNT", 10);
    const unsigned char code[] = {
        0xf0, 0x44, 0xfe, 0x90, 0x20, 0xfa,  /* loop: ldh a,($44); cp 144; jr nz,loop */
        0x21, 0x01, 0xc0, 0x34,              /* ld hl,$c001; inc (hl) */
        0xf0, 0x44, 0xfe, 0x90, 0x28, 0xfa,  /* wait: ldh a,($44); cp 144; jr z,wait */
        0x18, 0xee,                          /* jr loop */
    };
    memcpy(rom + 0x150, code, sizeof(code));
    unsigned char checksum = 0;
    for (size_t i = 0x134; i <= 0x14c; ++i) checksum = (unsigned char)(checksum - rom[i] - 1);
    rom[0x14d] = checksum;
}

/* Answers the oldest request; returns its POST data (copied). */
static char last_post[2048];
static const char *answer(raven_achievements *a, const char *body, int status) {
    raven_achievements_request request;
    CHECK(raven_achievements_next_request(a, &request));
    CHECK(strstr(request.url, "/dorequest.php") != NULL);
    snprintf(last_post, sizeof(last_post), "%s", request.post_data);
    raven_achievements_respond(a, request.id, body, body ? strlen(body) : 0, status);
    return last_post;
}

/* Answers until no request waits: the game's sets, then plain successes.
 * Memory is read on the emulation thread only, so rcheevos activates the
 * game at the next frame: one is run. */
static void answer_game(raven_frontend *host, raven_achievements *a, const char *sets) {
    raven_achievements_request request;
    while (raven_achievements_next_request(a, &request)) {
        const char *body = strncmp(request.post_data, "r=achievementsets", 17) == 0 ? sets : "{\"Success\":true}";
        raven_achievements_respond(a, request.id, body, strlen(body), 200);
    }
    CHECK(raven_frontend_run_frame(host));
    raven_achievements_frame(a);
}

/* The next event of this kind, skipping the others; 0 when none came. */
static int take(raven_achievements *a, const char *kind, raven_achievements_event *out) {
    raven_achievements_event event;
    while (raven_achievements_next_event(a, &event))
        if (strcmp(event.kind, kind) == 0) { *out = event; return 1; }
    return 0;
}

static void login(raven_achievements *a) {
    raven_achievements_login_password(a, "SyntheticUser", "SyntheticPassword");
    const char *post = answer(a, LOGIN_OK, 200);
    CHECK(strstr(post, "r=login2") && strstr(post, "u=SyntheticUser") && strstr(post, "p=SyntheticPassword"));
    raven_achievements_event event;
    CHECK(take(a, "login", &event));
    CHECK(strcmp(event.title, "SyntheticUser") == 0 && strcmp(event.extra, "SyntheticToken") == 0);
}

static raven_frontend *open_game(void) {
    raven_frontend *host = raven_frontend_create();
    CHECK(host && raven_frontend_open_core(host, gb_core, ".", "."));
    CHECK(raven_frontend_load_rom(host, rom, sizeof(rom), "ravencount.gb"));
    return host;
}

static void logins_say_why_they_fail(void) {
    raven_achievements *a = raven_achievements_create(NULL, NULL);
    CHECK(a);
    raven_achievements_event event;
    raven_achievements_login_password(a, "SyntheticUser", "WrongPassword");
    answer(a, "{\"Success\":false,\"Error\":\"Invalid User/Password combination.\\nPlease try again\","
              "\"Code\":\"invalid_credentials\",\"Status\":401}", 401);
    CHECK(take(a, "login_error", &event));
    CHECK(strcmp(event.extra, "refused") == 0);
    CHECK(strcmp(event.title, "Invalid User/Password combination. Please try again") == 0);
    /* No answer at all: the network, not the password. */
    raven_achievements_login_password(a, "SyntheticUser", "SyntheticPassword");
    answer(a, "", -2);
    CHECK(take(a, "login_error", &event) && strcmp(event.extra, "offline") == 0);
    /* A token from an earlier session. */
    raven_achievements_login_token(a, "SyntheticUser", "SyntheticToken");
    const char *post = answer(a, LOGIN_OK, 200);
    CHECK(strstr(post, "t=SyntheticToken") != NULL);
    CHECK(take(a, "login", &event));
    raven_achievements_summary summary;
    raven_achievements_get_summary(a, &summary);
    CHECK(strcmp(summary.user, "SyntheticUser") == 0 && summary.score == 1 && summary.softcore_score == 2);
    CHECK(strcmp(summary.game_title, "") == 0 && summary.total == 0);
    /* Unknown ids and empty calls change nothing. */
    raven_achievements_respond(a, 9999, "{}", 2, 200);
    CHECK(!raven_achievements_next_request(a, &(raven_achievements_request){0}));
    raven_achievements_destroy(a);
    raven_achievements_destroy(NULL);
    raven_achievements_frame(NULL);
}

/* Plays until the award is asked for; returns the frame, 0 if it never was. */
static unsigned play_until_award(raven_frontend *host, raven_achievements *a, unsigned limit) {
    raven_achievements_request request;
    for (unsigned frame = 1; frame <= limit; ++frame) {
        CHECK(raven_frontend_run_frame(host));
        raven_achievements_frame(a);
        if (raven_achievements_next_request(a, &request)) {
            CHECK(strncmp(request.post_data, "r=awardachievement", 18) == 0);
            snprintf(last_post, sizeof(last_post), "%s", request.post_data);
            raven_achievements_respond(a, request.id, AWARD_LAST, strlen(AWARD_LAST), 200);
            return frame;
        }
    }
    return 0;
}

static void an_achievement_unlocks_from_ravencore_memory(int hardcore) {
    raven_frontend *host = open_game();
    raven_achievements *a = raven_achievements_create(host, NULL);
    CHECK(a);
    raven_achievements_set_hardcore(a, hardcore);
    CHECK(raven_achievements_hardcore(a) == hardcore);
    login(a);
    /* As the Android bridge does: a copy, wiped and freed on return (the
     * console being known, the ROM is hashed during the call). */
    unsigned char *copy = (unsigned char *)malloc(sizeof(rom));
    CHECK(copy);
    memcpy(copy, rom, sizeof(rom));
    raven_achievements_load_game(a, RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY, copy, sizeof(rom));
    memset(copy, 0xff, sizeof(rom));
    free(copy);
    answer_game(host, a, GAME_SETS);
    raven_achievements_event event;
    CHECK(take(a, "game", &event));
    CHECK(strcmp(event.title, "Synthetic game") == 0 && strcmp(event.detail, "1") == 0);
    CHECK(strcmp(event.id, "1234") == 0 && strstr(event.image, "000001.png") != NULL);
    /* $C001 counts frames from the boot: sixty comes within the first few
     * hundred, whatever the WRAM held. */
    const unsigned frame = play_until_award(host, a, 400);
    CHECK(frame > 0);
    CHECK(strstr(last_post, "a=5501") != NULL);
    CHECK(strstr(last_post, hardcore ? "h=1" : "h=0") != NULL);
    CHECK(take(a, "achievement", &event));
    CHECK(strcmp(event.title, "Sixty frames") == 0 && strcmp(event.detail, "Count to sixty") == 0);
    CHECK(strcmp(event.extra, "5") == 0 && strcmp(event.id, "5501") == 0 && strstr(event.image, "00234") != NULL);
    /* The last one: the game is complete, or mastered in Hardcore. */
    CHECK(raven_frontend_run_frame(host));
    raven_achievements_frame(a);
    CHECK(take(a, hardcore ? "mastered" : "completed", &event));
    CHECK(strcmp(event.title, "Synthetic game") == 0 && strcmp(event.detail, "1") == 0 && strcmp(event.extra, "1") == 0);
    raven_achievements_summary summary;
    raven_achievements_get_summary(a, &summary);
    CHECK(summary.game_id == 1234 && summary.unlocked == 1 && summary.total == 1 && summary.points_unlocked == 5);
    CHECK(strcmp(summary.game_title, "Synthetic game") == 0);
    /* Leaving the game forgets it. */
    raven_achievements_unload_game(a);
    raven_achievements_get_summary(a, &summary);
    CHECK(summary.game_id == 0 && strcmp(summary.game_title, "") == 0);
    raven_achievements_destroy(a);
    raven_frontend_destroy(host);
}

static void hardcore_turned_on_in_game_asks_for_a_reset(void) {
    raven_frontend *host = open_game();
    raven_achievements *a = raven_achievements_create(host, NULL);
    login(a);
    raven_achievements_load_game(a, RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY, rom, sizeof(rom));
    answer_game(host, a, GAME_SETS);
    for (int i = 0; i < 3; ++i) {
        CHECK(raven_frontend_run_frame(host));
        raven_achievements_frame(a);
    }
    raven_achievements_set_hardcore(a, 1);
    raven_achievements_event event;
    CHECK(take(a, "hardcore_reset", &event));
    /* The console restarts in place, then the achievements with it. */
    CHECK(raven_frontend_reset(host) == 1 && raven_frontend_reset(NULL) == 0);
    raven_achievements_reset(a);
    CHECK(raven_frontend_run_frame(host));
    raven_achievements_frame(a);
    raven_achievements_destroy(a);
    raven_frontend_destroy(host);
}

/* As the Android player does: the stored token's login and the game asked
 * at once; the game's sets wait for the login. */
static void the_game_waits_for_the_token_login(void) {
    raven_frontend *host = open_game();
    raven_achievements *a = raven_achievements_create(host, NULL);
    raven_achievements_login_token(a, "SyntheticUser", "SyntheticToken");
    raven_achievements_load_game(a, RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY, rom, sizeof(rom));
    const char *post = answer(a, LOGIN_OK, 200);
    CHECK(strstr(post, "r=login2") != NULL && strstr(post, "t=SyntheticToken") != NULL);
    raven_achievements_event event;
    CHECK(take(a, "login", &event));
    answer_game(host, a, GAME_SETS);
    CHECK(take(a, "game", &event) && strcmp(event.title, "Synthetic game") == 0);
    /* A state loaded: the achievements start over, the game stays. */
    raven_achievements_reset(a);
    raven_achievements_summary summary;
    raven_achievements_get_summary(a, &summary);
    CHECK(summary.game_id == 1234 && summary.total == 1);
    raven_achievements_destroy(a);
    raven_frontend_destroy(host);
}

/* What the game menu lists: the achievements, the leaderboards and their
 * scores, the best or around the player; a query replaced is dropped. */
static void the_lists_follow_the_game_and_its_scores(void) {
    raven_frontend *host = open_game();
    raven_achievements *a = raven_achievements_create(host, NULL);
    const raven_achievements_entry *entries;
    const raven_achievements_board *boards;
    const raven_achievements_score *scores;
    CHECK(raven_achievements_entries(a, &entries) == 0 && raven_achievements_boards(a, &boards) == 0);
    login(a);
    raven_achievements_load_game(a, RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY, rom, sizeof(rom));
    answer_game(host, a, GAME_SETS_BOARD);
    raven_achievements_event event;
    CHECK(take(a, "game", &event));
    CHECK(raven_achievements_entries(a, &entries) == 1);
    CHECK(entries[0].id == 5501 && entries[0].points == 5 && strcmp(entries[0].title, "Sixty frames") == 0);
    CHECK(strcmp(entries[0].description, "Count to sixty") == 0 && strstr(entries[0].badge, "00234_lock") != NULL);
    CHECK(entries[0].state == 1 && entries[0].unlocked_at == 0);
    CHECK(raven_achievements_boards(a, &boards) == 1);
    CHECK(boards[0].id == 4401 && strcmp(boards[0].title, "Course") == 0 && !boards[0].lower_is_better);
    /* The best scores, then around the player; the first query is replaced. */
    raven_achievements_fetch_scores(a, 4401, 0);
    raven_achievements_fetch_scores(a, 4401, 1);
    raven_achievements_request first, second;
    CHECK(raven_achievements_next_request(a, &first) && strstr(first.post_data, "r=lbinfo") != NULL);
    CHECK(raven_achievements_next_request(a, &second) && strstr(second.post_data, "r=lbinfo") != NULL);
    raven_achievements_respond(a, first.id, SCORES, strlen(SCORES), 200);
    CHECK(!take(a, "scores", &event));
    raven_achievements_respond(a, second.id, SCORES, strlen(SCORES), 200);
    CHECK(take(a, "scores", &event) && strcmp(event.detail, "2") == 0 && strcmp(event.extra, "around") == 0);
    CHECK(strcmp(event.id, "4401") == 0);
    uint32_t board = 0, total = 0;
    int around = 0;
    CHECK(raven_achievements_scores(a, &board, &total, &around, &scores) == 2);
    CHECK(board == 4401 && total == 2 && around == 1);
    CHECK(scores[0].rank == 1 && strcmp(scores[0].user, "Corbeau") == 0 && !scores[0].is_player);
    CHECK(scores[1].rank == 2 && scores[1].is_player && strstr(scores[1].score, "60") != NULL);
    CHECK(scores[1].submitted == 1615634566);
    /* A refusal or no answer says so; the scores read before go with the new query. */
    raven_achievements_fetch_scores(a, 4401, 0);
    answer(a, "{\"Success\":false,\"Error\":\"Unknown leaderboard\"}", 404);
    CHECK(take(a, "scores_error", &event) && event.title[0] != 0 && strcmp(event.extra, "refused") == 0);
    CHECK(strcmp(event.id, "4401") == 0 && raven_achievements_scores(a, NULL, NULL, NULL, &scores) == 0);
    raven_achievements_fetch_scores(a, 4401, 1);
    answer(a, "", -1);
    CHECK(take(a, "scores_error", &event) && strcmp(event.extra, "offline") == 0);
    raven_achievements_fetch_scores(a, 9999, 0);
    CHECK(!raven_achievements_next_request(a, &first));
    /* Unlocked: its badge in colour, its date. */
    CHECK(play_until_award(host, a, 400) > 0);
    CHECK(raven_achievements_entries(a, &entries) == 1);
    CHECK(entries[0].state == 2 && entries[0].unlocked_at > 0 && strstr(entries[0].badge, "_lock") == NULL);
    char presence[64];
    raven_achievements_rich_presence(a, presence, sizeof(presence));
    CHECK(strlen(presence) < sizeof(presence));
    /* Leaving the game empties the lists. */
    raven_achievements_unload_game(a);
    CHECK(raven_achievements_entries(a, &entries) == 0 && raven_achievements_boards(a, &boards) == 0);
    raven_achievements_rich_presence(a, presence, sizeof(presence));
    CHECK(presence[0] == 0);
    raven_achievements_destroy(a);
    raven_frontend_destroy(host);
}

static void a_service_out_of_reach_is_told_apart(void) {
    raven_frontend *host = open_game();
    raven_achievements *a = raven_achievements_create(host, NULL);
    login(a);
    raven_achievements_load_game(a, RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY, rom, sizeof(rom));
    raven_achievements_event event;
    answer(a, "", -2);
    CHECK(take(a, "game_offline", &event));
    raven_achievements_load_game(a, RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY, rom, sizeof(rom));
    answer(a, "{\"Success\":false,\"Error\":\"This game is\\nnot allowed\"}", 403);
    CHECK(take(a, "game_error", &event) && strcmp(event.title, "This game is not allowed") == 0);
    raven_achievements_destroy(a);
    raven_frontend_destroy(host);
}

static void closing_settles_requests_still_out(void) {
    raven_frontend *host = open_game();
    raven_achievements *a = raven_achievements_create(host, NULL);
    login(a);
    raven_achievements_load_game(a, RAVEN_ACHIEVEMENTS_CONSOLE_GAMEBOY, rom, sizeof(rom));
    /* The game's sets never answered: destroy settles them itself. */
    raven_achievements_destroy(a);
    raven_frontend_destroy(host);
    char clause[64];
    raven_achievements_user_agent_clause(clause, sizeof(clause));
    CHECK(strncmp(clause, "rcheevos/", 9) == 0);
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s ravenemu_gb_libretro\n", argv[0]);
        return 2;
    }
    gb_core = argv[1];
    make_counter_rom();
    logins_say_why_they_fail();
    an_achievement_unlocks_from_ravencore_memory(0);
    an_achievement_unlocks_from_ravencore_memory(1);
    hardcore_turned_on_in_game_asks_for_a_reset();
    the_game_waits_for_the_token_login();
    the_lists_follow_the_game_and_its_scores();
    a_service_out_of_reach_is_told_apart();
    closing_settles_requests_still_out();
    puts("Achievements: 8 scenarios passed");
    return 0;
}
