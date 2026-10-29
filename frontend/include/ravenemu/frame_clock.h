#ifndef RAVENEMU_FRAME_CLOCK_H
#define RAVENEMU_FRAME_CLOCK_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* RavenEmu monotonic frame pacing (project AGPLv3). Call only on the
 * emulation owner thread. This clock neither sleeps nor accesses a core. */
#define RAVEN_FRAME_CLOCK_MAX_CATCHUP 3u

typedef struct raven_frame_clock {
    uint64_t period_ns;
    uint64_t previous_ns;
    uint64_t remainder_ns;
    int started;
} raven_frame_clock;

/* Accept 1..240 FPS, rounded to the nearest nanosecond. Invalid timing
 * disables the clock; never silently substitute 60 FPS. Resets the epoch. */
int raven_frame_clock_configure(raven_frame_clock *clock, double fps);
/* Keep the rate, discard all elapsed time. Next tick advances immediately. */
void raven_frame_clock_reset(raven_frame_clock *clock);
/* Return 0..MAX_CATCHUP frames due. Retain fractional elapsed time across
 * renders. Excess backlog is discarded, never carried into later ticks.
 * A backwards timestamp starts a new epoch without advancing a frame. */
unsigned raven_frame_clock_tick(raven_frame_clock *clock, uint64_t now_ns);

#ifdef __cplusplus
}
#endif

#endif
