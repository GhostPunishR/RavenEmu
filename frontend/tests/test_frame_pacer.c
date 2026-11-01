#include <ravenemu/frame_pacer.h>

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

/* Virtual clocks only, never wall-time sleeps. */
#define CHECK(c) do { if (!(c)) { \
    fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); \
} } while (0)

#define GBA_FPS 59.727500569606

static void cadences(void) {
    CHECK(raven_frame_cadence(60.0, GBA_FPS) == 1);
    CHECK(raven_frame_cadence(59.94, GBA_FPS) == 1);
    CHECK(raven_frame_cadence(120.0, GBA_FPS) == 2);
    CHECK(raven_frame_cadence(240.0, GBA_FPS) == 4);
    CHECK(raven_frame_cadence(90.0, GBA_FPS) == 0);   /* 1.5 refreshes per frame */
    CHECK(raven_frame_cadence(144.0, GBA_FPS) == 0);
    CHECK(raven_frame_cadence(75.0, GBA_FPS) == 0);
    CHECK(raven_frame_cadence(50.0, GBA_FPS) == 0);
    CHECK(raven_frame_cadence(30.0, GBA_FPS) == 0);
    CHECK(raven_frame_cadence(50.0, 50.0) == 1);
    CHECK(raven_frame_cadence(NAN, GBA_FPS) == 0);
    CHECK(raven_frame_cadence(60.0, INFINITY) == 0);
    CHECK(raven_frame_cadence(0.0, GBA_FPS) == 0);
    CHECK(raven_frame_cadence(60.0, 0.0) == 0);
}

typedef struct run {
    unsigned total, after_lock, zeros, ones, twos, more;
    int locked;
} run;

/* Draws at `actual_hz` with alternating jitter while the system says
 * `reported_hz`; counts the frames due per draw once past `settle` draws. */
static run draw(raven_frame_pacer *pacer, double actual_hz, double seconds,
                uint64_t origin, unsigned settle) {
    run result = {0};
    const unsigned draws = (unsigned)(actual_hz * seconds + 0.5);
    for (unsigned i = 0; i <= draws; ++i) {
        uint64_t at = (uint64_t)((double)i * 1000000000.0 / actual_hz + 0.5);
        if (i && i < draws) at = i % 2 ? at + 700000 : at - 700000;
        const unsigned due = raven_frame_pacer_tick(pacer, origin + at);
        CHECK(due <= RAVEN_FRAME_CLOCK_MAX_CATCHUP);
        result.total += due;
        if (i < settle) continue;
        result.after_lock += due;
        if (due == 0) ++result.zeros;
        else if (due == 1) ++result.ones;
        else if (due == 2) ++result.twos;
        else ++result.more;
    }
    result.locked = raven_frame_pacer_locked(pacer);
    return result;
}

static void sixty_hertz_runs_one_frame_per_refresh(void) {
    raven_frame_pacer pacer = {0};
    CHECK(raven_frame_pacer_configure(&pacer, GBA_FPS));
    raven_frame_pacer_set_display(&pacer, 60.0);
    CHECK(raven_frame_pacer_speed(&pacer) == 1.0);
    /* One hour: never a refresh without a frame, never two frames. */
    const run hour = draw(&pacer, 60.0, 3600.0, 123456789, RAVEN_FRAME_PACER_LOCK_DRAWS + 1);
    CHECK(hour.locked);
    CHECK(hour.zeros == 0 && hour.twos == 0 && hour.more == 0);
    CHECK(hour.ones == 60u * 3600u - RAVEN_FRAME_PACER_LOCK_DRAWS);
    const double speed = raven_frame_pacer_speed(&pacer);
    CHECK(fabs(speed - 60.0 / GBA_FPS) < 1e-12);
    CHECK(speed > 1.004 && speed < 1.005);
}

