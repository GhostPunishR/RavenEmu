#ifndef RAVEN_NATIVE_AUDIO_H
#define RAVEN_NATIVE_AUDIO_H

#include <stddef.h>
#include <stdint.h>

/* Android 8+ AAudio PCM16 stereo. Producer: emulation/GL thread.
 * Consumer: AAudio data callback. No blocking or allocation in callback. */
int raven_audio_open(double input_sample_rate);
void raven_audio_write(const int16_t *stereo, size_t frames);
void raven_audio_set_running(int running);
void raven_audio_close(void);
#endif
