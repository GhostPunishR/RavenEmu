// Ported from the RavenEmu 0.8.1 rcheevos adapter (native/jni/src/achievements_bridge.cpp,
// now legacy and frozen). This copy is the maintained one from here on.
#include <jni.h>
#include <rc_client.h>
#include <rc_error.h>
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <chrono>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
constexpr char closed_body[] = R"({"Success":false,"Error":"Session closed"})";
const rc_api_server_response_t closed_response{closed_body, sizeof(closed_body) - 1, 400};
struct Pending { rc_client_server_callback_t callback; void* data; };
struct Client {
    rc_client_t* rc{};
    JNIEnv* env{};
    jobject memory{};
    jmethodID read{};
    int next_id{};
    std::unordered_map<int, Pending> pending;
    std::vector<std::string> requests, events;
    bool closing{};
    rc_client_async_handle_t* leaderboard_query{};
    uint32_t leaderboard_id{};
    bool leaderboard_loading{}, leaderboard_around{};
    std::string leaderboard_error;
    uint32_t leaderboard_total{};
    std::vector<std::string> leaderboard_entries, scoreboard;
    ~Client() {
        if (!rc) return;
        closing = true;
        if (leaderboard_query) rc_client_abort_async(rc, leaderboard_query);
        // SDK callbacks own allocations: settle them before destroying the client.
        while (!pending.empty()) {
            const auto entry = pending.begin();
            const auto call = entry->second;
            pending.erase(entry);
            call.callback(&closed_response, call.data);
        }
        // The longest retry delay is 120 s. Advancing our host clock by 180 s
        // drains scheduled retries with a terminal local response, without HTTP.
        rc_client_idle(rc);
        rc_client_destroy(rc);
    }
    // Six columns: kind, title, detail, extra, image, identifier. L'image est l'URL du
    // badge du succès ou de l'avatar du compte ; vide quand il n'y en a pas.
    void event(const char* kind, const char* title, const char* detail = "", const char* extra = "",
               const char* image = "", const char* id = "") {
        if (closing) return;
        for (const char* s : {kind, title, detail, extra, image, id}) events.emplace_back(s ? s : "");
    }
};
Client& client(jlong h) { return *reinterpret_cast<Client*>(static_cast<std::uintptr_t>(h)); }
// Le SDK publie `avatar_url` et `badge_url` depuis la version 12.0, mais les
// laisse nuls tant que la réponse du serveur n'a rien donné. Les reconstruire
// depuis le nom évite une carte de profil sans portrait et un succès sans
// badge, sans jamais inventer d'hôte : c'est le SDK qui compose l'adresse.
template<typename Build> std::string image_url(const char* ready, Build build) {
    if (ready && *ready) return ready;
    char buffer[256]{};
    return build(buffer, sizeof(buffer)) == RC_OK ? std::string(buffer) : std::string();
}
std::string user_image(const rc_client_user_t* user) {
    if (!user) return {};
    return image_url(user->avatar_url, [&](char* b, std::size_t n) { return rc_client_user_get_image_url(user, b, n); });
}
std::string game_image(const rc_client_game_t* game) {
    if (!game) return {};
    return image_url(game->badge_url, [&](char* b, std::size_t n) { return rc_client_game_get_image_url(game, b, n); });
}
std::string achievement_image(const rc_client_achievement_t& a) {
    const auto* ready = a.state == RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED ? a.badge_url : a.badge_locked_url;
    return image_url(ready, [&](char* b, std::size_t n) {
        return rc_client_achievement_get_image_url(&a, a.state, b, n);
    });
}
// Le badge en couleur, même verrouillé : celui de la progression d'un succès.
std::string achievement_colour_image(const rc_client_achievement_t& a) {
    return image_url(a.badge_url, [&](char* b, std::size_t n) {
        return rc_client_achievement_get_image_url(&a, RC_CLIENT_ACHIEVEMENT_STATE_UNLOCKED, b, n);
    });
}
Client& client(const rc_client_t* rc) { return *static_cast<Client*>(rc_client_get_userdata(rc)); }
rc_clock_t time_millis(const rc_client_t* rc) {
    const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    return static_cast<rc_clock_t>(elapsed) + (client(rc).closing ? 180000 : 0);
}
struct MemoryScope {
    Client& c;
    MemoryScope(Client& c_, JNIEnv* env, jobject memory) : c(c_) {
        c.env = env; c.memory = memory;
        if (memory) {
            auto type = env->GetObjectClass(memory);
            c.read = env->GetMethodID(type, "read", "(II)[B");
            env->DeleteLocalRef(type);
        }
    }
    ~MemoryScope() { c.env = nullptr; c.memory = nullptr; c.read = nullptr; }
};
uint32_t read_memory(uint32_t address, uint8_t* buffer, uint32_t count, rc_client_t* rc) {
    auto& c = client(rc);
    if (!c.memory || !c.read || count > 0x58000) return 0;
    auto bytes = static_cast<jbyteArray>(c.env->CallObjectMethod(c.memory, c.read, static_cast<jint>(address), static_cast<jint>(count)));
    if (c.env->ExceptionCheck()) { c.env->ExceptionClear(); return 0; }
    if (!bytes) return 0;
    auto size = std::min(count, static_cast<uint32_t>(c.env->GetArrayLength(bytes)));
    c.env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(size), reinterpret_cast<jbyte*>(buffer));
    c.env->DeleteLocalRef(bytes);
    return size;
}
void server_call(const rc_api_request_t* request, rc_client_server_callback_t callback, void* data, rc_client_t* rc) {
    auto& c = client(rc);
    if (c.closing) { callback(&closed_response, data); return; }
    const int id = ++c.next_id;
    c.pending.emplace(id, Pending{callback, data});
    c.requests.push_back(std::to_string(id));
    for (const char* s : {request->url, request->post_data, request->content_type}) c.requests.emplace_back(s ? s : "");
}
void logged_in(int result, const char*, rc_client_t* rc, void*) {
    auto& c = client(rc);
    if (result == RC_OK) {
        const auto* user = rc_client_get_user_info(rc);
        c.event("login", user->username, user->display_name, user->token, user_image(user).c_str());
    } else c.event("login_error", "Connexion RetroAchievements impossible. Vérifiez vos identifiants et le réseau.");
}
void loaded(int result, const char*, rc_client_t* rc, void*) {
    auto& c = client(rc);
    const auto* game = rc_client_get_game_info(rc);
    if (result == RC_OK && game) {
        rc_client_user_game_summary_t summary{};
        rc_client_get_user_game_summary(rc, &summary);
        auto detail = std::to_string(summary.num_core_achievements) + " succès";
        if (summary.num_unsupported_achievements) detail += " • " + std::to_string(summary.num_unsupported_achievements) + " non pris en charge";
        c.event("game", game->title, detail.c_str());
    }
    else c.event("game_error", "Succès indisponibles pour cette ROM, ou connexion impossible.");
}
void event_handler(const rc_client_event_t* event, rc_client_t* rc) {
    auto& c = client(rc);
    if (event->type == RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED && event->achievement) {
        const auto& a = *event->achievement;
        const auto points = std::to_string(a.points);
        c.event("achievement", a.title, a.description, points.c_str(), achievement_image(a).c_str(), std::to_string(a.id).c_str());
    } else if (event->type == RC_CLIENT_EVENT_DISCONNECTED)
        c.event("offline", "RetroAchievements hors ligne", "Déblocages en attente : synchronisation à la reconnexion.");
    else if (event->type == RC_CLIENT_EVENT_RECONNECTED)
        c.event("online", "RetroAchievements reconnecté");
    else if (event->type == RC_CLIENT_EVENT_SERVER_ERROR)
        c.event("notice", "Erreur RetroAchievements",
            event->server_error && event->server_error->error_message ?
                event->server_error->error_message : "Le serveur a refusé une requête.");
    else if (event->type == RC_CLIENT_EVENT_GAME_COMPLETED) {
        const auto* game = rc_client_get_game_info(rc);
        const auto* user = rc_client_get_user_info(rc);
        rc_client_user_game_summary_t summary{};
        rc_client_get_user_game_summary(rc, &summary);
        const auto detail = std::string(user && user->display_name ? user->display_name : "") + " · " +
            std::to_string(summary.num_unlocked_achievements) + "/" + std::to_string(summary.num_core_achievements) +
            " succès · " + std::to_string(summary.points_unlocked) + " points";
        c.event(rc_client_get_hardcore_enabled(rc) ? "mastered" : "completed",
            game ? game->title : "Jeu", detail.c_str(), "", game_image(game).c_str());
    }
    else if (event->type == RC_CLIENT_EVENT_RESET)
        c.event("hardcore_reset", "Redémarrage requis pour le mode Hardcore");
    else if (event->type == RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_HIDE) {
        // The SDK timer sends a global HIDE with no achievement pointer.
        c.event("progress_hide", "");
    } else if (event->type == RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_SHOW ||
             event->type == RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_HIDE ||
             event->type == RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW ||
             event->type == RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE) {
        if (!event->achievement) return;
        const auto& a = *event->achievement;
        const char* kind =
            event->type == RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_SHOW ? "challenge_show" :
            event->type == RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_HIDE ? "challenge_hide" :
            "progress_show";
        const std::string image = std::strcmp(kind, "progress_show") == 0
            ? achievement_colour_image(a) : achievement_image(a);
        c.event(kind, a.title, a.measured_progress, std::to_string(a.id).c_str(), image.c_str());
    } else if (event->type == RC_CLIENT_EVENT_LEADERBOARD_TRACKER_SHOW ||
               event->type == RC_CLIENT_EVENT_LEADERBOARD_TRACKER_UPDATE ||
               event->type == RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE) {
        if (!event->leaderboard_tracker) return;
        const auto* tracker = event->leaderboard_tracker;
        const auto* kind = event->type == RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE ? "tracker_hide" : "tracker_show";
        c.event(kind, "Classement en cours", tracker->display, std::to_string(tracker->id).c_str());
    } else if (event->type == RC_CLIENT_EVENT_LEADERBOARD_SCOREBOARD) {
        // SCOREBOARD carries its own structure, not necessarily leaderboard.
        const auto* board = event->leaderboard_scoreboard;
        if (!board) return;
        const auto* info = rc_client_get_leaderboard_info(rc, board->leaderboard_id);
        const auto title = info && info->title ? info->title : "Classement";
        c.scoreboard = {std::to_string(board->leaderboard_id), title,
            board->submitted_score, board->best_score, std::to_string(board->new_rank),
            std::to_string(board->num_entries)};
        const auto detail = std::string("Score : ") + board->submitted_score + " · Record : " +
            board->best_score + " · Rang : " + std::to_string(board->new_rank) + "/" + std::to_string(board->num_entries);
        c.event("leaderboard_scoreboard", title, detail.c_str(), std::to_string(board->leaderboard_id).c_str());
    } else if (event->type == RC_CLIENT_EVENT_LEADERBOARD_STARTED ||
               event->type == RC_CLIENT_EVENT_LEADERBOARD_FAILED ||
               event->type == RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED) {
        if (!event->leaderboard) return;
        const auto* kind =
            event->type == RC_CLIENT_EVENT_LEADERBOARD_STARTED ? "leaderboard_started" :
            event->type == RC_CLIENT_EVENT_LEADERBOARD_FAILED ? "leaderboard_failed" :
            event->type == RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED ? "leaderboard_submitted" :
            "leaderboard_scoreboard";
        c.event(kind, event->leaderboard->title, event->leaderboard->description,
            std::to_string(event->leaderboard->id).c_str());
    }
}
// Copy SDK-owned strings on the owner thread. No credentials enter UI snapshots.
std::vector<std::string> summary_records(rc_client_t* rc) {
    const auto* user = rc_client_get_user_info(rc);
    const auto* game = rc_client_get_game_info(rc);
    rc_client_user_game_summary_t summary{};
    rc_client_get_user_game_summary(rc, &summary);
    return {user && user->display_name ? user->display_name : "",
        std::to_string(user ? user->score_softcore : 0),
        std::to_string(user ? user->score : 0),
        game && game->title ? game->title : "", std::to_string(game ? game->id : 0),
        std::to_string(summary.num_unlocked_achievements), std::to_string(summary.num_core_achievements),
        std::to_string(summary.points_unlocked), std::to_string(summary.points_core),
        user && user->username ? user->username : "", user_image(user), game_image(game)};
}
std::vector<std::string> achievement_records(rc_client_t* rc) {
    std::vector<std::string> result;
    std::unique_ptr<rc_client_achievement_list_t, decltype(&rc_client_destroy_achievement_list)> list(
        rc_client_create_achievement_list(rc, RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE,
            RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS), rc_client_destroy_achievement_list);
    if (!list) return result;
    for (uint32_t b = 0; b < list->num_buckets; ++b) {
        const auto& bucket = list->buckets[b];
        for (uint32_t i = 0; i < bucket.num_achievements; ++i) {
            const auto& a = *bucket.achievements[i];
            char measured_percent[32];
            // Nine significant digits preserve the SDK's float, including
            // small improvements in achievements with very large targets.
            std::snprintf(measured_percent, sizeof(measured_percent), "%.9g",
                static_cast<double>(a.measured_percent));
            for (auto value : {std::to_string(a.id), std::string(a.title ? a.title : ""),
                std::string(a.description ? a.description : ""), std::to_string(a.points),
                std::to_string(a.state), std::string(a.measured_progress),
                std::to_string(a.unlock_time), std::to_string(bucket.bucket_type),
                achievement_image(a), std::string(measured_percent)}) result.push_back(value);
        }
    }
    return result;
}
// Copy lists/results before SDK-owned storage is destroyed. Queries run only
// on explicit UI actions, never every frame or with each snapshot.
std::vector<std::string> leaderboard_records(rc_client_t* rc) {
    std::vector<std::string> result;
    std::unique_ptr<rc_client_leaderboard_list_t, decltype(&rc_client_destroy_leaderboard_list)> list(
        rc_client_create_leaderboard_list(rc, RC_CLIENT_LEADERBOARD_LIST_GROUPING_NONE), rc_client_destroy_leaderboard_list);
    if (!list) return result;
    for (uint32_t b = 0; b < list->num_buckets; ++b) {
        const auto& bucket = list->buckets[b];
        for (uint32_t i = 0; i < bucket.num_leaderboards; ++i) {
            const auto& board = *bucket.leaderboards[i];
            for (auto value : {std::to_string(board.id), std::string(board.title ? board.title : ""),
                std::string(board.description ? board.description : ""), std::string(board.tracker_value ? board.tracker_value : ""),
                std::to_string(board.state), std::to_string(board.lower_is_better)}) result.push_back(value);
        }
    }
    return result;
}
void leaderboard_fetched(int result, const char*, rc_client_leaderboard_entry_list_t* raw,
                         rc_client_t* rc, void*) {
    auto& c = client(rc);
    std::unique_ptr<rc_client_leaderboard_entry_list_t, decltype(&rc_client_destroy_leaderboard_entry_list)>
        list(raw, rc_client_destroy_leaderboard_entry_list);
    c.leaderboard_query = nullptr;
    c.leaderboard_loading = false;
    if (c.closing) return;
    c.leaderboard_entries.clear();
    if (result != RC_OK || !list) { c.leaderboard_error = "Impossible de charger les scores. Réessayez."; return; }
    c.leaderboard_total = list->total_entries;
    for (uint32_t i = 0; i < list->num_entries; ++i) {
        const auto& entry = list->entries[i];
        for (auto value : {std::to_string(entry.rank), std::string(entry.user ? entry.user : ""),
            std::string(entry.display), std::to_string(entry.submitted),
            std::to_string(static_cast<int32_t>(i) == list->user_index ? 1 : 0)})
            c.leaderboard_entries.push_back(value);
    }
}
// Java uses UTF-16, the SDK uses standard UTF-8 (not JNI modified UTF-8).
std::string utf8(JNIEnv* env, jstring value) {
    if (!value) return {};
    auto type = env->FindClass("java/lang/String");
    auto method = env->GetMethodID(type, "getBytes", "(Ljava/lang/String;)[B");
    auto encoding = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, method, encoding));
    env->DeleteLocalRef(encoding); env->DeleteLocalRef(type);
    if (!bytes) return {};
    std::string text(static_cast<std::size_t>(env->GetArrayLength(bytes)), '\0');
    env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<jbyte*>(text.data()));
    env->DeleteLocalRef(bytes); return text;
}
jstring java_string(JNIEnv* env, const std::string& text) {
    auto bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
    auto type = env->FindClass("java/lang/String");
    auto constructor = env->GetMethodID(type, "<init>", "([BLjava/lang/String;)V");
    auto encoding = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(type, constructor, bytes, encoding));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(type); env->DeleteLocalRef(encoding);
    return result;
}
jobjectArray drain(JNIEnv* env, std::vector<std::string>& records) {
    auto type = env->FindClass("java/lang/String");
    auto array = env->NewObjectArray(static_cast<jsize>(records.size()), type, nullptr);
    env->DeleteLocalRef(type);
    if (!array) return nullptr;
    for (std::size_t i = 0; i < records.size(); ++i) {
        auto value = java_string(env, records[i]);
        env->SetObjectArrayElement(array, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    records.clear(); return array;
}
// Never unwind a C++ allocation failure through JNI.
template<typename T, typename F> T guarded(JNIEnv* env, T fallback, F f) {
    try { return f(); } catch (...) {
        auto type = env->FindClass("java/lang/IllegalStateException");
        if (type) env->ThrowNew(type, "RetroAchievements native failure");
        return fallback;
    }
}
}
#define JNI_RA(name) Java_com_ravenemu_android_NativeAchievementsBridge_##name
extern "C" {
JNIEXPORT jobjectArray JNICALL JNI_RA(summary)(JNIEnv* env, jclass, jlong h) {
    return guarded<jobjectArray>(env, nullptr, [&] { auto data = summary_records(client(h).rc); return drain(env, data); });
}
JNIEXPORT jobjectArray JNICALL JNI_RA(achievements)(JNIEnv* env, jclass, jlong h) {
    return guarded<jobjectArray>(env, nullptr, [&] { auto data = achievement_records(client(h).rc); return drain(env, data); });
}
JNIEXPORT jobjectArray JNICALL JNI_RA(leaderboards)(JNIEnv* env, jclass, jlong h) {
    return guarded<jobjectArray>(env, nullptr, [&] { auto data = leaderboard_records(client(h).rc); return drain(env, data); });
}
JNIEXPORT jobjectArray JNICALL JNI_RA(leaderboardView)(JNIEnv* env, jclass, jlong h) {
    return guarded<jobjectArray>(env, nullptr, [&] {
        auto& c = client(h);
        std::vector<std::string> data{std::to_string(c.leaderboard_id), c.leaderboard_loading ? "1" : "0",
            c.leaderboard_error, std::to_string(c.leaderboard_total), c.leaderboard_around ? "1" : "0"};
        data.insert(data.end(), c.leaderboard_entries.begin(), c.leaderboard_entries.end());
        return drain(env, data);
    });
}
JNIEXPORT jobjectArray JNICALL JNI_RA(scoreboard)(JNIEnv* env, jclass, jlong h) {
    return guarded<jobjectArray>(env, nullptr, [&] { auto data = client(h).scoreboard; return drain(env, data); });
}
JNIEXPORT void JNICALL JNI_RA(fetchLeaderboard)(JNIEnv* env, jclass, jlong h, jint id, jboolean around) {
    guarded<int>(env, 0, [&] {
        auto& c = client(h);
        if (!rc_client_get_leaderboard_info(c.rc, static_cast<uint32_t>(id))) return 0;
        if (c.leaderboard_query) rc_client_abort_async(c.rc, c.leaderboard_query);
        c.leaderboard_query = nullptr;
        c.leaderboard_id = static_cast<uint32_t>(id); c.leaderboard_around = around;
        c.leaderboard_loading = true; c.leaderboard_total = 0;
        c.leaderboard_error.clear(); c.leaderboard_entries.clear();
        auto* query = around ? rc_client_begin_fetch_leaderboard_entries_around_user(c.rc, id, 20, leaderboard_fetched, nullptr) :
            rc_client_begin_fetch_leaderboard_entries(c.rc, id, 1, 20, leaderboard_fetched, nullptr);
        // An immediate callback may have completed before begin_fetch returns.
        if (c.leaderboard_loading) c.leaderboard_query = query;
        return 0;
    });
}
JNIEXPORT jstring JNICALL JNI_RA(richPresence)(JNIEnv* env, jclass, jlong h) {
    return guarded<jstring>(env, nullptr, [&] {
        char value[512]{};
        rc_client_get_rich_presence_message(client(h).rc, value, sizeof(value));
        value[sizeof(value) - 1] = 0;
        return java_string(env, std::string(value));
    });
}
JNIEXPORT jboolean JNICALL JNI_RA(hardcore)(JNIEnv*, jclass, jlong h) {
    return rc_client_get_hardcore_enabled(client(h).rc) ? JNI_TRUE : JNI_FALSE;
}
// Enabling Hardcore with a game loaded makes the SDK raise RC_CLIENT_EVENT_RESET
// ("hardcore_reset"): the frontend must then reset the game.
JNIEXPORT void JNICALL JNI_RA(setHardcore)(JNIEnv*, jclass, jlong h, jboolean enabled) {
    rc_client_set_hardcore_enabled(client(h).rc, enabled ? 1 : 0);
}
JNIEXPORT jboolean JNICALL JNI_RA(canPause)(JNIEnv*, jclass, jlong h) {
    return rc_client_can_pause(client(h).rc, nullptr) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jlong JNICALL JNI_RA(create)(JNIEnv* env, jclass) {
    return guarded<jlong>(env, 0, [] {
        auto c = std::make_unique<Client>();
        c->rc = rc_client_create(read_memory, server_call);
        if (!c->rc) return jlong{0};
        rc_client_set_userdata(c->rc, c.get());
        rc_client_set_get_time_millisecs_function(c->rc, time_millis);
        rc_client_set_host(c->rc, "https://retroachievements.org");
        rc_client_set_hardcore_enabled(c->rc, 0);
        rc_client_set_allow_background_memory_reads(c->rc, 0);
        rc_client_set_event_handler(c->rc, event_handler);
        return static_cast<jlong>(reinterpret_cast<std::uintptr_t>(c.release()));
    });
}
JNIEXPORT void JNICALL JNI_RA(destroy)(JNIEnv*, jclass, jlong h) { delete &client(h); }
JNIEXPORT void JNICALL JNI_RA(login)(JNIEnv* env, jclass, jlong h, jstring user, jstring secret, jboolean token) {
    guarded<int>(env, 0, [&] {
        auto u = utf8(env, user); auto s = utf8(env, secret);
        if (token) rc_client_begin_login_with_token(client(h).rc, u.c_str(), s.c_str(), logged_in, nullptr);
        else rc_client_begin_login_with_password(client(h).rc, u.c_str(), s.c_str(), logged_in, nullptr);
        std::fill(s.begin(), s.end(), '\0');
        return 0;
    });
}
JNIEXPORT void JNICALL JNI_RA(load)(JNIEnv* env, jclass, jlong h, jint console, jbyteArray rom) {
    guarded<int>(env, 0, [&] {
        std::vector<uint8_t> data(static_cast<std::size_t>(env->GetArrayLength(rom)));
        env->GetByteArrayRegion(rom, 0, static_cast<jsize>(data.size()), reinterpret_cast<jbyte*>(data.data()));
        rc_client_begin_identify_and_load_game(client(h).rc, static_cast<uint32_t>(console), nullptr, data.data(), data.size(), loaded, nullptr);
        return 0;
    });
}
JNIEXPORT void JNICALL JNI_RA(tick)(JNIEnv* env, jclass, jlong h, jobject memory, jboolean frame) {
    guarded<int>(env, 0, [&] { auto& c = client(h); MemoryScope scope(c, env, memory); if (frame) rc_client_do_frame(c.rc); else rc_client_idle(c.rc); return 0; });
}
JNIEXPORT void JNICALL JNI_RA(reset)(JNIEnv*, jclass, jlong h) { rc_client_reset(client(h).rc); }
JNIEXPORT jobjectArray JNICALL JNI_RA(requests)(JNIEnv* env, jclass, jlong h) { return guarded<jobjectArray>(env, nullptr, [&] { return drain(env, client(h).requests); }); }
JNIEXPORT jobjectArray JNICALL JNI_RA(events)(JNIEnv* env, jclass, jlong h) { return guarded<jobjectArray>(env, nullptr, [&] { return drain(env, client(h).events); }); }
JNIEXPORT void JNICALL JNI_RA(respond)(JNIEnv* env, jclass, jlong h, jint id, jbyteArray body, jint status, jobject memory) {
    guarded<int>(env, 0, [&] {
        auto& c = client(h); const auto entry = c.pending.find(id); if (entry == c.pending.end()) return 0;
        const auto pending = entry->second; c.pending.erase(entry);
        std::string data(static_cast<std::size_t>(env->GetArrayLength(body)), '\0');
        env->GetByteArrayRegion(body, 0, static_cast<jsize>(data.size()), reinterpret_cast<jbyte*>(data.data()));
        const rc_api_server_response_t response{data.c_str(), data.size(), status};
        MemoryScope scope(c, env, memory); pending.callback(&response, pending.data); return 0;
    });
}
}

