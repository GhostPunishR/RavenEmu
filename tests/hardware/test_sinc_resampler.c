#include "sinc_resampler.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* The sinc resampler against the linear interpolation it replaces: same
 * output cadence, a constant kept exact, and the images and aliases that
 * linear interpolation lets through, removed. Signals are generated here. */
#define CHECK(c) do { if (!(c)) { \
    fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); \
} } while (0)

#define PI 3.14159265358979323846
#define MAX_OUT 400000

static int16_t out_left[MAX_OUT], out_right[MAX_OUT];
static size_t out_count;

static void collect(int16_t left, int16_t right, void *user) {
    (void)user;
    CHECK(out_count < MAX_OUT);
    out_left[out_count] = left;
    out_right[out_count] = right;
    ++out_count;
}

/* The linear interpolation of audio_pipeline.c, for cadence and comparison. */
static void linear(const int16_t *stereo, size_t frames, double step) {
    double phase = 0.0;
    for (size_t i = 1; i < frames; ++i) {
        while (phase < 1.0) {
            const double l = stereo[2 * (i - 1)] + (stereo[2 * i] - stereo[2 * (i - 1)]) * phase;
            const double r = stereo[2 * (i - 1) + 1] + (stereo[2 * i + 1] - stereo[2 * (i - 1) + 1]) * phase;
            collect((int16_t)lrint(l), (int16_t)lrint(r), NULL);
            phase += step;
        }
        phase -= 1.0;
    }
}

static int16_t *tone(size_t frames, double rate, double hz, double amplitude) {
    int16_t *stereo = malloc(frames * 2 * sizeof(int16_t));
    CHECK(stereo);
    for (size_t i = 0; i < frames; ++i) {
        stereo[2 * i] = (int16_t)lrint(amplitude * sin(2.0 * PI * hz * (double)i / rate));
        stereo[2 * i + 1] = (int16_t)lrint(amplitude * cos(2.0 * PI * hz * (double)i / rate));
    }
    return stereo;
}

/* Least-squares fit of a sine at `hz` plus DC over samples [from, to);
 * returns the fitted amplitude and stores the residual's power ratio, dB. */
static double fit(const int16_t *samples, size_t from, size_t to, double rate, double hz, double *snr_db) {
    double s[3][3] = {{0}}, b[3] = {0};
    for (size_t j = from; j < to; ++j) {
        const double v[3] = {sin(2 * PI * hz * j / rate), cos(2 * PI * hz * j / rate), 1.0};
        for (int r = 0; r < 3; ++r) {
            b[r] += v[r] * samples[j];
            for (int c = 0; c < 3; ++c) s[r][c] += v[r] * v[c];
        }
    }
    /* Gaussian elimination, 3x3. */
    for (int k = 0; k < 3; ++k)
        for (int r = k + 1; r < 3; ++r) {
            const double f = s[r][k] / s[k][k];
            for (int c = k; c < 3; ++c) s[r][c] -= f * s[k][c];
            b[r] -= f * b[k];
        }
    double x[3];
    for (int k = 2; k >= 0; --k) {
        double v = b[k];
        for (int c = k + 1; c < 3; ++c) v -= s[k][c] * x[c];
        x[k] = v / s[k][k];
    }
    double signal = 0.0, noise = 0.0;
    for (size_t j = from; j < to; ++j) {
        const double model = x[0] * sin(2 * PI * hz * j / rate) + x[1] * cos(2 * PI * hz * j / rate);
        const double residual = samples[j] - model - x[2];
        signal += model * model;
        noise += residual * residual;
    }
    *snr_db = 10.0 * log10(signal / (noise > 1e-9 ? noise : 1e-9));
    return sqrt(x[0] * x[0] + x[1] * x[1]);
}

/* Converts a tone and measures the result on both channels. */
static double convert(double in_rate, double out_rate, double hz, int sinc_filter, double *snr_db) {
    const size_t frames = (size_t)(in_rate / 4); /* a quarter second */
    int16_t *stereo = tone(frames, in_rate, hz, 16000.0);
    out_count = 0;
    if (sinc_filter) {
        raven_sinc sinc = {0};
        CHECK(raven_sinc_configure(&sinc, in_rate, out_rate));
        raven_sinc_process(&sinc, stereo, frames / 2, in_rate / out_rate, collect, NULL);
        raven_sinc_process(&sinc, stereo + frames / 2 * 2, frames - frames / 2, in_rate / out_rate, collect, NULL);
        raven_sinc_free(&sinc);
    } else {
        linear(stereo, frames, in_rate / out_rate);
    }
    free(stereo);
    double snr_right;
    const double amplitude = fit(out_left, out_count / 8, out_count, out_rate, hz, snr_db);
    const double amplitude_right = fit(out_right, out_count / 8, out_count, out_rate, hz, &snr_right);
    CHECK(fabs(amplitude - amplitude_right) < 16000.0 * 0.002);
    if (snr_right < *snr_db) *snr_db = snr_right;
    return amplitude;
}

