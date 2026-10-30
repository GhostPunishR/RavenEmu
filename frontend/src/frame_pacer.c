#include <ravenemu/frame_pacer.h>

#include <math.h>

unsigned raven_frame_cadence(double display_hz, double core_fps) {
    /* These comparisons reject NaN and infinities as well as outliers. */
    if (!(core_fps >= 1.0 && core_fps <= 240.0) || !(display_hz >= 1.0 && display_hz <= 1000.0))
        return 0;
    const double ratio = display_hz / core_fps;
    const double count = floor(ratio + 0.5);
    if (count < 1.0 || count > RAVEN_FRAME_PACER_MAX_VSYNCS) return 0;
    /* The skew of a frame lasting `count` refreshes against the console's. */
    const double skew = fabs(count / ratio - 1.0);
    return skew <= RAVEN_FRAME_PACER_MAX_SKEW ? (unsigned)count : 0;
}

static void unlock(raven_frame_pacer *pacer) {
    pacer->locked = 0;
    pacer->pending = pacer->agreeing = pacer->disagreeing = 0;
    pacer->average_ns = (double)pacer->vsync_ns;
}

void raven_frame_pacer_reset(raven_frame_pacer *pacer) {
    if (!pacer) return;
    raven_frame_clock_reset(&pacer->clock);
    pacer->started = 0;
    pacer->previous_ns = 0;
    unlock(pacer);
}

int raven_frame_pacer_configure(raven_frame_pacer *pacer, double core_fps) {
    if (!pacer) return 0;
    const int valid = raven_frame_clock_configure(&pacer->clock, core_fps);
    pacer->core_fps = valid ? core_fps : 0.0;
    raven_frame_pacer_set_display(pacer, pacer->display_hz);
    return valid;
}

void raven_frame_pacer_set_display(raven_frame_pacer *pacer, double display_hz) {
    if (!pacer) return;
    if (!(display_hz >= 1.0 && display_hz <= 1000.0)) display_hz = 0.0;
    pacer->display_hz = display_hz;
    pacer->vsync_ns = display_hz > 0.0 ? (uint64_t)(1000000000.0 / display_hz + 0.5) : 0;
    pacer->vsyncs_per_frame = raven_frame_cadence(display_hz, pacer->core_fps);
    unlock(pacer);
}

/* A call interval of whole refreshes, as when a refresh is missed, agrees
 * with the display, within a quarter of one for the draws' jitter; anything
 * else (a display really at 90 or 120 Hz while the system still says 60)
 * does not. The average, within 2 %, catches what the quarter lets by. */
static unsigned whole_refreshes(const raven_frame_pacer *pacer, uint64_t elapsed, int *agrees) {
    const uint64_t period = pacer->vsync_ns;
    const uint64_t count = (elapsed + period / 2) / period;
    const uint64_t nearest = count * period;
    const uint64_t error = elapsed > nearest ? elapsed - nearest : nearest - elapsed;
    *agrees = count >= 1 && count <= RAVEN_FRAME_PACER_MAX_VSYNCS && error <= period / 4;
    return count > RAVEN_FRAME_PACER_MAX_VSYNCS ? RAVEN_FRAME_PACER_MAX_VSYNCS : (unsigned)count;
}

unsigned raven_frame_pacer_tick(raven_frame_pacer *pacer, uint64_t now_ns) {
    if (!pacer || !pacer->clock.period_ns) return 0;
    /* The clock always runs, so that unlocking continues from its epoch. */
    const unsigned clocked = raven_frame_clock_tick(&pacer->clock, now_ns);
    if (!pacer->vsyncs_per_frame || !pacer->vsync_ns) return clocked;
    if (!pacer->started || now_ns < pacer->previous_ns) {
        pacer->started = 1;
        pacer->previous_ns = now_ns;
        unlock(pacer);
        return clocked;
    }
    const uint64_t elapsed = now_ns - pacer->previous_ns;
    pacer->previous_ns = now_ns;
    int agrees = 0;
    const unsigned refreshes = whole_refreshes(pacer, elapsed, &agrees);
    if (agrees) {
        /* Moving average over sixteen draws: one late call moves nothing. */
        pacer->average_ns += ((double)elapsed / refreshes - pacer->average_ns) / 16.0;
        pacer->disagreeing = 0;
        if (pacer->agreeing < RAVEN_FRAME_PACER_LOCK_DRAWS) ++pacer->agreeing;
    } else {
        pacer->agreeing = 0;
        ++pacer->disagreeing;
    }
    const double drift = fabs(pacer->average_ns / (double)pacer->vsync_ns - 1.0);
    if (pacer->locked && (pacer->disagreeing >= RAVEN_FRAME_PACER_UNLOCK_DRAWS || drift > 0.03))
        unlock(pacer);
    else if (!pacer->locked && pacer->agreeing >= RAVEN_FRAME_PACER_LOCK_DRAWS && drift <= 0.02)
        pacer->locked = 1;
    if (!pacer->locked) return clocked;
    pacer->pending += refreshes;
    unsigned frames = pacer->pending / pacer->vsyncs_per_frame;
    pacer->pending %= pacer->vsyncs_per_frame;
    return frames > RAVEN_FRAME_CLOCK_MAX_CATCHUP ? RAVEN_FRAME_CLOCK_MAX_CATCHUP : frames;
}

int raven_frame_pacer_locked(const raven_frame_pacer *pacer) {
    return pacer && pacer->locked;
}

double raven_frame_pacer_speed(const raven_frame_pacer *pacer) {
    if (!pacer || !pacer->locked || !pacer->vsyncs_per_frame || !(pacer->core_fps > 0.0)) return 1.0;
    return pacer->display_hz / pacer->vsyncs_per_frame / pacer->core_fps;
}
