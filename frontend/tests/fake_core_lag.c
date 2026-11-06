#include <libretro.h>
#include <stdint.h>
#include <string.h>

/* Test-only core with input lag, as many real games: the buttons read at
 * frame t move the hero at frame t + LAG. Its whole game serializes; the
 * battery is that game too, so the test reads it back. No ROM, no BIOS. */
#define LAG 2

static retro_environment_t env;
static retro_video_refresh_t video;
static retro_audio_sample_batch_t audio_batch;
static retro_input_poll_t input_poll;
static retro_input_state_t input_state;
static int loaded;

static struct game {
    uint32_t frame;
    int32_t position;
    uint16_t pending[LAG];  /* Buttons read, not acted on yet. */
    uint16_t padding;
    /* Frames run without picture nor sound: kept outside the timeline by
     * the test, which compares the rest. */
    uint32_t silent_runs;
} game;

unsigned retro_api_version(void) { return RETRO_API_VERSION; }
void retro_set_environment(retro_environment_t cb) { env = cb; }
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
    info->library_name = "RavenLagCore";
    info->library_version = "1";
    info->valid_extensions = "rvn";
}
void retro_get_system_av_info(struct retro_system_av_info *info) {
    memset(info, 0, sizeof(*info));
    info->geometry.base_width = info->geometry.max_width = 1;
    info->geometry.base_height = info->geometry.max_height = 1;
    info->geometry.aspect_ratio = 1.0f;
    info->timing.fps = 60.0;
    info->timing.sample_rate = 48000.0;
}
bool retro_load_game(const struct retro_game_info *game_info) {
    loaded = game_info && game_info->data && game_info->size == 4 &&
             memcmp(game_info->data, "LAG1", 4) == 0;
    memset(&game, 0, sizeof(game));
#if defined(FAKE_LAG_INCOMPLETE)
    /* A core whose states miss part of the machine: never replayed. */
    uint64_t quirks = RETRO_SERIALIZATION_QUIRK_INCOMPLETE | RETRO_SERIALIZATION_QUIRK_FRONT_VARIABLE_SIZE;
    if (loaded && env) env(RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS, &quirks);
    game.padding = (uint16_t)quirks; /* What the frontend kept of them. */
#endif
    return loaded != 0;
}
void retro_unload_game(void) { loaded = 0; }
void retro_run(void) {
    if (!loaded) return;
    if (input_poll) input_poll();
    uint16_t buttons = 0;
    for (unsigned id = 0; input_state && id < 16; ++id)
        if (input_state(0, RETRO_DEVICE_JOYPAD, 0, id)) buttons |= (uint16_t)(1u << id);
    const uint16_t acted = game.pending[0];
    memmove(game.pending, game.pending + 1, sizeof(game.pending) - sizeof(*game.pending));
    game.pending[LAG - 1] = buttons;
    if (acted & (1u << RETRO_DEVICE_ID_JOYPAD_RIGHT)) ++game.position;
    if (acted & (1u << RETRO_DEVICE_ID_JOYPAD_LEFT)) --game.position;
    ++game.frame;
    int av = RETRO_AV_ENABLE_VIDEO | RETRO_AV_ENABLE_AUDIO;
    if (env && !env(RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE, &av)) av = 3;
    if (!(av & (RETRO_AV_ENABLE_VIDEO | RETRO_AV_ENABLE_AUDIO))) ++game.silent_runs;
    /* The hero's place as the one pixel; the frame number as the sound. */
    const uint32_t pixel = (uint32_t)game.position;
    const int16_t samples[4] = {(int16_t)game.frame, (int16_t)game.frame, 0, 0};
    if (video) video((av & RETRO_AV_ENABLE_VIDEO) ? &pixel : NULL, 1, 1, sizeof(pixel));
    if (audio_batch && (av & RETRO_AV_ENABLE_AUDIO)) audio_batch(samples, 2);
}
void *retro_get_memory_data(unsigned id) {
    return id == RETRO_MEMORY_SAVE_RAM && loaded ? &game : NULL;
}
size_t retro_get_memory_size(unsigned id) {
    return id == RETRO_MEMORY_SAVE_RAM && loaded ? sizeof(game) : 0;
}
size_t retro_serialize_size(void) { return loaded ? sizeof(game) : 0; }
bool retro_serialize(void *buffer, size_t size) {
    if (!loaded || !buffer || size != sizeof(game)) return false;
    memcpy(buffer, &game, size);
    return true;
}
bool retro_unserialize(const void *buffer, size_t size) {
    if (!loaded || !buffer || size != sizeof(game)) return false;
    const uint32_t silent = game.silent_runs;
    memcpy(&game, buffer, size);
    game.silent_runs = silent;
    return true;
}
