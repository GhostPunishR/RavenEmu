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
    return loaded != 0;
}
void retro_unload_game(void) { loaded = 0; }
void retro_run(void) {
    if (!loaded) return;
    if (input_poll) input_poll();
    const int pressed = input_state && input_state(0, RETRO_DEVICE_JOYPAD, 0,
                                                    RETRO_DEVICE_ID_JOYPAD_A);
    const uint32_t pixels[4] = {
        pressed ? 0x00112233u : 0, 0x0000ff00u, 0x000000ffu, 0
    };
    const int16_t samples[4] = {100, -100, 200, -200};
    if (video) video(pixels, 2, 2, 2 * sizeof(uint32_t));
    if (audio_batch) audio_batch(samples, 2);
}
