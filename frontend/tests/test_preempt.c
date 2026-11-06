#include <ravenemu/frontend.h>

#include <libretro.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* Preemptive frames against fake_core_lag.c: a game whose hero moves two
 * frames after the button is read. Every frame is checked for one picture
 * and one frame of sound, whatever was replayed behind it. */
#define CHECK(c) do { if (!(c)) { \
    fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); \
} } while (0)

#define RIGHT (1u << RETRO_DEVICE_ID_JOYPAD_RIGHT)
#define LEFT (1u << RETRO_DEVICE_ID_JOYPAD_LEFT)
#define LAG 2u

/* fake_core_lag.c's game, read back as its battery. */
struct game {
    uint32_t frame;
    int32_t position;
    uint16_t pending[LAG];
    uint16_t padding;
    uint32_t silent_runs;
};

static const char *lag_core;
static uint16_t held;
static unsigned pictures, sound_frames;
static int32_t shown;

static void on_video(const raven_video_frame *frame, void *userdata) {
    (void)userdata;
    ++pictures;
    if (frame->pixels) shown = (int32_t)*(const uint32_t *)frame->pixels;
}

static void on_audio(const int16_t *samples, size_t frames, void *userdata) {
    (void)samples; (void)userdata;
    sound_frames += (unsigned)frames;
}

static int16_t on_input(unsigned port, unsigned device, unsigned index, unsigned id, void *userdata) {
    (void)userdata;
    return port == 0 && device == RETRO_DEVICE_JOYPAD && index == 0 && id < 16 ? (held >> id) & 1 : 0;
}

static raven_frontend *open_game(const char *path, unsigned preempt) {
    raven_frontend *host = raven_frontend_create();
    CHECK(host);
    CHECK(raven_frontend_set_preemptive_frames(host, preempt));
    CHECK(raven_frontend_open_core(host, path, ".", "."));
    raven_frontend_callbacks callbacks = {.video = on_video, .audio = on_audio, .input = on_input};
    raven_frontend_set_callbacks(host, &callbacks);
    CHECK(raven_frontend_load_rom(host, "LAG1", 4, "lag.rvn"));
    CHECK(raven_frontend_preemptive_frames(host) == preempt);
    return host;
}

/* One frame with these buttons held: exactly one picture and its sound. */
static void step(raven_frontend *host, uint16_t buttons) {
    held = buttons;
    pictures = sound_frames = 0;
    CHECK(raven_frontend_run_frame(host));
    CHECK(pictures == 1 && sound_frames == 2);
}

static struct game read_game(raven_frontend *host) {
    struct game game;
    CHECK(raven_frontend_battery_size(host) == sizeof(game));
    CHECK(raven_frontend_read_battery(host, &game, sizeof(game)));
    return game;
}

static void the_hero_answers_n_frames_sooner(void) {
    for (unsigned n = 0; n <= RAVEN_FRONTEND_PREEMPT_MAX; ++n) {
        raven_frontend *host = open_game(lag_core, n);
        for (unsigned i = 0; i < 20; ++i) {
            step(host, 0);
            CHECK(raven_frontend_replayed_frames(host) == 0);
        }
        unsigned first = 0;
        for (unsigned i = 20; i < 30 && !first; ++i) {
            step(host, RIGHT);
            CHECK(raven_frontend_replayed_frames(host) == (i == 20 ? n : 0));
            if (shown) first = i;
        }
        const unsigned gain = n < LAG ? n : LAG;
        CHECK(first == 20 + LAG - gain);
        /* Beyond the game's own lag, the hero jumps ahead. */
        if (n > LAG) CHECK(shown == (int32_t)(n - LAG + 1));
        CHECK(read_game(host).silent_runs == n);
        raven_frontend_destroy(host);
    }
}

/* The buttons over 150 frames: holds, one-frame taps, both at once. */
static uint16_t script(unsigned frame) {
    if (frame < 12) return 0;
    if (frame < 30) return RIGHT;
    if (frame == 31 || frame == 33) return LEFT;   /* taps a frame apart */
    if (frame == 40) return RIGHT | LEFT;
    if (frame >= 50 && frame < 53) return LEFT;
    if (frame == 60 || frame == 61) return RIGHT;
    if (frame >= 70 && frame < 120) return frame % 7 < 3 ? RIGHT : (frame % 11 == 0 ? LEFT : 0);
    return frame >= 140 ? RIGHT : 0;
}

static void the_replayed_timeline_is_the_one_n_frames_earlier(void) {
    enum { FRAMES = 150 };
    for (unsigned n = 1; n <= RAVEN_FRONTEND_PREEMPT_MAX; ++n) {
        raven_frontend *host = open_game(lag_core, n);
        unsigned changes = 0, replays = 0;
        for (unsigned i = 0; i < FRAMES; ++i) {
            step(host, script(i));
            if (i && script(i) != script(i - 1)) ++changes;
            replays += raven_frontend_replayed_frames(host);
        }
        const struct game preempted = read_game(host);
        CHECK(replays == n * changes);
        CHECK(preempted.silent_runs == replays);
        raven_frontend_destroy(host);

        /* Without preemption, each button pressed n frames earlier: a tap
         * stays a tap, and none is lost. */
        raven_frontend *reference = open_game(lag_core, 0);
        for (unsigned i = 0; i < FRAMES; ++i)
            step(reference, script(i + n < FRAMES ? i + n : FRAMES - 1));
        const struct game expected = read_game(reference);
        raven_frontend_destroy(reference);
        CHECK(preempted.frame == FRAMES && expected.frame == FRAMES);
        CHECK(preempted.position == expected.position);
        CHECK(memcmp(preempted.pending, expected.pending, sizeof(expected.pending)) == 0);
        CHECK(expected.silent_runs == 0);
    }
}

