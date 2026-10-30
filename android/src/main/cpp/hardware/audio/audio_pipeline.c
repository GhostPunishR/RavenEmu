/* RavenEmu core-to-device PCM16 conversion. This existing linear resampler
 * remains covered by the project's GNU AGPL v3 LICENSE. The separate
 * aaudio_driver.c output adapter retains its upstream GPL v3+ license.
 *
 * Dynamic rate control, the idea RetroArch's author published, written for
 * RavenEmu: the device and the emulation run on two clocks, so the queue
 * would slowly fill (samples dropped) or empty (silence), both heard as
 * crackles. Each write corrects the ratio by at most RAVEN_AUDIO_RATE_CONTROL
 * towards a half-full queue: inaudible, and the queue never drifts. The
 * game's speed against the console (the display lock) is applied first. */
#include "audio_pipeline.h"
#include "aaudio_driver.h"

#include <math.h>

/* Draws between two attempts to reopen a lost device, about a second. */
#define REOPEN_RETRY_DRAWS 60

static double input_rate, output_rate, phase;
static double speed = 1.0, rate_control = RAVEN_AUDIO_RATE_CONTROL;
/* What to reopen after a disconnect, and when to try again. */
static double requested_rate;
static int wanted_running = 1, reopen_pending, reopen_countdown;
static int16_t previous[2];
static int have_previous;
static int output_enabled = 1, output_volume = 100;

static int16_t interpolate(int16_t before, int16_t after, double fraction) {
    double value = (double)before + ((double)after - before) * fraction;
    if (value > 32767.0) value = 32767.0;
    if (value < -32768.0) value = -32768.0;
    return (int16_t)lrint(value);
}

static int16_t apply_gain(int16_t sample, int gain) {
    if (gain == 100) return sample;
    const int scaled = (int)sample * gain;
    // Nearest integer, halves away from zero. Even -32768 * 100 fits int32.
    return (int16_t)((scaled + (scaled >= 0 ? 50 : -50)) / 100);
}

void raven_audio_set_output(int enabled, int volume) {
    enabled = !!enabled;
    if (volume < 0) volume = 0;
    if (volume > 100) volume = 100;
    if (enabled == output_enabled && volume == output_volume) return;
    output_enabled = enabled;
    output_volume = volume;
    raven_aaudio_flush();
}

void raven_audio_set_speed(double value) {
    speed = value >= 0.9 && value <= 1.1 ? value : 1.0;
}

void raven_audio_set_rate_control(double delta) {
    rate_control = delta >= 0.0 && delta <= 0.05 ? delta : RAVEN_AUDIO_RATE_CONTROL;
}

double raven_audio_rate_adjust(uint32_t queued, uint32_t limit, double delta) {
    if (!limit || !(delta > 0.0)) return 1.0;
    const double half = limit / 2.0;
    double direction = (half - (double)queued) / half;
    if (direction > 1.0) direction = 1.0;
    if (direction < -1.0) direction = -1.0;
    /* Emptier than half: more output samples per core sample, and back. */
    return 1.0 + delta * direction;
}

void raven_audio_write(const int16_t *stereo, size_t frames) {
    if (!raven_aaudio_running() || !stereo || input_rate <= 0.0 || output_rate <= 0.0) return;
    uint32_t queued = 0, limit = 0;
    raven_aaudio_fill(&queued, &limit);
    /* The queue as it will be halfway through this batch. */
    const double nominal = input_rate * speed / output_rate;
    const uint32_t half_batch = (uint32_t)((double)frames / nominal / 2.0);
    const double adjust = raven_audio_rate_adjust(queued + half_batch, limit, rate_control);
    const double step = nominal / adjust;
    const int gain = output_enabled ? output_volume : 0;
    for (size_t i = 0; i < frames; ++i) {
        const int16_t left = stereo[2 * i], right = stereo[2 * i + 1];
        if (!have_previous) {
            previous[0] = left; previous[1] = right;
            have_previous = 1;
            continue;
        }
        while (phase < 1.0) {
            raven_aaudio_push(apply_gain(interpolate(previous[0], left, phase), gain),
                             apply_gain(interpolate(previous[1], right, phase), gain));
            phase += step;
        }
        phase -= 1.0;
        previous[0] = left; previous[1] = right;
    }
}

int raven_audio_open(double requested) {
    raven_audio_close();
    if (!(requested >= 8000.0 && requested <= 192000.0)) return 0;
    int rate = 0;
    if (!raven_aaudio_open(&rate)) return 0;
    input_rate = requested;
    output_rate = rate;
    requested_rate = requested;
    return 1;
}

int raven_audio_recover(void) {
    if (!reopen_pending) {
        if (!raven_aaudio_lost()) return 0;
        reopen_pending = 1;
        reopen_countdown = 0;
    }
    if (reopen_countdown > 0) {
        --reopen_countdown;
        return 0;
    }
    const double rate = requested_rate;
    const int run = wanted_running;
    if (rate > 0.0 && raven_audio_open(rate)) {
        raven_audio_set_running(run);
        return 1;
    }
    /* Opening closed everything: keep what to reopen, and wait. */
    requested_rate = rate;
    wanted_running = run;
    reopen_pending = rate > 0.0;
    reopen_countdown = REOPEN_RETRY_DRAWS;
    return 0;
}

void raven_audio_set_running(int running) {
    wanted_running = !!running;
    if (!!running != raven_aaudio_running()) {
        phase = 0.0;
        have_previous = 0;
    }
    raven_aaudio_set_running(running);
}

void raven_audio_close(void) {
    raven_aaudio_close();
    input_rate = output_rate = phase = 0.0;
    have_previous = 0;
    requested_rate = 0.0;
    reopen_pending = reopen_countdown = 0;
}
