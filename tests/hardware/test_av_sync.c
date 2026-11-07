/* Picture and sound together, end to end, in virtual time (RavenEmu code,
 * AGPLv3). The real RavenCore cores run through the C frontend, paced by
 * raven_frame_pacer as the Android bridge's drawFrame paces them; their
 * sound goes through the audio pipeline and the AAudio driver to a fake
 * device that pulls bursts on its own clock, as a phone's does. Nothing
 * sleeps: each display refresh and each device burst has an exact time, so
 * a run is the same on every machine.
 *
 * Measured once the pacer has locked and the queue has settled:
 * - the picture: frames run per refresh (none repeated, none skipped when
 *   the display divides the console's rate), and one picture per frame;
 * - the sound: samples per frame, the device never starved (a crackle) and
 *   the queue never full (samples lost), the tone's pitch, no silent gap;
 * - their sync: the delay between a frame shown and its sound heard (the
 *   queue in front of the device), which must stay put, not drift.
 *
 * No game is needed: the GB and GBA ROMs below are built here and play an
 * A (about 440 Hz) on the square channel 2. Extra arguments run a ROM of the
 * player's own (never part of the repository) with the same measures. */
#define _POSIX_C_SOURCE 200809L
#include "audio_pipeline.h"
#include "aaudio_driver.h"
#include <aaudio/AAudio.h>
#include <ravenemu/frame_pacer.h>
#include <ravenemu/frontend.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* ---- A fake AAudio device: one stream, pulled by the test, not a thread. ---- */
struct AAudioStreamStruct { int unused; };
struct AAudioStreamBuilderStruct { int unused; };
static struct AAudioStreamStruct fake_stream;
static struct AAudioStreamBuilderStruct fake_builder;
static AAudioStream_dataCallback data_callback;
static void *data_user;
static aaudio_stream_state_t state;
#define DEVICE_RATE 48000
#define DEVICE_BURST 192
static int32_t device_buffer = 4096;

aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **b) { *b = &fake_builder; return AAUDIO_OK; }
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *b, int32_t x) { (void)b; (void)x; }
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *b, int32_t x) { (void)b; (void)x; }
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *b, int32_t x) { (void)b; (void)x; }
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *b, int32_t x) { (void)b; (void)x; }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *b, int32_t x) { (void)b; (void)x; }
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *b, int32_t x) { (void)b; (void)x; }
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, AAudioStream_dataCallback cb, void *u) {
    (void)b; data_callback = cb; data_user = u;
}
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, AAudioStream_errorCallback cb, void *u) {
    (void)b; (void)cb; (void)u;
}
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **s) {
    (void)b; *s = &fake_stream; state = AAUDIO_STREAM_STATE_PAUSED; return AAUDIO_OK;
}
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b) { (void)b; return AAUDIO_OK; }
int32_t AAudioStream_getSampleRate(AAudioStream *s) { (void)s; return DEVICE_RATE; }
int32_t AAudioStream_getFormat(AAudioStream *s) { (void)s; return AAUDIO_FORMAT_PCM_I16; }
int32_t AAudioStream_getChannelCount(AAudioStream *s) { (void)s; return 2; }
int32_t AAudioStream_getFramesPerBurst(AAudioStream *s) { (void)s; return DEVICE_BURST; }
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *s) { (void)s; return 4096; }
int32_t AAudioStream_getBufferSizeInFrames(AAudioStream *s) { (void)s; return device_buffer; }
aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream *s, int32_t n) { (void)s; device_buffer = n; return n; }
aaudio_result_t AAudioStream_requestStart(AAudioStream *s) { (void)s; state = AAUDIO_STREAM_STATE_STARTED; return AAUDIO_OK; }
aaudio_result_t AAudioStream_requestPause(AAudioStream *s) { (void)s; state = AAUDIO_STREAM_STATE_PAUSED; return AAUDIO_OK; }
aaudio_result_t AAudioStream_requestStop(AAudioStream *s) { (void)s; state = AAUDIO_STREAM_STATE_STOPPING; return AAUDIO_OK; }
aaudio_stream_state_t AAudioStream_getState(AAudioStream *s) { (void)s; return state; }
aaudio_result_t AAudioStream_waitForStateChange(AAudioStream *s, aaudio_stream_state_t before,
                                                aaudio_stream_state_t *after, int64_t timeout) {
    (void)s; (void)before; (void)timeout;
    state = AAUDIO_STREAM_STATE_STOPPED;
    *after = state;
    return AAUDIO_OK;
}
aaudio_result_t AAudioStream_close(AAudioStream *s) { (void)s; state = AAUDIO_STREAM_STATE_CLOSED; return AAUDIO_OK; }
int __android_log_print(int p, const char *tag, const char *message, ...) { (void)p; (void)tag; (void)message; return 0; }