static void a_jump_of_the_state_is_never_replayed_across(void) {
    raven_frontend *host = open_game(lag_core, 2);
    for (unsigned i = 0; i < 30; ++i) step(host, 0);
    const size_t size = raven_frontend_state_size(host);
    CHECK(size == sizeof(struct game));
    unsigned char *saved = malloc(size);
    CHECK(saved && raven_frontend_save_state(host, saved, size));
    for (unsigned i = 0; i < 10; ++i) step(host, RIGHT);
    CHECK(read_game(host).position > 0);

    /* A loaded state: the next change replays nothing from before it. */
    CHECK(raven_frontend_load_state(host, saved, size));
    step(host, LEFT);
    CHECK(raven_frontend_replayed_frames(host) == 0);
    step(host, 0);
    CHECK(raven_frontend_replayed_frames(host) == 1);  /* one state since */
    step(host, LEFT);
    CHECK(raven_frontend_replayed_frames(host) == 2);
    CHECK(read_game(host).frame == 33);

    /* Restored SRAM, and a pause, start the history again too. */
    struct game game = read_game(host);
    CHECK(raven_frontend_restore_battery(host, &game, sizeof(game)));
    step(host, RIGHT);
    CHECK(raven_frontend_replayed_frames(host) == 0);
    step(host, RIGHT);
    raven_frontend_forget_preemption(host);
    step(host, 0);
    CHECK(raven_frontend_replayed_frames(host) == 0);
    free(saved);
    raven_frontend_destroy(host);
}

static void settings_and_a_short_history(void) {
    CHECK(!raven_frontend_set_preemptive_frames(NULL, 1));
    CHECK(raven_frontend_replayed_frames(NULL) == 0 && raven_frontend_preemptive_frames(NULL) == 0);
    raven_frontend_forget_preemption(NULL);
    raven_frontend *host = open_game(lag_core, 3);
    CHECK(!raven_frontend_set_preemptive_frames(host, RAVEN_FRONTEND_PREEMPT_MAX + 1));
    CHECK(raven_frontend_preemptive_frames(host) == 3);
    /* Right after loading, only the frames since can be replayed. */
    step(host, RIGHT);
    CHECK(raven_frontend_replayed_frames(host) == 0);
    step(host, 0);
    CHECK(raven_frontend_replayed_frames(host) == 1);
    step(host, RIGHT);
    CHECK(raven_frontend_replayed_frames(host) == 2);
    step(host, 0);
    step(host, RIGHT);
    CHECK(raven_frontend_replayed_frames(host) == 3);
    /* Changed during the game: the history starts again; off, never. */
    CHECK(raven_frontend_set_preemptive_frames(host, 1));
    step(host, 0);
    CHECK(raven_frontend_replayed_frames(host) == 0);
    step(host, RIGHT);
    CHECK(raven_frontend_replayed_frames(host) == 1);
    CHECK(raven_frontend_set_preemptive_frames(host, 0));
    for (unsigned i = 0; i < 6; ++i) {
        step(host, i % 2 ? RIGHT : LEFT);
        CHECK(raven_frontend_replayed_frames(host) == 0);
    }
    /* The setting outlives the game; the history does not. */
    CHECK(raven_frontend_set_preemptive_frames(host, 2));
    step(host, 0);
    CHECK(raven_frontend_load_rom(host, "LAG1", 4, "lag.rvn"));
    CHECK(raven_frontend_preemptive_frames(host) == 2);
    step(host, RIGHT);
    CHECK(raven_frontend_replayed_frames(host) == 0);
    raven_frontend_destroy(host);
}

static void an_incomplete_state_is_never_replayed(const char *incomplete_core) {
    raven_frontend *host = open_game(incomplete_core, 2);
    /* The frontend keeps the quirks it honours, and drops the others. */
    CHECK(read_game(host).padding == RETRO_SERIALIZATION_QUIRK_INCOMPLETE);
    for (unsigned i = 0; i < 20; ++i) {
        step(host, i % 3 ? RIGHT : 0);
        CHECK(raven_frontend_replayed_frames(host) == 0);
    }
    CHECK(read_game(host).silent_runs == 0);
    raven_frontend_destroy(host);
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s fake_core_lag fake_core_lag_incomplete\n", argv[0]);
        return 2;
    }
    lag_core = argv[1];
    the_hero_answers_n_frames_sooner();
    the_replayed_timeline_is_the_one_n_frames_earlier();
    a_jump_of_the_state_is_never_replayed_across();
    settings_and_a_short_history();
    an_incomplete_state_is_never_replayed(argv[2]);
    puts("Preemptive frames: 5 scenarios passed");
    return 0;
}