static void one_hundred_twenty_hertz_runs_one_frame_every_two_refreshes(void) {
    raven_frame_pacer pacer = {0};
    CHECK(raven_frame_pacer_configure(&pacer, GBA_FPS));
    raven_frame_pacer_set_display(&pacer, 120.0);
    const run minute = draw(&pacer, 120.0, 60.0, 0, RAVEN_FRAME_PACER_LOCK_DRAWS + 1);
    CHECK(minute.locked);
    CHECK(minute.twos == 0 && minute.more == 0);
    /* Every other refresh, exactly. */
    CHECK(minute.ones == minute.zeros || minute.ones + 1 == minute.zeros || minute.zeros + 1 == minute.ones);
    CHECK(fabs(raven_frame_pacer_speed(&pacer) - 60.0 / GBA_FPS) < 1e-12);
}

static void a_display_that_does_not_divide_keeps_the_console_rate(void) {
    raven_frame_pacer pacer = {0};
    CHECK(raven_frame_pacer_configure(&pacer, GBA_FPS));
    raven_frame_pacer_set_display(&pacer, 90.0);
    const run minute = draw(&pacer, 90.0, 60.0, 0, 0);
    CHECK(!minute.locked && raven_frame_pacer_speed(&pacer) == 1.0);
    const double expected = 1.0 + 60.0 * GBA_FPS;
    CHECK(fabs((double)minute.total - expected) <= 1.5);
}

static void a_wrong_reported_rate_never_locks(void) {
    /* Phones may switch to 90 or 120 Hz while the system still says 60. At
     * 57 or 64 Hz every interval is near one refresh: only the measured
     * average keeps the pacer off. */
    const double actual[] = {120.0, 90.0, 72.0, 48.0, 64.0, 57.0};
    for (unsigned i = 0; i < sizeof(actual) / sizeof(*actual); ++i) {
        raven_frame_pacer pacer = {0};
        CHECK(raven_frame_pacer_configure(&pacer, GBA_FPS));
        raven_frame_pacer_set_display(&pacer, 60.0);
        const run minute = draw(&pacer, actual[i], 60.0, 0, 0);
        CHECK(!minute.locked);
        CHECK(fabs((double)minute.total - (1.0 + 60.0 * GBA_FPS)) <= 1.5);
    }
}

static void a_missed_refresh_is_caught_up_and_stays_locked(void) {
    raven_frame_pacer pacer = {0};
    CHECK(raven_frame_pacer_configure(&pacer, GBA_FPS));
    raven_frame_pacer_set_display(&pacer, 60.0);
    const uint64_t period = 16666667;
    uint64_t now = 1000;
    for (unsigned i = 0; i <= RAVEN_FRAME_PACER_LOCK_DRAWS; ++i, now += period)
        raven_frame_pacer_tick(&pacer, now);
    CHECK(raven_frame_pacer_locked(&pacer));
    CHECK(raven_frame_pacer_tick(&pacer, now) == 1);
    now += 2 * period; /* one refresh without a draw */
    CHECK(raven_frame_pacer_tick(&pacer, now) == 2);
    now += period;
    CHECK(raven_frame_pacer_tick(&pacer, now) == 1);
    CHECK(raven_frame_pacer_locked(&pacer));
    /* A long stall never asks for a storm of frames. */
    now += UINT64_C(5000000000);
    CHECK(raven_frame_pacer_tick(&pacer, now) <= RAVEN_FRAME_CLOCK_MAX_CATCHUP);
}

static void a_new_rate_relocks_and_an_unannounced_one_unlocks(void) {
    raven_frame_pacer pacer = {0};
    CHECK(raven_frame_pacer_configure(&pacer, GBA_FPS));
    raven_frame_pacer_set_display(&pacer, 60.0);
    run settle = draw(&pacer, 60.0, 2.0, 0, 0);
    CHECK(settle.locked);
    raven_frame_pacer_set_display(&pacer, 120.0);
    CHECK(!raven_frame_pacer_locked(&pacer) && raven_frame_pacer_speed(&pacer) == 1.0);
    settle = draw(&pacer, 120.0, 2.0, UINT64_C(3000000000), 0);
    CHECK(settle.locked);
    /* The display goes to 90 Hz without a word: back to the console clock. */
    settle = draw(&pacer, 90.0, 1.0, UINT64_C(6000000000), 0);
    CHECK(!settle.locked);
}