/* ---- The ROMs: an A on the square channel 2, then a loop. ---- */

/* Channel 2 at x = 1750: 131072 / (2048 - x) Hz, on both consoles. */
#define TONE_X 1750
#define TONE_HZ (131072.0 / (2048 - TONE_X))

static unsigned char gb_rom[32 * 1024];
static unsigned char gba_rom[1024];

static void make_gb_rom(void) {
    memset(gb_rom, 0, sizeof(gb_rom));
    const unsigned char entry[] = {0x00, 0xc3, 0x50, 0x01};  /* nop; jp 0x0150 */
    memcpy(gb_rom + 0x100, entry, sizeof(entry));
    memcpy(gb_rom + 0x134, "RAVENTONE", 9);
    const unsigned char code[] = {
        0x3e, 0x80, 0xe0, 0x26,  /* NR52: sound on */
        0x3e, 0x77, 0xe0, 0x24,  /* NR50: both sides, full volume */
        0x3e, 0xff, 0xe0, 0x25,  /* NR51: every channel to both sides */
        0x3e, 0x80, 0xe0, 0x16,  /* NR21: duty 50 % */
        0x3e, 0xf0, 0xe0, 0x17,  /* NR22: volume 15, no envelope */
        0x3e, TONE_X & 0xff, 0xe0, 0x18,          /* NR23: frequency, low */
        0x3e, 0x80 | (TONE_X >> 8), 0xe0, 0x19,   /* NR24: trigger, frequency, high */
        0x18, 0xfe,              /* jr -2 */
    };
    memcpy(gb_rom + 0x150, code, sizeof(code));
    unsigned char checksum = 0;
    for (size_t i = 0x134; i <= 0x14c; ++i) checksum = (unsigned char)(checksum - gb_rom[i] - 1);
    gb_rom[0x14d] = checksum;
}

static void put32(unsigned char *at, uint32_t word) {
    at[0] = (unsigned char)word; at[1] = (unsigned char)(word >> 8);
    at[2] = (unsigned char)(word >> 16); at[3] = (unsigned char)(word >> 24);
}

/* ARM, from the cartridge's entry: the GBA's own PSG channel 2 (GBATEK). */
static void make_gba_rom(void) {
    memset(gba_rom, 0, sizeof(gba_rom));
    put32(gba_rom, 0xea00002eu);  /* b 0xc0, over the header */
    gba_rom[0xb2] = 0x96;         /* the header's fixed byte */
    static const uint32_t code[] = {
        0xe3a00301u,  /* mov r0, #0x04000000 */
        0xe3a01080u,  /* mov r1, #0x80 */
        0xe1c018b4u,  /* strh r1, [r0, #0x84]   SOUNDCNT_X: sound on */
        0xe3a01cffu,  /* mov r1, #0xff00 */
        0xe3811077u,  /* orr r1, r1, #0x77 */
        0xe1c018b0u,  /* strh r1, [r0, #0x80]   SOUNDCNT_L: PSG to both sides */
        0xe3a01002u,  /* mov r1, #2 */
        0xe1c018b2u,  /* strh r1, [r0, #0x82]   SOUNDCNT_H: PSG at 100 % */
        0xe3a01cf0u,  /* mov r1, #0xf000 */
        0xe3811080u,  /* orr r1, r1, #0x80 */
        0xe1c016b8u,  /* strh r1, [r0, #0x68]   SOUND2CNT_L: volume 15, duty 50 % */
        0xe3a01c80u | (TONE_X >> 8),        /* mov r1, #0x8000 | high bits */
        0xe3811000u | (TONE_X & 0xff),      /* orr r1, r1, #low bits */
        0xe1c016bcu,  /* strh r1, [r0, #0x6c]   SOUND2CNT_H: trigger, frequency */
        0xeafffffeu,  /* b . */
    };
    for (size_t i = 0; i < sizeof(code) / sizeof(code[0]); ++i) put32(gba_rom + 0xc0 + 4 * i, code[i]);
}

