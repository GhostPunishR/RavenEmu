#include <libretro.h>
#include <stdint.h>
#include <string.h>

/* Test-only core; no copyrighted ROM or BIOS. */
static retro_environment_t env;
static retro_video_refresh_t video;
static retro_audio_sample_batch_t audio_batch;
static retro_input_poll_t input_poll;
static retro_input_state_t input_state;
static int loaded;
static int red;  /* The "raven_test_color" option, read at load and when it changes. */
static unsigned frames; /* Since the game loaded: each early frame sends one message. */

/* Messages as a core sends them: plain, then the extended kind, for the log
 * alone (not shown), on screen, at debug level (not shown), and malformed. */
static void send_message(unsigned frame) {
    if (!env) return;
    if (frame == 0) {
        const struct retro_message plain = {"RavenTest : jeu chargé", 180};
        env(RETRO_ENVIRONMENT_SET_MESSAGE, (void *)&plain);
        return;
    }
    struct retro_message_ext ext = {0};
    ext.duration = 3000;
    ext.level = RETRO_LOG_WARN;
    ext.target = RETRO_MESSAGE_TARGET_OSD;
    ext.type = RETRO_MESSAGE_TYPE_NOTIFICATION;
    if (frame == 1) { ext.msg = "Journal seulement"; ext.target = RETRO_MESSAGE_TARGET_LOG; }
    else if (frame == 2) ext.msg = "Attention : à l'écran";
    else if (frame == 3) { ext.msg = "Débogage"; ext.level = RETRO_LOG_DEBUG; }
    else if (frame == 4) ext.msg = "Bad \xff byte, emoji \xf0\x9f\x8e\xae, cut \xc3";
    else return;
    env(RETRO_ENVIRONMENT_SET_MESSAGE_EXT, &ext);
}

/* One option, declared as options v2 (default), v1 (FAKE_CORE_V1) or
 * legacy variables (FAKE_CORE_VARIABLES), plus a second one to keep. */
static void declare_options(void) {
#if defined(FAKE_CORE_VARIABLES)
    static const struct retro_variable variables[] = {
        {"raven_test_color", "Couleur; green|red"},
        {"raven_test_speed", "Vitesse; 1|2|4"},
        {NULL, NULL},
    };
    env(RETRO_ENVIRONMENT_SET_VARIABLES, (void *)variables);
#elif defined(FAKE_CORE_V1)
    static const struct retro_core_option_definition definitions[] = {
        {"raven_test_color", "Couleur", "Couleur du deuxième pixel.",
         {{"green", "Vert"}, {"red", "Rouge"}, {NULL, NULL}}, "green"},
        {"raven_test_speed", "Vitesse", NULL, {{"1", NULL}, {"2", NULL}, {"4", NULL}, {NULL, NULL}}, "1"},
        {NULL, NULL, NULL, {{NULL, NULL}}, NULL},
    };
    env(RETRO_ENVIRONMENT_SET_CORE_OPTIONS, (void *)definitions);
#else
    unsigned version = 0;
    if (!env(RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION, &version) || version < 2) return;
    static struct retro_core_option_v2_category categories[] = {
        {"video", "Image", "Couleurs."}, {NULL, NULL, NULL},
    };
    static struct retro_core_option_v2_definition definitions[] = {
        {"raven_test_color", "Couleur", NULL, "Couleur du deuxième pixel.", NULL, "video",
         {{"green", "Vert"}, {"red", "Rouge"}, {NULL, NULL}}, "green"},
        {"raven_test_speed", "Vitesse", NULL, NULL, NULL, NULL,
         {{"1", NULL}, {"2", NULL}, {"4", NULL}, {NULL, NULL}}, "1"},
        {NULL, NULL, NULL, NULL, NULL, NULL, {{NULL, NULL}}, NULL},
    };
    struct retro_core_options_v2 options = {categories, definitions};
    env(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2, &options);
#endif
}

static void read_options(void) {
    struct retro_variable variable = {"raven_test_color", NULL};
    red = env && env(RETRO_ENVIRONMENT_GET_VARIABLE, &variable) && variable.value &&
          strcmp(variable.value, "red") == 0;
}

unsigned retro_api_version(void) { return RETRO_API_VERSION; }
void retro_set_environment(retro_environment_t cb) {
    env = cb;
    declare_options();
}
void retro_set_video_refresh(retro_video_refresh_t cb) { video = cb; }
void retro_set_audio_sample(retro_audio_sample_t cb) { (void)cb; }
void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) { audio_batch = cb; }
void retro_set_input_poll(retro_input_poll_t cb) { input_poll = cb; }
void retro_set_input_state(retro_input_state_t cb) { input_state = cb; }
void retro_init(void) {
    enum retro_pixel_format format = RETRO_PIXEL_FORMAT_XRGB8888;
    if (env) env(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &format);
}
void retro_deinit(void) { loaded = 0; }
void retro_get_system_info(struct retro_system_info *info) {
    memset(info, 0, sizeof(*info));
    info->library_name = "RavenTestCore";
    info->library_version = "1";
    info->valid_extensions = "rvn";
    info->need_fullpath = false;
}
void retro_get_system_av_info(struct retro_system_av_info *info) {
    memset(info, 0, sizeof(*info));
    info->geometry.base_width = 2;
    info->geometry.base_height = 2;
    info->geometry.max_width = 2;
    info->geometry.max_height = 2;
    info->geometry.aspect_ratio = 1.0f;
    info->timing.fps = 60.0;
    info->timing.sample_rate = 48000.0;
}
bool retro_load_game(const struct retro_game_info *game) {
    loaded = game && game->data && game->size == 4 &&
             memcmp(game->data, "RVN1", 4) == 0;
    read_options();
    frames = 0;
    if (loaded) send_message(frames++);
    return loaded != 0;
}
void retro_unload_game(void) { loaded = 0; }
void retro_run(void) {
    if (!loaded) return;
    send_message(frames++);
    bool updated = false;
    if (env && env(RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE, &updated) && updated) read_options();
    if (input_poll) input_poll();
    const int pressed = input_state && input_state(0, RETRO_DEVICE_JOYPAD, 0,
                                                    RETRO_DEVICE_ID_JOYPAD_A);
    const uint32_t pixels[4] = {
        pressed ? 0x00112233u : 0, red ? 0x00ff0000u : 0x0000ff00u, 0x000000ffu, 0
    };
    const int16_t samples[4] = {100, -100, 200, -200};
    if (video) video(pixels, 2, 2, 2 * sizeof(uint32_t));
    if (audio_batch) audio_batch(samples, 2);
}

/* Fixed-size test battery and state, to verify C frontend round-trips. */
static unsigned char save_ram[4] = {1, 2, 3, 4};
void *retro_get_memory_data(unsigned id) {
    return id == RETRO_MEMORY_SAVE_RAM && loaded ? save_ram : NULL;
}
size_t retro_get_memory_size(unsigned id) {
    return id == RETRO_MEMORY_SAVE_RAM && loaded ? sizeof(save_ram) : 0;
}
size_t retro_serialize_size(void) { return loaded ? sizeof(save_ram) : 0; }
bool retro_serialize(void *buffer, size_t size) {
    if (!loaded || !buffer || size != sizeof(save_ram)) return false;
    memcpy(buffer, save_ram, size);
    return true;
}
bool retro_unserialize(const void *buffer, size_t size) {
    if (!loaded || !buffer || size != sizeof(save_ram)) return false;
    memcpy(save_ram, buffer, size);
    return true;
}
