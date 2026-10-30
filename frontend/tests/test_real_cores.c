#include <ravenemu/frontend.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

/* Validate the real RavenCore Libretro adapters can be loaded by the new C
 * frontend on Linux, with the options the application sets. No game or BIOS
 * is needed: the Game Boy ROM below is built here, an empty screen. */

#define CHECK(test) do { if (!(test)) { \
    fprintf(stderr, "Test failed at line %d: %s\n", __LINE__, #test); \
    return 1; \
} } while (0)

static uint32_t first_pixel;
static unsigned frames_seen;

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
    raven_frontend_callbacks callbacks = {video, NULL, NULL, NULL};
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
    raven_frontend_callbacks callbacks = {video, NULL, NULL, NULL};
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
        printf("%s: %s (%s), %zu options\n", argv[i], info.name, info.version,
               raven_frontend_option_count(host));
        raven_frontend_close_core(host);
    }
    raven_frontend_destroy(host);
    return 0;
}
