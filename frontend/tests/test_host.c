#include <ravenemu/frontend.h>

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
    raven_frontend_callbacks callbacks = {video, audio, input, &obs};
    raven_frontend_set_callbacks(host, &callbacks);
    raven_core_info info;
    CHECK(raven_frontend_get_info(host, &info));
    CHECK(strcmp(info.name, "RavenTestCore") == 0);
    CHECK(strcmp(info.extensions, "rvn") == 0);
    const unsigned char invalid[] = {0, 0, 0, 0};
    const unsigned char valid[] = {'R', 'V', 'N', '1'};
    CHECK(!raven_frontend_load_rom(host, invalid, sizeof(invalid), "wrong.rvn"));
    CHECK(raven_frontend_load_rom(host, valid, sizeof(valid), "demo.rvn"));
    CHECK(raven_frontend_get_info(host, &info));
    CHECK(info.width == 2 && info.height == 2);
    CHECK(info.fps == 60.0 && info.sample_rate == 48000.0);
    CHECK(raven_frontend_run_frame(host));
    CHECK(raven_frontend_run_frame(host));
    CHECK(obs.video_calls == 2);
    CHECK(obs.audio_frames == 4);
    CHECK(!obs.invalid_video);
    raven_frontend_unload(host);
    CHECK(!raven_frontend_run_frame(host));
    raven_frontend_destroy(host);
    puts("RavenEmu Libretro frontend smoke test passed");
    return 0;
}