static void reset_and_invalid_rates(void) {
    raven_frame_pacer pacer = {0};
    CHECK(raven_frame_pacer_tick(&pacer, 5) == 0);
    CHECK(!raven_frame_pacer_configure(&pacer, NAN));
    CHECK(raven_frame_pacer_tick(&pacer, 5) == 0);
    CHECK(raven_frame_pacer_configure(&pacer, GBA_FPS));
    raven_frame_pacer_set_display(&pacer, NAN);
    CHECK(pacer.vsync_ns == 0 && pacer.vsyncs_per_frame == 0);
    CHECK(raven_frame_pacer_tick(&pacer, 100) == 1);
    raven_frame_pacer_set_display(&pacer, 60.0);
    draw(&pacer, 60.0, 2.0, 1000, 0);
    CHECK(raven_frame_pacer_locked(&pacer));
    raven_frame_pacer_reset(&pacer);
    CHECK(!raven_frame_pacer_locked(&pacer) && raven_frame_pacer_speed(&pacer) == 1.0);
    /* The first draw after a pause runs a frame at once, as the clock does. */
    CHECK(raven_frame_pacer_tick(&pacer, UINT64_C(900000000000)) == 1);
    /* The display survives a new game; the lock does not. */
    CHECK(raven_frame_pacer_configure(&pacer, GBA_FPS));
    CHECK(pacer.display_hz == 60.0 && pacer.vsyncs_per_frame == 1 && !raven_frame_pacer_locked(&pacer));
}

static void slow_replays_lower_the_preemptive_frames(void) {
    raven_replay_guard guard = {0};
    const uint64_t period = 16742706; /* one GBA frame */
    /* Within budget: never. A lone overrun among eight: forgotten. */
    for (unsigned i = 0; i < 100; ++i)
        CHECK(!raven_replay_guard_overloaded(&guard, period / 2 + (i % 8 == 0 ? period : 0), GBA_FPS));
    /* Three overruns within eight replays: one frame less, then again. */
    guard = (raven_replay_guard){0};
    CHECK(!raven_replay_guard_overloaded(&guard, period, GBA_FPS));
    CHECK(!raven_replay_guard_overloaded(&guard, period / 4, GBA_FPS));
    CHECK(!raven_replay_guard_overloaded(&guard, period, GBA_FPS));
    CHECK(raven_replay_guard_overloaded(&guard, period, GBA_FPS));
    CHECK(guard.replays == 0 && guard.overruns == 0);
    /* The budget is three quarters of the frame. */
    CHECK(!raven_replay_guard_overloaded(&guard, period * 3 / 4 - 1000, GBA_FPS));
    CHECK(guard.overruns == 0);
    CHECK(!raven_replay_guard_overloaded(&guard, period * 3 / 4 + 1000, GBA_FPS));
    CHECK(guard.overruns == 1);
    CHECK(!raven_replay_guard_overloaded(&guard, UINT64_MAX, NAN));
    CHECK(!raven_replay_guard_overloaded(NULL, UINT64_MAX, GBA_FPS));
}

int main(void) {
    cadences();
    sixty_hertz_runs_one_frame_per_refresh();
    one_hundred_twenty_hertz_runs_one_frame_every_two_refreshes();
    a_display_that_does_not_divide_keeps_the_console_rate();
    a_wrong_reported_rate_never_locks();
    a_missed_refresh_is_caught_up_and_stays_locked();
    a_new_rate_relocks_and_an_unannounced_one_unlocks();
    reset_and_invalid_rates();
    slow_replays_lower_the_preemptive_frames();
    puts("Frame pacer: 9 scenarios passed");
    return 0;
}
