#ifndef RAVEN_NATIVE_SINC_H
#define RAVEN_NATIVE_SINC_H

#include <stddef.h>
#include <stdint.h>

/* Windowed-sinc resampling of PCM16 stereo (RavenEmu code, AGPLv3): the
 * classic band-limited interpolation, a Kaiser-windowed sinc read from a
 * polyphase table. Where linear interpolation leaves images of the core's
 * high notes above its Nyquist frequency (heard as a metallic hiss on the
 * Game Boy's square waves), the sinc filters them out. Pure: no device. */

/* Zero crossings on each side of the kernel, at the input rate when
 * upsampling; widened by the ratio when downsampling. */
#define RAVEN_SINC_ZEROS 16
#define RAVEN_SINC_MAX_TAPS 128
/* Table rows per input sample; coefficients between rows are interpolated. */
#define RAVEN_SINC_PHASES 256
/* Passband against the lower Nyquist frequency of the two rates. */
#define RAVEN_SINC_CUTOFF 0.90
#define RAVEN_SINC_KAISER_BETA 8.0

typedef struct raven_sinc {
    float *table;             /* (PHASES + 1) rows of `taps` coefficients */
    unsigned taps;            /* even, at most RAVEN_SINC_MAX_TAPS */
    unsigned head;            /* next slot of the history ring */
    int primed;               /* the history holds input */
    double phase;             /* position of the next output, 0..1 past the centre */
    float history[2][2 * RAVEN_SINC_MAX_TAPS]; /* each frame twice: no wrap when reading */
} raven_sinc;

typedef void (*raven_sinc_emit)(int16_t left, int16_t right, void *user);

/* Builds the filter for `input_rate` to `output_rate`; 0 on nonsense rates or
 * without memory. Allocates: not from the audio device's callback. */
int raven_sinc_configure(raven_sinc *sinc, double input_rate, double output_rate);
/* Forgets the history: the next frame starts the stream again. */
void raven_sinc_reset(raven_sinc *sinc);
void raven_sinc_free(raven_sinc *sinc);
/* Takes `frames` interleaved stereo frames; `step` is input frames per
 * output frame (the rate ratio with its corrections). Emits exactly as many
 * frames as linear interpolation would: none for the stream's first input
 * frame, then one per `step` of input. */
void raven_sinc_process(raven_sinc *sinc, const int16_t *stereo, size_t frames, double step,
                        raven_sinc_emit emit, void *user);

#endif
