#define _POSIX_C_SOURCE 200809L
#include <ravenemu/frontend.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* Validate the real RavenCore Libretro adapters can be loaded by the new C
 * frontend on Linux, with the options the application sets. No game or BIOS
 * is needed: the Game Boy ROM below is built here, an empty screen. */

#define CHECK(test) do { if (!(test)) { \
    fprintf(stderr, "Test failed at line %d: %s\n", __LINE__, #test); \
    return 1; \
} } while (0)

static uint32_t first_pixel;
static unsigned frames_seen;
static uint16_t held;  /* Port 0's buttons, for preemptive frames. */

static int16_t input(unsigned port, unsigned device, unsigned index, unsigned id, void *opaque) {
    (void)opaque; (void)index;
    return port == 0 && device == 1 /* RETRO_DEVICE_JOYPAD */ && id < 16 ? (held >> id) & 1 : 0;
}

static void video(const raven_video_frame *frame, void *opaque) {
    (void)opaque;
    if (!frame->pixels || frame->pixel_format != 1 /* XRGB8888 */) return;
    first_pixel = ((const uint32_t *)frame->pixels)[0] & 0x00ffffffu;
    ++frames_seen;
}

/* 32 KiB, no mapper: a jump to 0x150 and a loop there. The screen keeps the
 * state after the boot ROM, tiles empty: every pixel is the lightest shade. */
static void make_rom(unsigned char *rom, size_t size) {
    memset(rom, 0, size);
    const unsigned char entry[] = {0x00, 0xc3, 0x50, 0x01};  /* nop; jp 0x0150 */
    memcpy(rom + 0x100, entry, sizeof(entry));
    memcpy(rom + 0x134, "RAVENTEST", 9);
    rom[0x150] = 0x18;  /* jr -2 */
    rom[0x151] = 0xfe;
    unsigned char checksum = 0;
    for (size_t i = 0x134; i <= 0x14c; ++i) checksum = (unsigned char)(checksum - rom[i] - 1);
    rom[0x14d] = checksum;
}

/* The lightest shade of the Game Boy Color's RGB555, as the core outputs it. */
#define GBC_WHITE 0xffffffu

static int has_option(raven_frontend *host, const char *key, const char *value) {
    const char *current = raven_frontend_option_value(host, key);
    return current && (!value || strcmp(current, value) == 0);
}

static int check_options(raven_frontend *host, const char *system) {
    char key[64];
    snprintf(key, sizeof(key), "ravenemu_%s_frame_blending", system);
    CHECK(has_option(host, key, "disabled"));
    CHECK(raven_frontend_set_option(host, key, "mix"));
    CHECK(raven_frontend_set_option(host, key, "ghosting"));
    CHECK(raven_frontend_set_option(host, key, "disabled"));
    if (strcmp(system, "gb") == 0) {
        CHECK(has_option(host, "ravenemu_gb_palette", "dmg"));
        CHECK(!has_option(host, "ravenemu_gbc_color_correction", NULL));
    } else {
        snprintf(key, sizeof(key), "ravenemu_%s_color_correction", system);
        CHECK(has_option(host, key, "disabled"));
        CHECK(raven_frontend_set_option(host, key, "enabled"));
        CHECK(raven_frontend_set_option(host, key, "disabled"));
        CHECK(!has_option(host, "ravenemu_gb_palette", NULL));
    }
    return 0;
}

static int run_until_frame(raven_frontend *host) {
    const unsigned before = frames_seen;
    for (int frame = 0; frame < 8; ++frame) {
        CHECK(raven_frontend_run_frame(host));
        if (frames_seen > before + 1) return 0;
    }
    fprintf(stderr, "No new frame from the Game Boy core\n");
    return 1;
}

static unsigned char rom[32 * 1024];

/* The palette chosen before loading, then changed during the game. */
static int check_gb_palette(raven_frontend *host) {
    make_rom(rom, sizeof(rom));
    raven_frontend_callbacks callbacks = {.video = video};
    raven_frontend_set_callbacks(host, &callbacks);
    CHECK(raven_frontend_set_option(host, "ravenemu_gb_palette", "gray"));
    CHECK(raven_frontend_load_rom(host, rom, sizeof(rom), "raventest.gb"));
    if (run_until_frame(host)) return 1;
    CHECK(first_pixel == 0xffffffu);
    CHECK(raven_frontend_set_option(host, "ravenemu_gb_palette", "pocket"));
    if (run_until_frame(host)) return 1;
    CHECK(first_pixel == 0xecececu);
    CHECK(raven_frontend_set_option(host, "ravenemu_gb_palette", "dmg"));
    if (run_until_frame(host)) return 1;
    CHECK(first_pixel == 0xe0f8d0u);
    raven_frontend_unload(host);
    return 0;
}

