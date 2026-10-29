#ifndef RAVEN_NATIVE_AUDIO_H
#define RAVEN_NATIVE_AUDIO_H

#include <stddef.h>
#include <stdint.h>

/* Android 8+ AAudio PCM16 stereo. Producer: emulation/GL thread.
 * Consumer: AAudio data callback. No blocking or allocation in callback. */
int raven_audio_open(double input_sample_rate);
void raven_audio_write(const int16_t *stereo, size_t frames);
void raven_audio_set_running(int running);
/* Emulation owner only. Preferences are independent of lifecycle pause and
 * survive close/open. Volume is clamped to 0..100; muted output remains
 * clocked, with silence in place of PCM. Pending software PCM is discarded
 * on change; samples already submitted to the device may finish playing. */
void raven_audio_set_output(int enabled, int volume);
void raven_audio_close(void);
#endif
