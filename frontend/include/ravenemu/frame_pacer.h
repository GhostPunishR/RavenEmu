#ifndef RAVENEMU_FRAME_PACER_H
#define RAVENEMU_FRAME_PACER_H

#include <ravenemu/frame_clock.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* RavenEmu display-locked pacing (project AGPLv3), the idea of RetroArch's
 * "vsync + dynamic rate control" written for RavenEmu, as the beta's
 * DisplayCadence and GbFramePacer do. Call only on the emulation owner
 * thread, once per presented picture. Pure: no sleep, no core access.
 *
 * A timer at the console's rate never falls on the display: at 59.73 FPS on
 * a 60 Hz screen one picture stays up twice about every 3.7 s. When the
 * display refreshes at n times the core rate, within RAVEN_FRAME_PACER_MAX_SKEW,
 * one core frame runs every n refreshes instead, and the game runs that much
 * faster or slower (0.46 % at 60 Hz); raven_frame_pacer_speed() tells the
 * audio. The rate the system reports is only trusted once the measured
 * intervals between calls agree with it: until then, and on any display that
 * does not divide the core rate (90 Hz), the monotonic frame clock paces at
 * the console's own rate. */
#define RAVEN_FRAME_PACER_MAX_SKEW 0.05
#define RAVEN_FRAME_PACER_MAX_VSYNCS 4u
/* Agreeing intervals needed to lock, and disagreeing ones that unlock. */
#define RAVEN_FRAME_PACER_LOCK_DRAWS 30u
#define RAVEN_FRAME_PACER_UNLOCK_DRAWS 8u

typedef struct raven_frame_pacer {
    raven_frame_clock clock;    /* At the core rate, kept running when locked. */
    double core_fps;
    double display_hz;
    uint64_t vsync_ns;          /* Display period; 0 when unknown. */
    unsigned vsyncs_per_frame;  /* 0 when the display does not divide the core rate. */
    unsigned pending;           /* Refreshes counted towards the next frame. */
    unsigned agreeing;          /* Consecutive intervals of whole refreshes. */
    unsigned disagreeing;       /* Consecutive other intervals, once locked. */
    double average_ns;          /* Measured refresh, averaged over agreeing draws. */
    uint64_t previous_ns;
    int started;
    int locked;
} raven_frame_pacer;

/* Refreshes per core frame, 1..RAVEN_FRAME_PACER_MAX_VSYNCS, or 0 when the
 * display rate is not a multiple of the core rate within the skew. */
unsigned raven_frame_cadence(double display_hz, double core_fps);

/* As raven_frame_clock_configure; the display stays unknown until set. */
int raven_frame_pacer_configure(raven_frame_pacer *pacer, double core_fps);
/* The refresh rate the system reports; 0 or nonsense means unknown. A new
 * rate unlocks until the measured intervals agree with it again. */
void raven_frame_pacer_set_display(raven_frame_pacer *pacer, double display_hz);
/* After a pause, a surface change or a restored state. */
void raven_frame_pacer_reset(raven_frame_pacer *pacer);
/* Core frames to run now, 0..RAVEN_FRAME_CLOCK_MAX_CATCHUP. */
unsigned raven_frame_pacer_tick(raven_frame_pacer *pacer, uint64_t now_ns);
int raven_frame_pacer_locked(const raven_frame_pacer *pacer);
/* The game's speed against the console: 1.0, or the display's when locked. */
double raven_frame_pacer_speed(const raven_frame_pacer *pacer);

/* Preemptive frames rerun up to three frames within one: a phone too slow
 * for that misses refreshes at every press. A frame that replayed and took
 * more than RAVEN_REPLAY_BUDGET of the core's frame period overran; at
 * RAVEN_REPLAY_OVERRUNS overruns within RAVEN_REPLAY_WINDOW replays, the
 * guard says to replay one frame less. Pure, emulation owner only. */
#define RAVEN_REPLAY_BUDGET 0.75
#define RAVEN_REPLAY_WINDOW 8u
#define RAVEN_REPLAY_OVERRUNS 3u

typedef struct raven_replay_guard {
    unsigned replays;
    unsigned overruns;
} raven_replay_guard;

/* After each frame that replayed, with its whole cost: 1 when the frames
 * should be lowered (the count then starts again), else 0. */
int raven_replay_guard_overloaded(raven_replay_guard *guard, uint64_t cost_ns, double core_fps);

#ifdef __cplusplus
}
#endif

#endif
