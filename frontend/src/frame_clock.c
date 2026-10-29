#include <ravenemu/frame_clock.h>

void raven_frame_clock_reset(raven_frame_clock *clock) {
    if (!clock) return;
    clock->previous_ns = clock->remainder_ns = 0;
    clock->started = 0;
}

int raven_frame_clock_configure(raven_frame_clock *clock, double fps) {
    if (!clock) return 0;
    raven_frame_clock_reset(clock);
    clock->period_ns = 0;
    /* This comparison rejects NaN as well as infinity and outliers. */
    if (!(fps >= 1.0 && fps <= 240.0)) return 0;
    clock->period_ns = (uint64_t)(1000000000.0 / fps + 0.5);
    return 1;
}

unsigned raven_frame_clock_tick(raven_frame_clock *clock, uint64_t now_ns) {
    if (!clock || !clock->period_ns) return 0;
    if (!clock->started) {
        clock->started = 1;
        clock->previous_ns = now_ns;
        return 1;
    }
    if (now_ns < clock->previous_ns) {
        clock->previous_ns = now_ns;
        clock->remainder_ns = 0;
        return 0;
    }
    const uint64_t elapsed = now_ns - clock->previous_ns;
    clock->previous_ns = now_ns;
    /* Bound before adding, so a very large timestamp jump cannot overflow.
     * Three frames is a work limit, not a demand to recover all lost time. */
    if (elapsed > clock->period_ns * RAVEN_FRAME_CLOCK_MAX_CATCHUP) {
        clock->remainder_ns = 0;
        return RAVEN_FRAME_CLOCK_MAX_CATCHUP;
    }
    clock->remainder_ns += elapsed;
    const unsigned frames = (unsigned)(clock->remainder_ns / clock->period_ns);
    clock->remainder_ns %= clock->period_ns;
    return frames;
}