static void a_constant_stays_exact_with_linear_cadence(void) {
    const double steps[] = {32768.0 / 48000.0, 1.0, 131072.0 / 48000.0, 0.5, 44100.0 / 48000.0};
    for (unsigned s = 0; s < sizeof(steps) / sizeof(*steps); ++s) {
        raven_sinc sinc = {0};
        CHECK(raven_sinc_configure(&sinc, steps[s] * 48000.0, 48000.0));
        int16_t constant[2000 * 2];
        for (int i = 0; i < 2000; ++i) { constant[2 * i] = 12345; constant[2 * i + 1] = -32768; }
        out_count = 0;
        /* Uneven batches, as the core sends them. */
        size_t done = 0;
        for (size_t batch = 1; done < 2000; batch = batch * 3 % 97 + 1) {
            const size_t n = batch < 2000 - done ? batch : 2000 - done;
            raven_sinc_process(&sinc, constant + 2 * done, n, steps[s], collect, NULL);
            done += n;
        }
        const size_t sinc_count = out_count;
        for (size_t j = 0; j < sinc_count; ++j) CHECK(out_left[j] == 12345 && out_right[j] == -32768);
        out_count = 0;
        linear(constant, 2000, steps[s]);
        CHECK(sinc_count == out_count);
        raven_sinc_free(&sinc);
    }
}

static void upsampling_removes_the_images_linear_lets_through(void) {
    double sinc_snr, linear_snr;
    /* A Game Boy rate tone high in the band: its image lands at 22.8 kHz. */
    const double amplitude = convert(32768.0, 48000.0, 10000.0, 1, &sinc_snr);
    convert(32768.0, 48000.0, 10000.0, 0, &linear_snr);
    printf("  10 kHz, 32768 -> 48000 Hz: sinc %.1f dB, linear %.1f dB\n", sinc_snr, linear_snr);
    CHECK(fabs(amplitude - 16000.0) < 16000.0 * 0.005);
    CHECK(sinc_snr > 70.0);
    CHECK(linear_snr < 30.0);
    /* Low notes, where linear interpolation does best, still gain. */
    convert(32768.0, 48000.0, 440.0, 1, &sinc_snr);
    convert(32768.0, 48000.0, 440.0, 0, &linear_snr);
    printf("  440 Hz, 32768 -> 48000 Hz: sinc %.1f dB, linear %.1f dB\n", sinc_snr, linear_snr);
    CHECK(sinc_snr > 80.0 && sinc_snr > linear_snr);
}

static void downsampling_filters_what_the_device_cannot_play(void) {
    double snr, unused;
    /* A tone in the passband goes through untouched. */
    const double kept = convert(131072.0, 48000.0, 5000.0, 1, &snr);
    CHECK(fabs(kept - 16000.0) < 16000.0 * 0.005 && snr > 70.0);
    /* 30 kHz, above the device's 24 kHz: linear interpolation folds it back
     * to 18 kHz almost whole; the sinc keeps that alias below -70 dB. */
    const size_t frames = 131072 / 4;
    int16_t *stereo = tone(frames, 131072.0, 30000.0, 16000.0);
    raven_sinc sinc = {0};
    CHECK(raven_sinc_configure(&sinc, 131072.0, 48000.0));
    out_count = 0;
    raven_sinc_process(&sinc, stereo, frames, 131072.0 / 48000.0, collect, NULL);
    raven_sinc_free(&sinc);
    const double sinc_alias = fit(out_left, out_count / 8, out_count, 48000.0, 18000.0, &unused);
    out_count = 0;
    linear(stereo, frames, 131072.0 / 48000.0);
    const double linear_alias = fit(out_left, out_count / 8, out_count, 48000.0, 18000.0, &unused);
    free(stereo);
    printf("  30 kHz into 48000 Hz: alias at 18 kHz %.1f dB with sinc, %.1f dB linear\n",
           20.0 * log10(sinc_alias / 16000.0 + 1e-12), 20.0 * log10(linear_alias / 16000.0));
    CHECK(sinc_alias < 16000.0 * pow(10.0, -70.0 / 20.0));
    CHECK(linear_alias > 16000.0 * pow(10.0, -20.0 / 20.0));
}

