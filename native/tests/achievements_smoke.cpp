// Test the production JNI adapter's event/list conversion against pinned rcheevos.
// No JVM, account, remote request, ROM or achievement award is required.
#include "../android/achievements_bridge.cpp"
#include <rc_client_internal.h>
#include <cstdlib>
#include <iostream>
#include <string>
#include <tuple>
#include <vector>

static void require(bool condition, const char* message) {
    if (!condition) { std::cerr << message << '\n'; std::exit(1); }
}

// Synthetic memory and clock for a full session against the real rc_client.
static uint8_t session_ram[0x100];
static uint32_t session_read(uint32_t address, uint8_t* buffer, uint32_t count, rc_client_t*) {
    uint32_t n = 0;
    for (; n < count && address + n < sizeof(session_ram); ++n) buffer[n] = session_ram[address + n];
    return n;
}
static rc_clock_t session_now = 1000000;
static rc_clock_t session_clock(const rc_client_t*) { return session_now; }
// Answers the oldest queued request: (id, url, post, content type) per entry.
static std::string answer(Client& c, const char* body) {
    require(c.requests.size() >= 4, "request expected");
    const int id = std::stoi(c.requests[0]);
    const std::string post = c.requests[2];
    c.requests.erase(c.requests.begin(), c.requests.begin() + 4);
    const auto call = c.pending.at(id);
    c.pending.erase(id);
    const rc_api_server_response_t response{body, std::strlen(body), 200};
    call.callback(&response, call.data);
    return post;
}

// The last award's reply leaves mastery pending until the next frame. When it
// arrives while paused (idle only), closing the client used to dereference
// the game it had just unloaded.
static void close_with_pending_mastery() {
    auto* c = new Client();
    c->rc = rc_client_create(read_memory, server_call);
    require(c->rc != nullptr, "session client allocation");
    rc_client_set_userdata(c->rc, c);
    rc_client_set_get_time_millisecs_function(c->rc, session_clock);
    rc_client_set_hardcore_enabled(c->rc, 0);
    rc_client_set_allow_background_memory_reads(c->rc, 0);
    rc_client_set_event_handler(c->rc, event_handler);
    rc_client_set_read_memory_function(c->rc, session_read);
    rc_client_begin_login_with_password(c->rc, "SyntheticUser", "SyntheticPassword", logged_in, nullptr);
    answer(*c, R"({"Success":true,"User":"SyntheticUser","Token":"SyntheticToken","Score":1,"SoftcoreScore":2,"Messages":0})");
    std::vector<uint8_t> rom(0x8000, 0);
    rc_client_begin_identify_and_load_game(c->rc, 4, nullptr, rom.data(), rom.size(), loaded, nullptr);
    while (!c->requests.empty()) {
        const auto& post = c->requests[2];
        answer(*c, post.rfind("r=achievementsets", 0) == 0 ?
            R"({"Success":true,"GameId":1234,"Title":"Synthetic game","ConsoleId":4,"ImageIconUrl":"https://media.retroachievements.org/Images/000001.png","RichPresenceGameId":1234,"RichPresencePatch":"","Sets":[{"AchievementSetId":1111,"GameId":1234,"Title":null,"Type":"core","ImageIconUrl":"https://media.retroachievements.org/Images/000001.png","Achievements":[{"ID":5501,"Title":"Last one","Description":"Synthetic","Flags":3,"Points":5,"MemAddr":"0xH0001=3","Author":"Test","BadgeName":"00234","Created":1367266583,"Modified":1376929305}],"Leaderboards":[]}]})" :
            R"({"Success":true})");
    }
    rc_client_do_frame(c->rc);
    rc_client_do_frame(c->rc);
    session_ram[1] = 3;
    rc_client_do_frame(c->rc);
    require(c->requests.size() >= 4 && c->requests[2].rfind("r=awardachievement", 0) == 0, "award request missing");
    answer(*c, R"({"Success":true,"Score":1,"SoftcoreScore":7,"AchievementID":5501,"AchievementsRemaining":0})");
    rc_client_idle(c->rc);
    rc_client_set_get_time_millisecs_function(c->rc, time_millis);
    delete c;
}

