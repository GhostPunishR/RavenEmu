#ifndef RAVEN_NATIVE_AUDIO_H
#define RAVEN_NATIVE_AUDIO_H

#include <stddef.h>
#include <stdint.h>

/* Android 8+ AAudio PCM16 stereo. Producer: emulation/GL thread.
 * Consumer: AAudio data callback. No blocking or allocation in callback. */
/* The largest correction of the dynamic rate control: ±0.5 %, RetroArch's
 * default, below what the ear hears. */
#define RAVEN_AUDIO_RATE_CONTROL 0.005

int raven_audio_open(double input_sample_rate);
void raven_audio_write(const int16_t *stereo, size_t frames);
/* The game's speed against the console, 0.9 to 1.1, 1.0 otherwise: the
 * display lock makes it 1.0046 on a 60 Hz screen. */
void raven_audio_set_speed(double speed);
/* 0 turns the rate control off; RAVEN_AUDIO_RATE_CONTROL by default. */
void raven_audio_set_rate_control(double delta);
/* Ratio correction for a queue holding `queued` of `limit` frames. */
double raven_audio_rate_adjust(uint32_t queued, uint32_t limit, double delta);
/* Emulation owner, once per draw: reopens a device AAudio lost (headphones,
 * Bluetooth), keeping the pause state. Returns 1 when it reopened. */
int raven_audio_recover(void);
void raven_audio_set_running(int running);
/* Emulation owner only. Preferences are independent of lifecycle pause and
 * survive close/open. Volume is clamped to 0..100; muted output remains
 * clocked, with silence in place of PCM. Pending software PCM is discarded
 * on change; samples already submitted to the device may finish playing. */
void raven_audio_set_output(int enabled, int volume);
/* How the core's rate becomes the device's: windowed sinc (sinc_resampler.c,
 * the default) or linear interpolation, both with the same cadence and rate
 * control. Emulation owner; survives close/open; a change restarts the
 * stream's filter, and pending software PCM is kept. */
#define RAVEN_AUDIO_RESAMPLER_LINEAR 0
#define RAVEN_AUDIO_RESAMPLER_SINC 1
void raven_audio_set_resampler(int resampler);
/* The beta's start fade (AndroidGbAudioOutput): a raised cosine over the
 * first `milliseconds` of sound each time the stream opens, comes back from
 * a pause or reopens after a lost device, starting at the first sample that
 * is not silence, so that a game's silent first frames do not use it up.
 * 180 by default, as validated on the phone; 0 turns it off (exact tests).
 * Emulation owner; survives close/open. */
#define RAVEN_AUDIO_STARTUP_FADE_MS 180
void raven_audio_set_startup_fade(int milliseconds);
void raven_audio_close(void);
#endif