/* The same Game Boy game in color on the Game Boy Color core, as the
 * application runs it: its GBA hardware and colors set before loading, the
 * colors changed again during the game. */
static int check_gb_in_color(raven_frontend *host) {
    make_rom(rom, sizeof(rom));
    raven_frontend_callbacks callbacks = {.video = video};
    raven_frontend_set_callbacks(host, &callbacks);
    CHECK(raven_frontend_set_option(host, "ravenemu_gbc_hardware", "gba"));
    CHECK(raven_frontend_set_option(host, "ravenemu_gbc_dmg_colors", "grayscale"));
    CHECK(raven_frontend_load_rom(host, rom, sizeof(rom), "raventest.gb"));
    if (run_until_frame(host)) return 1;
    CHECK(first_pixel == GBC_WHITE);
    CHECK(raven_frontend_set_option(host, "ravenemu_gbc_dmg_colors", "b_right"));  /* negative */
    if (run_until_frame(host)) return 1;
    CHECK(first_pixel == 0x000000u);
    CHECK(raven_frontend_set_option(host, "ravenemu_gbc_dmg_colors", "auto"));
    CHECK(raven_frontend_set_option(host, "ravenemu_gbc_hardware", "gbc"));
    if (run_until_frame(host)) return 1;
    CHECK(first_pixel == GBC_WHITE);
    raven_frontend_unload(host);
    return 0;
}

/* A GBA ROM that loops at its entry (b .), its header's fixed byte set. */
static unsigned char gba_rom[1024];

static uint16_t buttons_at(unsigned frame) {
    return frame % 9 < 4 ? (uint16_t)(1u << (frame % 16)) : 0;
}

static double elapsed_ms(const struct timespec *start) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (double)(now.tv_sec - start->tv_sec) * 1e3 + (double)(now.tv_nsec - start->tv_nsec) / 1e6;
}

/* Runs `frames` frames, each button pressed `shift` frames early; the state
 * at the end in `state`, and the milliseconds per frame. */
static int run_script(raven_frontend *host, const unsigned char *game, size_t size, const char *name,
                      unsigned preempt, unsigned shift, unsigned char **state, size_t *state_size,
                      double *per_frame) {
    CHECK(raven_frontend_set_preemptive_frames(host, preempt));
    CHECK(raven_frontend_load_rom(host, game, size, name));
    enum { FRAMES = 240 };
    unsigned replays = 0;
    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    for (unsigned frame = 0; frame < FRAMES; ++frame) {
        held = buttons_at(frame + shift < FRAMES ? frame + shift : FRAMES - 1);
        const unsigned before = frames_seen;
        CHECK(raven_frontend_run_frame(host));
        CHECK(frames_seen == before + 1);  /* one picture, replays unseen */
        replays += raven_frontend_replayed_frames(host);
    }
    *per_frame = elapsed_ms(&start) / FRAMES;
    CHECK(preempt == 0 || replays > 0);
    *state_size = raven_frontend_state_size(host);
    CHECK(*state_size > 0 && (*state = malloc(*state_size)) != NULL);
    CHECK(raven_frontend_save_state(host, *state, *state_size));
    raven_frontend_unload(host);
    return 0;
}

/* Preemptive frames on RavenCore: the replayed timeline ends in exactly the
 * state of a game played with each button pressed two frames earlier, so
 * its states load back whole and repeat. Prints what it costs here. */
static int check_preemption(raven_frontend *host, const unsigned char *game, size_t size,
                            const char *name) {
    raven_frontend_callbacks callbacks = {.video = video, .input = input};
    raven_frontend_set_callbacks(host, &callbacks);
    unsigned char *preempted = NULL, *expected = NULL;
    size_t preempted_size = 0, expected_size = 0;
    double with = 0.0, without = 0.0;
    if (run_script(host, game, size, name, 2, 0, &preempted, &preempted_size, &with) ||
        run_script(host, game, size, name, 0, 2, &expected, &expected_size, &without)) {
        free(preempted); free(expected);
        return 1;
    }
    const int same = preempted_size == expected_size && memcmp(preempted, expected, expected_size) == 0;
    printf("  preemptive frames: state %zu bytes, %.3f ms per frame with 2, %.3f without\n",
           expected_size, with, without);
    free(preempted); free(expected);
    held = 0;
    CHECK(raven_frontend_set_preemptive_frames(host, 0));
    CHECK(same);
    return 0;
}