/* ---- One run ---- */

typedef struct scenario {
    const char *name;
    unsigned core;          /* 0 GB, 1 GBC, 2 GBA */
    const void *rom;
    size_t rom_size;
    const char *file;
    double display_hz;
    double device_ppm;      /* The device's clock against the display's. */
    double jitter_ms;       /* Each refresh reported up to this early or late. */
    double seconds;
    int resampler;
    unsigned cadence;       /* Refreshes per frame expected once locked; 0: unlocked. */
    int tone;               /* The built-in ROM: its pitch and its gaps are checked. */
    double pause_at;        /* A pause of one second there (menu), 0 for none. */
} scenario;

typedef struct results {
    unsigned long frames, pictures, refreshes, repeats, doubles;
    unsigned long long core_samples;
    unsigned long underruns, full;
    double latency_min, latency_max, latency_first, latency_last;
    double pitch_hz, speed;
    unsigned long gap_frames;
    int locked;
} results;

static unsigned long pictures;
static unsigned long long core_samples;
static void on_video(const raven_video_frame *frame, void *user) { (void)frame; (void)user; ++pictures; }
static void on_audio(const int16_t *stereo, size_t frames, void *user) {
    (void)user;
    core_samples += frames;
    raven_audio_write(stereo, frames);
}

/* A tiny deterministic jitter, as a Choreographer timestamp wanders. */
static uint32_t lcg = 12345u;
static double jitter(double ms) {
    lcg = lcg * 1664525u + 1013904223u;
    return ms * (((double)(lcg >> 8) / 16777216.0) * 2.0 - 1.0);
}

typedef struct window { double sum; unsigned long count; } window;
static double mean(const window *w) { return w->count ? w->sum / (double)w->count : 0.0; }

static double wall_seconds(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double)t.tv_sec + (double)t.tv_nsec / 1e9;
}

