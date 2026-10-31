#include <ravenemu/frontend.h>
#include <ravenemu/frame_clock.h>
#include <libretro.h>

#include <stdint.h>
#include <stdio.h>
#include <string.h>

struct observations {
    unsigned video_calls;
    size_t audio_frames;
    int invalid_video;
};

static void video(const raven_video_frame *frame, void *opaque) {
    struct observations *obs = (struct observations *)opaque;
    ++obs->video_calls;
    if (!frame->pixels || frame->width != 2 || frame->height != 2 ||
        frame->pitch != 8 || frame->pixel_format != 1 ||
        ((const uint32_t *)frame->pixels)[0] != 0x00112233u)
        obs->invalid_video = 1;
}

static void audio(const int16_t *samples, size_t frames, void *opaque) {
    struct observations *obs = (struct observations *)opaque;
    if (!samples || samples[0] != 100) obs->invalid_video = 1;
    obs->audio_frames += frames;
}

static int16_t input(unsigned port, unsigned device, unsigned index,
                     unsigned id, void *opaque) {
    (void)port; (void)device; (void)index; (void)id; (void)opaque;
    return 1;
}

#define CHECK(test) do { if (!(test)) { \
    fprintf(stderr, "Test failed at line %d: %s\n", __LINE__, #test); \
    return 1; \
} } while (0)

/* The video and audio callbacks read the same userdata as observations. */
struct hardware {
    struct observations obs;
    int has_accelerometer, rumble_calls;
    unsigned effect, last_action, last_rate;
    uint16_t strength;
    float accel_x;
};
static int rumble(unsigned port, unsigned effect, uint16_t strength, void *userdata) {
    struct hardware *hw = userdata;
    if (port) return 0;
    hw->rumble_calls++; hw->effect = effect; hw->strength = strength;
    return 1;
}
static int sensor_state(unsigned port, unsigned action, unsigned rate, void *userdata) {
    struct hardware *hw = userdata;
    hw->last_action = action; hw->last_rate = rate;
    return !port && hw->has_accelerometer;
}
static float sensor_input(unsigned port, unsigned id, void *userdata) {
    const struct hardware *hw = userdata;
    return !port && id == RETRO_SENSOR_ACCELEROMETER_X ? hw->accel_x : 0.0f;
}