/* Cheats on RavenCore, through retro_cheat_set: a code it takes writes the
 * game's memory, so the state after a few frames differs from a game played
 * without it; a code it cannot read is refused, with its reason. */
static int check_cheats(raven_frontend *host, const unsigned char *game, size_t size, const char *name,
                        const char *code) {
    char reason[RAVEN_FRONTEND_MESSAGE_MAX];
    const char *const taken[] = {code};
    const char *const unreadable[] = {"ZZZZ"};
    unsigned char *states[2] = {NULL, NULL};
    size_t sizes[2] = {0, 0};
    CHECK(raven_frontend_set_cheats(host, taken, 1, reason, sizeof(reason)) == -2);  /* no game */
    for (int with = 0; with < 2; ++with) {
        CHECK(raven_frontend_load_rom(host, game, size, name));
        while (raven_frontend_take_message(host)) {}
        if (with) {
            CHECK(raven_frontend_set_cheats(host, unreadable, 1, reason, sizeof(reason)) == 0);
            CHECK(strstr(reason, "refusé") != NULL);
            CHECK(raven_frontend_take_message(host) == NULL);
            CHECK(raven_frontend_set_cheats(host, taken, 1, reason, sizeof(reason)) == -1);
        }
        for (int frame = 0; frame < 3; ++frame) CHECK(raven_frontend_run_frame(host));
        sizes[with] = raven_frontend_state_size(host);
        states[with] = malloc(sizes[with]);
        CHECK(states[with] && raven_frontend_save_state(host, states[with], sizes[with]));
        raven_frontend_unload(host);
    }
    const int changed = sizes[0] == sizes[1] && memcmp(states[0], states[1], sizes[0]) != 0;
    free(states[0]); free(states[1]);
    CHECK(changed);
    printf("  cheats: %s taken and applied, an unreadable one refused\n", code);
    return 0;
}

/* Two games joined by RavenCore's "link" subsystem: player 1 without
 * battery, player 2 with an 8 KiB battery SRAM (MBC1+RAM+BATTERY). The
 * picture holds both, side by side; each player's SRAM is its own. */
static unsigned frame_width;
static void link_video(const raven_video_frame *frame, void *opaque) {
    (void)opaque;
    if (frame->pixels) frame_width = frame->width;
}