static int run(raven_frontend *host, const char *core_path, const scenario *s, results *r) {
    const double started = wall_seconds();
    memset(r, 0, sizeof(*r));
    r->latency_min = 1e9;
    pictures = 0;
    core_samples = 0;
    lcg = 12345u;
    if (!raven_frontend_open_core(host, core_path, ".", ".")) {
        fprintf(stderr, "%s: %s\n", core_path, raven_frontend_last_error(host));
        return 0;
    }
    raven_frontend_callbacks callbacks = {.video = on_video, .audio = on_audio};
    raven_frontend_set_callbacks(host, &callbacks);
    if (!raven_frontend_load_rom(host, s->rom, s->rom_size, s->file)) {
        fprintf(stderr, "%s: %s\n", s->name, raven_frontend_last_error(host));
        raven_frontend_close_core(host);
        return 0;
    }
    raven_core_info info;
    if (!raven_frontend_get_info(host, &info) || !(info.fps > 0.0) || !(info.sample_rate > 0.0)) {
        fprintf(stderr, "%s: no timing from the core\n", s->name);
        raven_frontend_close_core(host);
        return 0;
    }
    /* As the application sets the sound: its defaults, the player's resampler. */
    raven_audio_set_output(1, 100);
    raven_audio_set_rate_control(RAVEN_AUDIO_RATE_CONTROL);
    raven_audio_set_resampler(s->resampler);
    raven_audio_set_startup_fade(RAVEN_AUDIO_STARTUP_FADE_MS);
    if (!raven_audio_open(info.sample_rate)) {
        fprintf(stderr, "%s: audio open\n", s->name);
        raven_frontend_close_core(host);
        return 0;
    }
    raven_audio_set_running(1);
    raven_frame_pacer pacer;
    raven_frame_pacer_configure(&pacer, info.fps);
    raven_frame_pacer_set_display(&pacer, s->display_hz);

    /* Counted from `settle` on: the pacer has locked and the queue is no
     * longer near empty. The rate control then brings the queue to its level
     * in about 20 s (it moves it by at most 0.5 %, a time constant near 6 s):
     * the delay is compared between the two last `span`s, once it has. */
    const double settle = 6.0, span = 5.0;
    const double refresh_s = 1.0 / s->display_hz;
    const double device_rate = DEVICE_RATE * (1.0 + s->device_ppm * 1e-6);
    const unsigned long refreshes = (unsigned long)(s->seconds * s->display_hz);
    double device_clock = 0.0;     /* Frames the device has pulled. */
    unsigned long frames_at_settle = 0;
    double settle_time = 0.0, resume_at = -1.0;
    int paused = 0;
    window first = {0}, last = {0};
    /* The tone, heard after settling: crossings with hysteresis, silent runs. */
    double level_mean = 0.0;
    unsigned long level_count = 0, crossings = 0, heard = 0, silent_run = 0;
    int high = 0, have_side = 0, previous = 0;
    int16_t burst[DEVICE_BURST * 2];
    const int trace = getenv("RAVEN_AV_TRACE") != NULL;
    double trace_sum = 0.0;
    unsigned long trace_count = 0, trace_second = 0;

    for (unsigned long k = 1; k <= refreshes; ++k) {
        const double now = (double)k * refresh_s;
        const double reported = now + jitter(s->jitter_ms) / 1000.0;
        const int counting = now >= settle && (resume_at < 0.0 || now >= resume_at + 1.0);
        /* The device pulls whole bursts until the display's refresh. */
        const double due = now * device_rate;
        while (device_clock + DEVICE_BURST <= due) {
            uint32_t queued = 0, limit = 0;
            raven_aaudio_fill(&queued, &limit);
            if (counting && !paused && queued < DEVICE_BURST) ++r->underruns;
            data_callback(&fake_stream, data_user, burst, DEVICE_BURST);
            device_clock += DEVICE_BURST;
            if (!counting || paused || !s->tone) continue;
            for (int i = 0; i < DEVICE_BURST; ++i) {
                const int v = burst[2 * i];
                /* The level's middle, from the first settled second. */
                if (level_count < (unsigned long)device_rate) {
                    level_mean += v;
                    ++level_count;
                    continue;
                }
                const double middle = level_mean / (double)level_count;
                ++heard;
                /* A run of one value longer than 10 ms: a gap, not a tone. */
                if (v == previous) {
                    if (++silent_run > (unsigned long)(device_rate / 100.0)) ++r->gap_frames;
                } else {
                    silent_run = 0;
                }
                previous = v;
                if (!have_side) { high = v > middle; have_side = 1; continue; }
                if (!high && v > middle + 1000.0) { high = 1; ++crossings; }
                else if (high && v < middle - 1000.0) high = 0;
            }
        }

        if (s->pause_at > 0.0 && !paused && resume_at < 0.0 && now >= s->pause_at) {
            /* The game's menu: as the bridge's setPaused, then its draws. */
            paused = 1;
            raven_audio_set_running(0);
        }
        if (paused) {
            raven_frame_pacer_reset(&pacer);
            if (now >= s->pause_at + 1.0) {
                paused = 0;
                resume_at = now;
                raven_audio_set_running(1);
            }
            continue;
        }

        /* The bridge's drawFrame: the pacer, the speed, the frames due. */
        const unsigned frames_due = raven_frame_pacer_tick(&pacer, (uint64_t)(reported * 1e9));
        raven_audio_set_speed(raven_frame_pacer_speed(&pacer));
        for (unsigned i = 0; i < frames_due; ++i) {
            if (!raven_frontend_run_frame(host)) {
                fprintf(stderr, "%s: frame failed\n", s->name);
                raven_audio_close();
                raven_frontend_close_core(host);
                return 0;
            }
            ++r->frames;
        }
        uint32_t queued = 0, limit = 0;
        raven_aaudio_fill(&queued, &limit);
        if (trace) {
            /* RAVEN_AV_TRACE: the delay, second by second, to see it settle. */
            trace_sum += 1000.0 * ((double)queued + device_buffer) / device_rate;
            ++trace_count;
            if ((unsigned long)now != trace_second) {
                fprintf(stderr, "  %s %3lus %.2f ms\n", s->name, trace_second, trace_sum / (double)trace_count);
                trace_second = (unsigned long)now;
                trace_sum = 0.0;
                trace_count = 0;
            }
        }
        if (now < settle) continue;
        if (!frames_at_settle) { frames_at_settle = r->frames; settle_time = now; }

        if (limit && queued >= limit) ++r->full;
        if (!counting) continue;
        ++r->refreshes;
        if (frames_due == 0) ++r->repeats;
        if (frames_due > 1) ++r->doubles;
        /* What is queued is heard after what the device holds: the delay
         * from this refresh's picture to its sound. */
        const double latency = 1000.0 * ((double)queued + device_buffer) / device_rate;
        if (latency < r->latency_min) r->latency_min = latency;
        if (latency > r->latency_max) r->latency_max = latency;
        if (now >= s->seconds - 2.0 * span && now < s->seconds - span) { first.sum += latency; ++first.count; }
        if (now >= s->seconds - span) { last.sum += latency; ++last.count; }
    }
    r->pictures = pictures;
    r->core_samples = core_samples;
    r->latency_first = mean(&first);
    r->latency_last = mean(&last);
    r->locked = raven_frame_pacer_locked(&pacer);
    r->speed = raven_frame_pacer_speed(&pacer);
    if (heard) r->pitch_hz = (double)crossings * device_rate / (double)heard;
    /* Frames against the time: the console's rate times the speed. */
    const double elapsed = (double)refreshes * refresh_s - settle_time - (s->pause_at > 0.0 ? 1.0 : 0.0);
    const double expected = elapsed * info.fps * r->speed;
    const double ran = (double)(r->frames - frames_at_settle);

    printf("%-34s %6.2f Hz  %lu frames  speed %.4f  repeats %lu  doubles %lu  "
           "latency %.1f-%.1f ms (%.2f -> %.2f)  underruns %lu  full %lu",
           s->name, s->display_hz, r->frames, r->speed, r->repeats, r->doubles,
           r->latency_min, r->latency_max, r->latency_first, r->latency_last, r->underruns, r->full);
    if (s->tone) printf("  pitch %.2f Hz (%.2f)  gaps %lu", r->pitch_hz, TONE_HZ * r->speed, r->gap_frames);
    printf("  [%.1f s, core %.0f Hz]\n", wall_seconds() - started, info.sample_rate);

    int ok = 1;
#define EXPECT(c) do { if (!(c)) { fprintf(stderr, "%s: expected %s\n", s->name, #c); ok = 0; } } while (0)
    EXPECT(r->pictures == r->frames);  /* every frame shows its picture */
    /* The core's sound matches its own clock: samples per frame. */
    EXPECT(fabs((double)r->core_samples / (double)r->frames - info.sample_rate / info.fps)
           < 0.002 * info.sample_rate / info.fps);
    EXPECT(fabs(ran - expected) <= 2.0);
    EXPECT(r->underruns == 0);
    EXPECT(r->full == 0);
    /* In sync: once settled, the delay stays put. A drifting one is a picture
     * and a sound slowly parting; 1 ms is a sixteenth of a frame. */
    EXPECT(fabs(r->latency_last - r->latency_first) < 1.0);
    /* Never more than the driver's budget (64 ms) plus a frame. */
    EXPECT(r->latency_max < 64.0 + 1000.0 / info.fps);
    if (s->cadence) {
        EXPECT(r->locked);
        EXPECT(fabs(r->speed - s->display_hz / s->cadence / info.fps) < 1e-3);
        /* Locked: one frame every `cadence` refreshes, never two at once. */
        EXPECT(r->doubles == 0);
        EXPECT(labs((long)r->repeats - (long)(r->refreshes * (s->cadence - 1) / s->cadence)) <= 1);
    } else {
        EXPECT(!r->locked && r->speed == 1.0);
    }
    if (s->tone) {
        /* The A at the game's speed: the rate control moves it 0.5 % at most. */
        EXPECT(fabs(r->pitch_hz / (TONE_HZ * r->speed) - 1.0) < 0.005);
        EXPECT(r->gap_frames == 0);
    }
#undef EXPECT
    raven_audio_close();
    raven_frontend_unload(host);
    raven_frontend_close_core(host);
    return ok;
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "Expected paths to GB, GBC and GBA Libretro cores, then optional ROMs\n");
        return 1;
    }
    make_gb_rom();
    make_gba_rom();
    /* The GB and GBC cores cost about six times the GBA core's idle loop on
     * a host: the variants (resampler, device clock, 90 Hz) use the GBA. */
    const scenario scenarios[] = {
        {"GB, 60 Hz, sinc", 0, gb_rom, sizeof(gb_rom), "tone.gb", 60.0, 0, 0.3, 30, RAVEN_AUDIO_RESAMPLER_SINC, 1, 1, 0},
        {"GB, 59.94 Hz, device +300 ppm", 0, gb_rom, sizeof(gb_rom), "tone.gb", 59.94, 300, 0.3, 30, RAVEN_AUDIO_RESAMPLER_SINC, 1, 1, 0},
        {"GB, 60 Hz, pause at 8 s", 0, gb_rom, sizeof(gb_rom), "tone.gb", 60.0, 0, 0.3, 40, RAVEN_AUDIO_RESAMPLER_SINC, 1, 1, 8},
        {"GBC, 120 Hz", 1, gb_rom, sizeof(gb_rom), "tone.gb", 120.0, 0, 0.3, 30, RAVEN_AUDIO_RESAMPLER_SINC, 2, 1, 0},
        {"GBA, 60 Hz", 2, gba_rom, sizeof(gba_rom), "tone.gba", 60.0, 0, 0.3, 30, RAVEN_AUDIO_RESAMPLER_SINC, 1, 1, 0},
        {"GBA, 60 Hz, linear", 2, gba_rom, sizeof(gba_rom), "tone.gba", 60.0, 0, 0.3, 30, RAVEN_AUDIO_RESAMPLER_LINEAR, 1, 1, 0},
        {"GBA, 60 Hz, device -3000 ppm", 2, gba_rom, sizeof(gba_rom), "tone.gba", 60.0, -3000, 0.3, 30, RAVEN_AUDIO_RESAMPLER_SINC, 1, 1, 0},
        {"GBA, 90 Hz (not a multiple)", 2, gba_rom, sizeof(gba_rom), "tone.gba", 90.0, 0, 0.3, 30, RAVEN_AUDIO_RESAMPLER_SINC, 0, 1, 0},
        {"GBA, 120 Hz, device +300 ppm", 2, gba_rom, sizeof(gba_rom), "tone.gba", 120.0, 300, 0.3, 30, RAVEN_AUDIO_RESAMPLER_SINC, 2, 1, 0},
    };
    /* RAVEN_AV_SECONDS: longer runs, for a local soak (600 for ten minutes). */
    const char *soak = getenv("RAVEN_AV_SECONDS");
    const double seconds = soak ? atof(soak) : 0.0;
    raven_frontend *host = raven_frontend_create();
    if (!host) return 2;
    int ok = 1;
    for (size_t i = 0; i < sizeof(scenarios) / sizeof(scenarios[0]); ++i) {
        scenario chosen = scenarios[i];
        if (seconds >= 40.0) chosen.seconds = seconds;
        results r;
        if (!run(host, argv[1 + chosen.core], &chosen, &r)) ok = 0;
    }
    /* A player's own ROMs, for a local run: the same measures, without the
     * tone. A .gba goes to the GBA core, anything else to the GBC core. */
    for (int a = 4; a < argc; ++a) {
        FILE *file = fopen(argv[a], "rb");
        if (!file) { fprintf(stderr, "%s: unreadable\n", argv[a]); ok = 0; continue; }
        static unsigned char data[32u << 20];
        const size_t size = fread(data, 1, sizeof(data), file);
        fclose(file);
        const size_t length = strlen(argv[a]);
        const int gba = length > 4 && strcmp(argv[a] + length - 4, ".gba") == 0;
        const char *name = strrchr(argv[a], '/');
        name = name ? name + 1 : argv[a];
        scenario own = {name, gba ? 2u : 1u, data, size, gba ? "own.gba" : "own.gbc",
                        60.0, 0, 0.3, seconds >= 40.0 ? seconds : 60, RAVEN_AUDIO_RESAMPLER_SINC, 1, 0, 30};
        results r;
        if (!run(host, argv[1 + own.core], &own, &r)) ok = 0;
    }
    raven_frontend_destroy(host);
    if (!ok) return 1;
    puts("Picture and sound in sync: every scenario passed");
    return 0;
}
