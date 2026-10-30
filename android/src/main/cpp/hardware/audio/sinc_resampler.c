/* Windowed-sinc resampler for RavenEmu (AGPLv3): band-limited interpolation
 * as Julius O. Smith describes it, with a Kaiser window. The kernel is
 * normalized at every phase so a constant stays exactly constant. */
#include "sinc_resampler.h"

#include <math.h>
#include <stdlib.h>
#include <string.h>

#define PI 3.14159265358979323846

/* Modified Bessel function of the first kind, order 0, by its series. */
static double bessel_i0(double x) {
    double sum = 1.0, term = 1.0;
    const double quarter = x * x / 4.0;
    for (unsigned k = 1; k < 64 && term > 1e-12 * sum; ++k) {
        term *= quarter / ((double)k * k);
        sum += term;
    }
    return sum;
}

static double kernel(double distance, double cutoff, double half_width) {
    if (fabs(distance) >= half_width) return 0.0;
    const double x = cutoff * distance;
    const double sinc = fabs(x) < 1e-9 ? 1.0 : sin(PI * x) / (PI * x);
    const double ratio = distance / half_width;
    return cutoff * sinc * bessel_i0(RAVEN_SINC_KAISER_BETA * sqrt(1.0 - ratio * ratio)) /
           bessel_i0(RAVEN_SINC_KAISER_BETA);
}

int raven_sinc_configure(raven_sinc *sinc, double input_rate, double output_rate) {
    if (!sinc || !(input_rate >= 1000.0 && input_rate <= 1e6) || !(output_rate >= 1000.0 && output_rate <= 1e6))
        return 0;
    raven_sinc_free(sinc);
    /* Below the lower of the two Nyquist frequencies, in input units. */
    const double cutoff = RAVEN_SINC_CUTOFF * (output_rate < input_rate ? output_rate / input_rate : 1.0);
    unsigned taps = 2u * (unsigned)ceil(RAVEN_SINC_ZEROS / cutoff);
    if (taps > RAVEN_SINC_MAX_TAPS) taps = RAVEN_SINC_MAX_TAPS;
    const double half_width = taps / 2.0;
    float *table = (float *)malloc(sizeof(float) * (RAVEN_SINC_PHASES + 1) * taps);
    if (!table) return 0;
    /* Row p, tap m: the weight of the m-th history frame (oldest first) for
     * an output p / PHASES past the centre frame, taps / 2 - 1. */
    for (unsigned p = 0; p <= RAVEN_SINC_PHASES; ++p) {
        float *row = table + (size_t)p * taps;
        const double fraction = (double)p / RAVEN_SINC_PHASES;
        double sum = 0.0;
        for (unsigned m = 0; m < taps; ++m) {
            const double weight = kernel(fraction + half_width - 1.0 - m, cutoff, half_width);
            row[m] = (float)weight;
            sum += weight;
        }
        for (unsigned m = 0; m < taps; ++m) row[m] = (float)(row[m] / sum);
    }
    sinc->table = table;
    sinc->taps = taps;
    raven_sinc_reset(sinc);
    return 1;
}

void raven_sinc_reset(raven_sinc *sinc) {
    if (!sinc) return;
    sinc->head = 0;
    sinc->primed = 0;
    sinc->phase = 0.0;
    memset(sinc->history, 0, sizeof(sinc->history));
}

void raven_sinc_free(raven_sinc *sinc) {
    if (!sinc) return;
    free(sinc->table);
    sinc->table = NULL;
    sinc->taps = 0;
    raven_sinc_reset(sinc);
}

static int16_t saturate(float value) {
    if (value >= 32767.0f) return 32767;
    if (value <= -32768.0f) return -32768;
    return (int16_t)lrintf(value);
}

/* Each frame goes in twice, `taps` apart, so the newest `taps` frames always
 * sit in one run starting at `head`. */
static void push(raven_sinc *sinc, float left, float right) {
    const unsigned taps = sinc->taps;
    sinc->history[0][sinc->head] = sinc->history[0][sinc->head + taps] = left;
    sinc->history[1][sinc->head] = sinc->history[1][sinc->head + taps] = right;
    sinc->head = (sinc->head + 1) % taps;
}

void raven_sinc_process(raven_sinc *sinc, const int16_t *stereo, size_t frames, double step,
                        raven_sinc_emit emit, void *user) {
    if (!sinc || !sinc->table || !stereo || !emit || !(step > 0.0)) return;
    const unsigned taps = sinc->taps;
    for (size_t i = 0; i < frames; ++i) {
        const float left = stereo[2 * i], right = stereo[2 * i + 1];
        if (!sinc->primed) {
            /* The stream starts as if it had always held its first frame:
             * no click, and no output yet, as linear interpolation. */
            for (unsigned k = 0; k < taps; ++k) push(sinc, left, right);
            sinc->primed = 1;
            continue;
        }
        push(sinc, left, right);
        const float *oldest_left = sinc->history[0] + sinc->head;
        const float *oldest_right = sinc->history[1] + sinc->head;
        while (sinc->phase < 1.0) {
            const double position = sinc->phase * RAVEN_SINC_PHASES;
            unsigned row = (unsigned)position;
            if (row >= RAVEN_SINC_PHASES) row = RAVEN_SINC_PHASES - 1;
            const float blend = (float)(position - row);
            const float *near = sinc->table + (size_t)row * taps;
            const float *far = near + taps;
            float sum_left = 0.0f, sum_right = 0.0f;
            for (unsigned m = 0; m < taps; ++m) {
                const float weight = near[m] + (far[m] - near[m]) * blend;
                sum_left += weight * oldest_left[m];
                sum_right += weight * oldest_right[m];
            }
            emit(saturate(sum_left), saturate(sum_right), user);
            sinc->phase += step;
        }
        sinc->phase -= 1.0;
    }
}