static int check_link(raven_frontend *host, const unsigned char *first, size_t first_size,
                      const unsigned char *second, size_t second_size, unsigned width,
                      size_t second_battery) {
    const raven_frontend_callbacks callbacks = {link_video, NULL, input, NULL, NULL, NULL, NULL};
    raven_frontend_set_callbacks(host, &callbacks);
    const void *const roms[2] = {first, second};
    const size_t sizes[2] = {first_size, second_size};
    const char *const paths[2] = {"joueur1", "joueur2"};
    CHECK(raven_frontend_load_linked(host, roms, sizes, paths));
    CHECK(raven_frontend_players(host) == 2);
    raven_core_info info;
    CHECK(raven_frontend_get_info(host, &info) && info.width == 2 * width);
    frame_width = 0;
    for (int i = 0; i < 3; ++i) CHECK(raven_frontend_run_frame(host));
    CHECK(frame_width == 2 * width);
    CHECK(raven_frontend_player_battery_size(host, 0) == 0);
    CHECK(raven_frontend_player_battery_size(host, 1) == second_battery);
    CHECK(raven_frontend_player_battery_size(host, 2) == 0);
    if (second_battery) {
        unsigned char *written = malloc(second_battery), *read = malloc(second_battery);
        CHECK(written && read);
        for (size_t i = 0; i < second_battery; ++i) written[i] = (unsigned char)(i * 7u + 3u);
        CHECK(raven_frontend_restore_player_battery(host, 1, written, second_battery));
        CHECK(raven_frontend_read_player_battery(host, 1, read, second_battery));
        const int same = memcmp(written, read, second_battery) == 0;
        CHECK(!raven_frontend_restore_player_battery(host, 1, written, second_battery - 1));
        free(written);
        free(read);
        CHECK(same);
    }
    // Preemptive frames stay off while two games run.
    CHECK(raven_frontend_set_preemptive_frames(host, 2));
    CHECK(raven_frontend_run_frame(host) && raven_frontend_run_frame(host));
    CHECK(raven_frontend_replayed_frames(host) == 0);
    CHECK(raven_frontend_set_preemptive_frames(host, 0));
    // A state holds both games.
    const size_t state_size = raven_frontend_state_size(host);
    unsigned char *state = malloc(state_size);
    CHECK(state && state_size);
    const int saved = raven_frontend_save_state(host, state, state_size) &&
                      raven_frontend_load_state(host, state, state_size);
    free(state);
    CHECK(saved);
    // A game alone afterwards is one player again.
    CHECK(raven_frontend_load_rom(host, first, first_size, "seul"));
    CHECK(raven_frontend_players(host) == 1);
    raven_frontend_unload(host);
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 4) {
        fprintf(stderr, "Expected paths to GB, GBC and GBA Libretro cores\n");
        return 1;
    }
    static const char *const systems[] = {"gb", "gbc", "gba"};
    raven_frontend *host = raven_frontend_create();
    if (!host) return 2;
    for (int i = 1; i < argc; ++i) {
        if (!raven_frontend_open_core(host, argv[i], ".", ".")) {
            fprintf(stderr, "%s: %s\n", argv[i], raven_frontend_last_error(host));
            raven_frontend_destroy(host);
            return 3;
        }
        raven_core_info info;
        if (!raven_frontend_get_info(host, &info) || !info.name ||
            !info.version || !info.extensions || !*info.name ||
            !*info.extensions) {
            fprintf(stderr, "%s: missing Libretro metadata\n", argv[i]);
            raven_frontend_destroy(host);
            return 4;
        }
        if (check_options(host, systems[i - 1]) ||
            (i == 1 && check_gb_palette(host)) || (i == 2 && check_gb_in_color(host))) {
            fprintf(stderr, "%s: core options\n", argv[i]);
            raven_frontend_destroy(host);
            return 5;
        }
        make_rom(rom, sizeof(rom));
        memset(gba_rom, 0, sizeof(gba_rom));
        const unsigned char loop[] = {0xfe, 0xff, 0xff, 0xea};
        memcpy(gba_rom, loop, sizeof(loop));
        gba_rom[0xb2] = 0x96;  /* the header's fixed byte */
        if (i == 3 ? check_preemption(host, gba_rom, sizeof(gba_rom), "raventest.gba")
                   : check_preemption(host, rom, sizeof(rom), "raventest.gb")) {
            fprintf(stderr, "%s: preemptive frames\n", argv[i]);
            raven_frontend_destroy(host);
            return 6;
        }
        if (i == 3 ? check_cheats(host, gba_rom, sizeof(gba_rom), "raventest.gba", "32000000 0042")
                   : check_cheats(host, rom, sizeof(rom), "raventest.gb", "014200C0")) {
            fprintf(stderr, "%s: cheats\n", argv[i]);
            raven_frontend_destroy(host);
            return 7;
        }
        if (i == 3) {
            static unsigned char gba_second[1024];
            memcpy(gba_second, gba_rom, sizeof(gba_second));
            if (check_link(host, gba_rom, sizeof(gba_rom), gba_second, sizeof(gba_second), 240, 0)) {
                fprintf(stderr, "%s: linked games\n", argv[i]);
                raven_frontend_destroy(host);
                return 8;
            }
        } else {
            static unsigned char battery_rom[32 * 1024];
            make_rom(battery_rom, sizeof(battery_rom));
            battery_rom[0x147] = 0x03;  /* MBC1+RAM+BATTERY */
            battery_rom[0x149] = 0x02;  /* 8 KiB */
            unsigned char checksum = 0;
            for (size_t b = 0x134; b <= 0x14c; ++b) checksum = (unsigned char)(checksum - battery_rom[b] - 1);
            battery_rom[0x14d] = checksum;
            if (check_link(host, rom, sizeof(rom), battery_rom, sizeof(battery_rom), 160, 8192)) {
                fprintf(stderr, "%s: linked games\n", argv[i]);
                raven_frontend_destroy(host);
                return 8;
            }
        }
        printf("%s: %s (%s), %zu options\n", argv[i], info.name, info.version,
               raven_frontend_option_count(host));
        raven_frontend_close_core(host);
    }
    raven_frontend_destroy(host);
    return 0;
}