int main(int argc, char **argv) {
    CHECK(argc == 2);
    raven_frontend *host = raven_frontend_create();
    CHECK(host != NULL);
    CHECK(!raven_frontend_open_core(host, "/missing/ravenemu_core.so", ".", "."));
    CHECK(strlen(raven_frontend_last_error(host)) > 0);
    CHECK(raven_frontend_open_core(host, argv[1], ".", "."));

    raven_frontend *second = raven_frontend_create();
    CHECK(second != NULL);
    CHECK(!raven_frontend_open_core(second, argv[1], ".", "."));
    raven_frontend_destroy(second);

    struct observations obs = {0};
    raven_frontend_callbacks callbacks = {.video = video, .audio = audio, .input = input, .userdata = &obs};
    raven_frontend_set_callbacks(host, &callbacks);
    raven_core_info info;
    CHECK(raven_frontend_get_info(host, &info));
    CHECK(strcmp(info.name, "RavenTestCore") == 0);
    CHECK(strcmp(info.extensions, "rvn") == 0);
    const unsigned char invalid[] = {0, 0, 0, 0};
    const unsigned char valid[] = {'R', 'V', 'N', '1'};
    CHECK(!raven_frontend_load_rom(host, invalid, sizeof(invalid), "wrong.rvn"));
    CHECK(raven_frontend_take_message(host) == NULL);
    char reason[RAVEN_FRONTEND_MESSAGE_MAX];
    const char *const cheats[] = {"ok", "bad one", "bad two", ""};
    CHECK(raven_frontend_set_cheats(host, cheats, 1, reason, sizeof(reason)) == -2);  /* no game */
    {
        // Linked games need the core's "link" subsystem: this one has none.
        const void *const roms[2] = {valid, valid};
        const size_t sizes[2] = {sizeof(valid), sizeof(valid)};
        CHECK(!raven_frontend_load_linked(host, roms, sizes, NULL));
        CHECK(strcmp(raven_frontend_last_error(host), "Core has no link subsystem") == 0);
        CHECK(raven_frontend_players(host) == 0);
    }
    CHECK(raven_frontend_load_rom(host, valid, sizeof(valid), "demo.rvn"));
    CHECK(raven_frontend_get_info(host, &info));
    CHECK(info.width == 2 && info.height == 2);
    CHECK(info.fps == 60.0 && info.sample_rate == 48000.0);
    // The core's messages reach the frontend once each; the log's do not.
    const char *message = raven_frontend_take_message(host);
    CHECK(message && strcmp(message, "RavenTest : jeu chargé") == 0);
    CHECK(raven_frontend_take_message(host) == NULL);
    CHECK(raven_frontend_run_frame(host));
    CHECK(raven_frontend_take_message(host) == NULL);
    CHECK(raven_frontend_run_frame(host));
    message = raven_frontend_take_message(host);
    CHECK(message && strcmp(message, "Attention : à l'écran") == 0);
    // Cheats: the first refused code and the core's reason, kept off the screen.
    CHECK(raven_frontend_set_cheats(host, cheats, 4, reason, sizeof(reason)) == 1);
    CHECK(strcmp(reason, "RavenTest : code de triche 2 refusé") == 0);
    CHECK(raven_frontend_take_message(host) == NULL);
    CHECK(raven_frontend_set_cheats(host, cheats, 1, reason, sizeof(reason)) == -1);
    CHECK(reason[0] == 0);
    CHECK(raven_frontend_set_cheats(host, NULL, 0, NULL, 0) == -1);
    CHECK(raven_frontend_set_cheats(host, NULL, 1, reason, sizeof(reason)) == -2);
    CHECK(raven_frontend_set_cheats(host, cheats, RAVEN_FRONTEND_CHEATS_MAX + 1, reason, sizeof(reason)) == -2);
    CHECK(raven_frontend_battery_size(host) == 4);
    // A game alone is player 1 only; this core declares no link subsystem.
    CHECK(raven_frontend_players(host) == 1);
    CHECK(raven_frontend_player_battery_size(host, 0) == 4);
    CHECK(raven_frontend_player_battery_size(host, 1) == 0);
    {
        unsigned char spare[4];
        CHECK(!raven_frontend_read_player_battery(host, 1, spare, sizeof(spare)));
        CHECK(!raven_frontend_restore_player_battery(host, 1, spare, sizeof(spare)));
    }
    unsigned char battery[4] = {0};
    CHECK(raven_frontend_read_battery(host, battery, sizeof(battery)));
    CHECK(battery[0] == 1 && battery[3] == 4);
    unsigned char replacement[4] = {8, 7, 6, 5};
    CHECK(!raven_frontend_restore_battery(host, replacement, 3));
    CHECK(raven_frontend_restore_battery(host, replacement, sizeof(replacement)));
    CHECK(raven_frontend_state_size(host) == 4);
    unsigned char state[4] = {0};
    CHECK(raven_frontend_save_state(host, state, sizeof(state)));
    CHECK(memcmp(state, replacement, 4) == 0);
    CHECK(raven_frontend_restore_battery(host, battery, sizeof(battery)));
    CHECK(raven_frontend_load_state(host, state, sizeof(state)));
    CHECK(raven_frontend_read_battery(host, battery, sizeof(battery)));
    CHECK(memcmp(battery, replacement, 4) == 0);
    CHECK(obs.video_calls == 2);
    CHECK(obs.audio_frames == 4);
    // Debug level is not shown; malformed bytes and 4-byte characters become
    // '?', a character cut at the end too: valid modified UTF-8 for Java.
    CHECK(raven_frontend_run_frame(host));
    CHECK(raven_frontend_take_message(host) == NULL);
    CHECK(raven_frontend_run_frame(host));
    message = raven_frontend_take_message(host);
    CHECK(message && strcmp(message, "Bad ? byte, emoji ?, cut ?") == 0);
    CHECK(!obs.invalid_video);
    // The FPS read through Libretro must drive the host independently of
    // display refresh, while preserving audio/video callbacks for every run.
    raven_frame_clock clock = {0};
    CHECK(raven_frame_clock_configure(&clock, info.fps));
    unsigned scheduled = 0;
    for (unsigned draw = 0; draw < 120; ++draw) {
        unsigned due = raven_frame_clock_tick(&clock, (uint64_t)draw * 1000000000u / 120u);
        CHECK(due <= RAVEN_FRAME_CLOCK_MAX_CATCHUP);
        for (unsigned frame = 0; frame < due; ++frame) {
            CHECK(raven_frontend_run_frame(host));
            ++scheduled;
        }
    }
    CHECK(scheduled == 60);
    CHECK(obs.video_calls == 64 && obs.audio_frames == 128);
    CHECK(!obs.invalid_video);
    raven_frontend_unload(host);
    CHECK(!raven_frontend_run_frame(host));
    CHECK(raven_frontend_players(host) == 0);
    raven_frontend_destroy(host);

    // A platform with a vibrator and sensors: the core finds them through
    // the Libretro interfaces and drives them during the game.
    host = raven_frontend_create();
    CHECK(host && raven_frontend_open_core(host, argv[1], ".", "."));
    struct hardware hw = {0};
    raven_frontend_callbacks with_hardware = {.video = video, .audio = audio, .input = input, .userdata = &hw,
        .rumble = rumble, .sensor_state = sensor_state, .sensor_input = sensor_input};
    hw.has_accelerometer = 1;
    raven_frontend_set_callbacks(host, &with_hardware);
    CHECK(raven_frontend_load_rom(host, valid, sizeof(valid), "demo.rvn"));
    CHECK(hw.last_action == RETRO_SENSOR_ACCELEROMETER_ENABLE && hw.last_rate == 60);
    CHECK(raven_frontend_run_frame(host) && hw.rumble_calls == 0);
    hw.accel_x = 9.8f;  // tilted right
    CHECK(raven_frontend_run_frame(host));
    CHECK(hw.rumble_calls == 1 && hw.strength == 0xffff && hw.effect == RETRO_RUMBLE_STRONG);
    CHECK(raven_frontend_run_frame(host) && hw.rumble_calls == 1);
    hw.accel_x = 0.0f;
    CHECK(raven_frontend_run_frame(host));
    CHECK(hw.rumble_calls == 2 && hw.strength == 0);
    hw.accel_x = 9.8f;
    CHECK(raven_frontend_run_frame(host) && hw.strength == 0xffff);
    // Unloading the game stops the motor and the sensor.
    raven_frontend_unload(host);
    CHECK(hw.strength == 0 && hw.last_action == RETRO_SENSOR_ACCELEROMETER_DISABLE);
    raven_frontend_destroy(host);
    puts("RavenEmu Libretro frontend smoke test passed");
    return 0;
}