static void full_scale_edges_saturate_without_wrapping(void) {
    /* The filter is linear: at full scale its output is twice the half-scale
     * one, clipped to 16 bits, and never wrapped around. */
    static int16_t half_left[MAX_OUT];
    int16_t square[4096 * 2], half[4096 * 2];
    for (int i = 0; i < 4096; ++i) {
        square[2 * i] = square[2 * i + 1] = (i / 37) % 2 ? 32767 : -32768;
        half[2 * i] = half[2 * i + 1] = (i / 37) % 2 ? 16384 : -16384;
    }
    raven_sinc sinc = {0};
    CHECK(raven_sinc_configure(&sinc, 32768.0, 48000.0));
    out_count = 0;
    raven_sinc_process(&sinc, half, 4096, 32768.0 / 48000.0, collect, NULL);
    const size_t count = out_count;
    memcpy(half_left, out_left, count * sizeof(*out_left));
    raven_sinc_reset(&sinc);
    out_count = 0;
    raven_sinc_process(&sinc, square, 4096, 32768.0 / 48000.0, collect, NULL);
    CHECK(out_count == count);
    int clipped = 0;
    for (size_t j = 0; j < count; ++j) {
        long expected = 2L * half_left[j];
        if (expected > 32767) { expected = 32767; clipped = 1; }
        if (expected < -32768) { expected = -32768; clipped = 1; }
        CHECK(labs((long)out_left[j] - expected) <= 3);
    }
    CHECK(clipped);  /* the edges' overshoot did reach the limits */
    raven_sinc_free(&sinc);
}

/* The filter needs half its kernel of future input: that, and no more, is
 * the delay it adds to linear interpolation (about half a millisecond). */
static void the_delay_is_half_the_kernel(void) {
    int16_t step_input[400 * 2];
    for (int i = 0; i < 400; ++i) step_input[2 * i] = step_input[2 * i + 1] = i < 200 ? -10000 : 10000;
    const double step = 32768.0 / 48000.0;
    raven_sinc sinc = {0};
    CHECK(raven_sinc_configure(&sinc, 32768.0, 48000.0));
    out_count = 0;
    raven_sinc_process(&sinc, step_input, 400, step, collect, NULL);
    size_t sinc_crossing = 0;
    while (sinc_crossing < out_count && out_left[sinc_crossing] <= 0) ++sinc_crossing;
    out_count = 0;
    linear(step_input, 400, step);
    size_t linear_crossing = 0;
    while (linear_crossing < out_count && out_left[linear_crossing] <= 0) ++linear_crossing;
    const double expected = (sinc.taps / 2.0 - 1.0) / step;
    CHECK(fabs((double)sinc_crossing - (double)linear_crossing - expected) <= 0.75);
    raven_sinc_free(&sinc);
}

static void reset_channels_and_bad_input(void) {
    raven_sinc sinc = {0};
    out_count = 0;
    const int16_t frame[4] = {1000, 0, 1000, 0};
    raven_sinc_process(&sinc, frame, 2, 1.0, collect, NULL);  /* not configured */
    CHECK(out_count == 0);
    CHECK(!raven_sinc_configure(&sinc, 0.0, 48000.0));
    CHECK(!raven_sinc_configure(&sinc, 48000.0, NAN));
    CHECK(!raven_sinc_configure(NULL, 48000.0, 48000.0));
    CHECK(raven_sinc_configure(&sinc, 32768.0, 48000.0));
    CHECK(sinc.taps == 2 * (unsigned)ceil(RAVEN_SINC_ZEROS / RAVEN_SINC_CUTOFF) && sinc.taps <= RAVEN_SINC_MAX_TAPS);
    raven_sinc_process(&sinc, frame, 2, 0.0, collect, NULL);  /* no step */
    CHECK(out_count == 0);
    int16_t loud[200 * 2], quiet[200 * 2];
    for (int i = 0; i < 200; ++i) {
        loud[2 * i] = 20000; loud[2 * i + 1] = (int16_t)(i % 2 ? 9000 : -9000);
        quiet[2 * i] = -2000; quiet[2 * i + 1] = 0;
    }
    raven_sinc_process(&sinc, loud, 200, 0.7, collect, NULL);
    for (size_t j = 0; j < out_count; ++j) CHECK(out_left[j] == 20000);  /* right moves, left does not */
    /* A reset forgets the past: no trace of the loud frames. */
    raven_sinc_reset(&sinc);
    out_count = 0;
    raven_sinc_process(&sinc, quiet, 200, 0.7, collect, NULL);
    CHECK(out_count > 0);
    for (size_t j = 0; j < out_count; ++j) CHECK(out_left[j] == -2000 && out_right[j] == 0);
    raven_sinc_free(&sinc);
    raven_sinc_free(&sinc);
    CHECK(sinc.table == NULL);
    /* Very high rates are capped in taps. */
    CHECK(raven_sinc_configure(&sinc, 768000.0, 48000.0));
    CHECK(sinc.taps == RAVEN_SINC_MAX_TAPS);
    raven_sinc_free(&sinc);
}

int main(void) {
    a_constant_stays_exact_with_linear_cadence();
    upsampling_removes_the_images_linear_lets_through();
    downsampling_filters_what_the_device_cannot_play();
    full_scale_edges_saturate_without_wrapping();
    the_delay_is_half_the_kernel();
    reset_channels_and_bad_input();
    puts("Sinc resampler: 6 scenarios passed");
    return 0;
}