static Client* session_client() {
    auto* c = new Client();
    c->rc = rc_client_create(read_memory, server_call);
    require(c->rc != nullptr, "session client allocation");
    rc_client_set_userdata(c->rc, c);
    rc_client_set_get_time_millisecs_function(c->rc, session_clock);
    rc_client_set_hardcore_enabled(c->rc, 0);
    rc_client_set_allow_background_memory_reads(c->rc, 0);
    rc_client_set_event_handler(c->rc, event_handler);
    rc_client_set_read_memory_function(c->rc, session_read);
    return c;
}
static void close_session(Client* c) {
    rc_client_set_get_time_millisecs_function(c->rc, time_millis);
    delete c;
}
static void answer_status(Client& c, const char* body, int status) {
    require(c.requests.size() >= 4, "request expected");
    const int id = std::stoi(c.requests[0]);
    c.requests.erase(c.requests.begin(), c.requests.begin() + 4);
    const auto call = c.pending.at(id);
    c.pending.erase(id);
    const rc_api_server_response_t response{body, std::strlen(body), status};
    call.callback(&response, call.data);
}
static std::string login_error(int status, const char* body) {
    auto* c = session_client();
    rc_client_begin_login_with_password(c->rc, "SyntheticUser", "WrongPassword", logged_in, nullptr);
    answer_status(*c, body, status);
    std::string message;
    for (std::size_t i = 0; i + 5 < c->events.size(); i += 6)
        if (c->events[i] == "login_error") message = c->events[i + 1];
    close_session(c);
    return message;
}
// A wrong password and an outage do not read the same: the service's reason is shown.
static void login_reasons() {
    const auto refused = login_error(401, R"({"Success":false,"Error":"Invalid User/Password combination. Please try again","Code":"invalid_credentials","Status":401})");
    require(refused.find("refusée") != std::string::npos &&
            refused.find("Invalid User/Password combination") != std::string::npos, "refused password reason");
    const auto silent = login_error(-2, "");
    require(silent.find("ne répond pas") != std::string::npos && silent.find("refusée") == std::string::npos,
            "an outage must not read as a refused password");
    const auto lines = login_error(403, R"({"Success":false,"Error":"Line one\nLine two","Code":"access_denied","Status":403})");
    require(lines.find("Line one Line two") != std::string::npos && lines.find('\n') == std::string::npos,
            "server text stays on one line");
}
// Leaving the game forgets it: no title, list or ranking afterwards, and no
// request while idle (no ping, no game session), even with an award in flight.
static void unload_forgets_game() {
    std::memset(session_ram, 0, sizeof(session_ram));
    auto* c = session_client();
    rc_client_begin_login_with_password(c->rc, "SyntheticUser", "SyntheticPassword", logged_in, nullptr);
    answer(*c, R"({"Success":true,"User":"SyntheticUser","Token":"SyntheticToken","Score":1,"SoftcoreScore":2,"Messages":0})");
    std::vector<uint8_t> rom(0x8000, 0);
    rc_client_begin_identify_and_load_game(c->rc, 4, nullptr, rom.data(), rom.size(), loaded, nullptr);
    while (!c->requests.empty()) {
        const auto& post = c->requests[2];
        answer(*c, post.rfind("r=achievementsets", 0) == 0 ?
            R"({"Success":true,"GameId":1234,"Title":"Synthetic game","ConsoleId":4,"ImageIconUrl":"https://media.retroachievements.org/Images/000001.png","RichPresenceGameId":1234,"RichPresencePatch":"","Sets":[{"AchievementSetId":1111,"GameId":1234,"Title":null,"Type":"core","ImageIconUrl":"https://media.retroachievements.org/Images/000001.png","Achievements":[{"ID":5501,"Title":"Last one","Description":"Synthetic","Flags":3,"Points":5,"MemAddr":"0xH0001=3","Author":"Test","BadgeName":"00234","Created":1367266583,"Modified":1376929305}],"Leaderboards":[]}]})" :
            R"({"Success":true})");
    }
    // Without background memory reads, the SDK activates the game on the next frame.
    rc_client_do_frame(c->rc);
    rc_client_do_frame(c->rc);
    require(summary_records(c->rc)[3] == "Synthetic game", "fixture game loaded");
    session_ram[1] = 3;
    rc_client_do_frame(c->rc);
    require(c->requests.size() >= 4 && c->requests[2].rfind("r=awardachievement", 0) == 0, "award in flight");
    c->scoreboard = {"7", "Ranking", "1", "1", "1", "1"};
    unload_game(*c);
    const auto summary = summary_records(c->rc);
    require(summary[3].empty() && summary[4] == "0" && achievement_records(c->rc).empty() &&
            leaderboard_records(c->rc).empty() && c->scoreboard.empty(), "the left game must not be shown");
    answer(*c, R"({"Success":true,"Score":1,"SoftcoreScore":7,"AchievementID":5501,"AchievementsRemaining":0})");
    session_now += 10 * 60 * 1000;
    rc_client_idle(c->rc);
    require(c->requests.empty(), "nothing may be sent for a game that was left");
    close_session(c);
}

