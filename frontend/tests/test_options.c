#include <ravenemu/frontend.h>

#include <stdint.h>
#include <stdio.h>
#include <string.h>

/* Core options through the three declarations a core may use (options v2,
 * v1 and legacy variables), read by the core at load and while it runs. */
static uint32_t second_pixel;

static void video(const raven_video_frame *frame, void *opaque) {
    (void)opaque;
    if (frame->pixels && frame->width >= 2) second_pixel = ((const uint32_t *)frame->pixels)[1];
}

#define CHECK(test) do { if (!(test)) { \
    fprintf(stderr, "Test failed at line %d: %s\n", __LINE__, #test); \
    return 1; \
} } while (0)

static int check_core(const char *path, int categorized, int labelled) {
    raven_frontend *host = raven_frontend_create();
    CHECK(host != NULL);
    CHECK(raven_frontend_option_count(host) == 0);
    CHECK(raven_frontend_open_core(host, path, ".", "."));
    raven_frontend_callbacks callbacks = {.video = video};
    raven_frontend_set_callbacks(host, &callbacks);

    // The declaration, as the core gave it.
    CHECK(raven_frontend_option_count(host) == 2);
    raven_core_option option;
    CHECK(raven_frontend_get_option(host, 0, &option));
    CHECK(strcmp(option.key, "raven_test_color") == 0);
    CHECK(strcmp(option.label, "Couleur") == 0);
    CHECK(option.value_count == 2);
    CHECK(strcmp(option.values[0], "green") == 0 && strcmp(option.values[1], "red") == 0);
    CHECK(strcmp(option.labels[1], labelled ? "Rouge" : "red") == 0);
    CHECK(strcmp(option.category, categorized ? "video" : "") == 0);
    CHECK(strcmp(option.value, "green") == 0);
    CHECK(raven_frontend_get_option(host, 1, &option) && option.value_count == 3);
    CHECK(!raven_frontend_get_option(host, 2, &option));

    // Unknown keys and values are refused; the value stays.
    CHECK(!raven_frontend_set_option(host, "raven_test_missing", "red"));
    CHECK(!raven_frontend_set_option(host, "raven_test_color", "blue"));
    CHECK(!raven_frontend_set_option(host, "raven_test_color", NULL));
    CHECK(raven_frontend_option_value(host, "raven_test_missing") == NULL);
    CHECK(strcmp(raven_frontend_option_value(host, "raven_test_color"), "green") == 0);

    // A value chosen before the game loads is the one it starts with.
    CHECK(raven_frontend_set_option(host, "raven_test_color", "red"));
    const unsigned char rom[] = {'R', 'V', 'N', '1'};
    CHECK(raven_frontend_load_rom(host, rom, sizeof(rom), "demo.rvn"));
    CHECK(raven_frontend_run_frame(host));
    CHECK(second_pixel == 0x00ff0000u);

    // Changed while it runs: the core sees it at its next frame.
    CHECK(raven_frontend_set_option(host, "raven_test_color", "green"));
    CHECK(raven_frontend_run_frame(host));
    CHECK(second_pixel == 0x0000ff00u);
    CHECK(raven_frontend_set_option(host, "raven_test_color", "green"));
    CHECK(raven_frontend_run_frame(host) && second_pixel == 0x0000ff00u);

    // Reloading a game keeps the chosen values.
    CHECK(raven_frontend_set_option(host, "raven_test_speed", "4"));
    CHECK(raven_frontend_load_rom(host, rom, sizeof(rom), "demo.rvn"));
    CHECK(strcmp(raven_frontend_option_value(host, "raven_test_speed"), "4") == 0);

    // Closing the core forgets its options.
    raven_frontend_close_core(host);
    CHECK(raven_frontend_option_count(host) == 0);
    CHECK(raven_frontend_option_value(host, "raven_test_color") == NULL);
    CHECK(!raven_frontend_set_option(host, "raven_test_color", "red"));
    raven_frontend_destroy(host);
    return 0;
}

int main(int argc, char **argv) {
    CHECK(argc == 4);
    CHECK(raven_frontend_option_count(NULL) == 0);
    CHECK(!raven_frontend_set_option(NULL, "raven_test_color", "red"));
    if (check_core(argv[1], 1, 1)) return 1;  /* options v2 */
    if (check_core(argv[2], 0, 1)) return 1;  /* options v1 */
    if (check_core(argv[3], 0, 0)) return 1;  /* legacy variables */
    puts("RavenEmu Libretro core options test passed");
    return 0;
}
