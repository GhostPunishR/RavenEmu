/* RavenEmu core-to-device PCM16 conversion. This existing linear resampler
 * remains covered by the project's GNU AGPL v3 LICENSE. The separate
 * aaudio_driver.c output adapter retains its upstream GPL v3+ license. */
#include "audio_pipeline.h"
#include "aaudio_driver.h"

#include <math.h>

static double input_rate, output_rate, phase;
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

void raven_audio_write(const int16_t *stereo, size_t frames) {
    if (!raven_aaudio_running() || !stereo || input_rate <= 0.0 || output_rate <= 0.0) return;
    const double step = input_rate / output_rate;
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

int raven_audio_open(double requested_rate) {
    raven_audio_close();
    if (!(requested_rate >= 8000.0 && requested_rate <= 192000.0)) return 0;
    int rate = 0;
    if (!raven_aaudio_open(&rate)) return 0;
    input_rate = requested_rate;
    output_rate = rate;
    return 1;
}

void raven_audio_set_running(int running) {
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
}