// The first event of one kind, as its six columns; empty when there is none.
static std::vector<std::string> first_event(const Client& c, const char* kind) {
    for (std::size_t i = 0; i + 5 < c.events.size(); i += 6)
        if (c.events[i] == kind) return {c.events.begin() + static_cast<long>(i), c.events.begin() + static_cast<long>(i) + 6};
    return {};
}
// A login the service refuses and a service out of reach are told apart.
static void login_outcomes() {
    for (const auto& [status, body, marker] : {
             std::tuple{401, R"({"Success":false,"Error":"Invalid User/Password combination. Please try again","Code":"invalid_credentials","Status":401})", "refused"},
             std::tuple{-2, "", "offline"}}) {
        auto* c = session_client();
        rc_client_begin_login_with_password(c->rc, "SyntheticUser", "Password", logged_in, nullptr);
        answer_status(*c, body, status);
        const auto event = first_event(*c, "login_error");
        require(event.size() == 6 && event[3] == marker, "login failure class");
        close_session(c);
    }
}
// A game load ends in one of four events: a game to track, a ROM the service
// does not know, a service out of reach, or a refusal with the service's reason.
static std::vector<std::string> load_outcome(int status, const char* body) {
    auto* c = session_client();
    rc_client_begin_login_with_password(c->rc, "SyntheticUser", "SyntheticPassword", logged_in, nullptr);
    answer(*c, R"({"Success":true,"User":"SyntheticUser","Token":"SyntheticToken","Score":1,"SoftcoreScore":2,"Messages":0})");
    c->events.clear();
    std::vector<uint8_t> rom(0x8000, 0);
    rc_client_begin_identify_and_load_game(c->rc, 4, nullptr, rom.data(), rom.size(), loaded, nullptr);
    answer_status(*c, body, status);
    // A known game goes on to open its session; any other outcome is final.
    while (!c->requests.empty()) answer(*c, R"({"Success":true})");
    // Without background memory reads the SDK activates the game on its next
    // tick. An idle tick is enough: the Hardcore barrier holds frames meanwhile.
    rc_client_idle(c->rc);
    std::vector<std::string> kinds;
    for (std::size_t i = 0; i + 5 < c->events.size(); i += 6)
        if (c->events[i].rfind("game", 0) == 0)
            kinds.insert(kinds.end(), {c->events[i], c->events[i + 1], c->events[i + 2], c->events[i + 3], c->events[i + 4]});
    close_session(c);
    return kinds;
}
static void load_outcomes() {
    const auto known = load_outcome(200,
        R"({"Success":true,"GameId":1234,"Title":"Synthetic game","ConsoleId":4,"ImageIconUrl":"https://media.retroachievements.org/Images/000001.png","RichPresenceGameId":1234,"RichPresencePatch":"","Sets":[{"AchievementSetId":1111,"GameId":1234,"Title":null,"Type":"core","ImageIconUrl":"https://media.retroachievements.org/Images/000001.png","Achievements":[{"ID":5501,"Title":"Last one","Description":"Synthetic","Flags":3,"Points":5,"MemAddr":"0xH0001=3","Author":"Test","BadgeName":"00234","Created":1367266583,"Modified":1376929305}],"Leaderboards":[]}]})");
    require(known.size() == 5 && known[0] == "game" && known[1] == "Synthetic game", "known game");
    // The banner of a game that starts: its achievement count and its icon.
    require(known[3] == "1" && known[4] == "https://media.retroachievements.org/Images/000001.png", "game count and icon");
    const auto unknown = load_outcome(404, R"({"Success":false,"Error":"Unknown game","Code":"not_found","Status":404})");
    require(unknown.size() == 5 && unknown[0] == "game_unknown", "unknown ROM");
    const auto offline = load_outcome(-2, "");
    require(offline.size() == 5 && offline[0] == "game_offline", "service out of reach");
    const auto garbled = load_outcome(502, "<html>Bad gateway</html>");
    require(garbled.size() == 5 && garbled[0] == "game_offline", "an answer that is not the service's");
    const auto refused = load_outcome(403, R"({"Success":false,"Error":"Access denied for this game","Code":"access_denied","Status":403})");
    require(refused.size() == 5 && refused[0] == "game_error" && refused[2] == "Access denied for this game", "refusal reason");
}

int main() {
    close_with_pending_mastery();
    login_reasons();
    login_outcomes();
    load_outcomes();
    unload_forgets_game();
    Client c;
    c.rc = rc_client_create(read_memory, server_call);
    require(c.rc != nullptr, "client allocation");
    rc_client_set_userdata(c.rc, &c);
    rc_client_set_hardcore_enabled(c.rc, 0);
    rc_client_set_event_handler(c.rc, event_handler);

    rc_client_achievement_t achievement{};
    achievement.id = 42; achievement.title = "Synthetic"; achievement.description = "Original test";
    achievement.points = 5; achievement.state = RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED;
    achievement.badge_url = "https://media.retroachievements.org/Badge/42.png";
    rc_client_event_t event{};
    event.type = RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED;
    event.achievement = &achievement;
    event_handler(&event, c.rc);
    require(c.events.size() == 6 && c.events[3] == "5" && c.events[5] == "42", "award event identifier/layout");
    c.events.clear();

    // Real SCOREBOARD events may have no event.leaderboard pointer.
    rc_client_leaderboard_scoreboard_t scoreboard{};
    scoreboard.leaderboard_id = 7;
    std::strcpy(scoreboard.submitted_score, "0:12.34");
    std::strcpy(scoreboard.best_score, "0:11.80");
    scoreboard.new_rank = 2; scoreboard.num_entries = 100;
    event = {};
    event.type = RC_CLIENT_EVENT_LEADERBOARD_SCOREBOARD;
    event.leaderboard_scoreboard = &scoreboard;
    event_handler(&event, c.rc);
    require(c.scoreboard.size() == 6 && c.scoreboard[2] == "0:12.34" && c.scoreboard[4] == "2", "scoreboard copied");
    require(c.events.size() == 6 && c.events[0] == "leaderboard_scoreboard", "scoreboard event missing");
    std::strcpy(scoreboard.best_score, "changed");
    require(c.scoreboard[3] == "0:11.80", "scoreboard retained SDK-owned storage");

    // Allocate the SDK result wrapper with a custom destructor: conversion must
    // copy every field before destroying that wrapper and its borrowed strings.
    auto* result = static_cast<rc_client_leaderboard_entry_list_info_t*>(std::calloc(1, sizeof(rc_client_leaderboard_entry_list_info_t)));
    require(result != nullptr, "result allocation");
    result->destroy_func = [](rc_client_leaderboard_entry_list_info_t* list) { std::free(list); };
    rc_client_leaderboard_entry_t entry{};
    char username[] = "SyntheticUser";
    entry.user = username; entry.rank = 3; entry.submitted = 1700000000;
    std::strcpy(entry.display, "12345");
    result->public_.entries = &entry; result->public_.num_entries = 1;
    result->public_.total_entries = 50; result->public_.user_index = 0;
    c.leaderboard_loading = true;
    leaderboard_fetched(RC_OK, nullptr, &result->public_, c.rc, nullptr);
    username[0] = 'X';
    require(!c.leaderboard_loading && c.leaderboard_total == 50, "query completion");
    require(c.leaderboard_entries == std::vector<std::string>{"3", "SyntheticUser", "12345", "1700000000", "1"}, "query fields copied");
    leaderboard_fetched(RC_INVALID_STATE, "test error", nullptr, c.rc, nullptr);
    require(!c.leaderboard_loading && !c.leaderboard_error.empty() && c.leaderboard_entries.empty(), "query error keeps stale rows");

    c.events.clear();
    event = {}; event.type = RC_CLIENT_EVENT_GAME_COMPLETED;
    event_handler(&event, c.rc);
    require(c.events.size() == 6 && c.events[0] == "completed", "softcore completion mislabeled mastery");
    rc_client_set_hardcore_enabled(c.rc, 1);
    c.events.clear(); event_handler(&event, c.rc);
    require(c.events.size() == 6 && c.events[0] == "mastered", "hardcore mastery missing");
    std::cout << "RetroAchievements: closing with pending mastery, login reasons and classes, load outcomes, leaving a game, event IDs, scoreboard, copied query rows/errors and completion modes passed\n";
}
