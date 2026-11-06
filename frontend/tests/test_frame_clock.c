#include <ravenemu/frame_clock.h>

#include <math.h>
#include <stdio.h>
#include <stdlib.h>

/* Active in Release too. Test virtual clocks, never wall-time sleeps. */
#define CHECK(c) do { if (!(c)) { \
    fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); \
} } while (0)

static void rate_at_display(double fps, double refresh, unsigned seconds,
                            uint64_t origin, int jitter) {
    raven_frame_clock clock = {0};
    CHECK(raven_frame_clock_configure(&clock, fps));
    unsigned total = 0, maximum = 0;
    uint64_t elapsed = 0;
    const unsigned draws = (unsigned)(refresh * seconds + 0.5);
    for (unsigned i = 0; i <= draws; ++i) {
        elapsed = (uint64_t)((double)i * 1000000000.0 / refresh + 0.5);
        // Alternate +/-0.75ms while preserving timestamp order and duration.
        if (jitter && i && i < draws)
            elapsed = i % 2 ? elapsed + 750000 : elapsed - 750000;
        const unsigned due = raven_frame_clock_tick(&clock, origin + elapsed);
        CHECK(due <= RAVEN_FRAME_CLOCK_MAX_CATCHUP);
        if (due > maximum) maximum = due;
        total += due;
    }
    // One immediate initial frame, then elapsed periods, independent of vsync.
    const uint64_t expected = 1u + (uint64_t)((long double)elapsed * fps / 1000000000.0L);
    // Check against the requested FPS, not the clock's stored period. Rounding
    // the period to a nanosecond may shift a boundary by at most one frame.
    CHECK(total + 1u >= expected && total <= expected + 1u);
    if (refresh < fps) CHECK(maximum >= 2);
}

static void display_rates_and_long_term_drift(void) {
    const double refreshes[] = {29.97, 30, 50, 59.94, 60, 90, 120, 144};
    const double core_rates[] = {50.0, 59.727500569606, 60.0};
    for (unsigned r = 0; r < sizeof(refreshes) / sizeof(*refreshes); ++r)
        for (unsigned f = 0; f < sizeof(core_rates) / sizeof(*core_rates); ++f) {
            rate_at_display(core_rates[f], refreshes[r], 120, 0, 0);
            rate_at_display(core_rates[f], refreshes[r], 120,
                            UINT64_C(9000000000000000000), 1);
        }
    rate_at_display(59.727500569606, 120, 3600, 123456789, 1);
}

static void pauses_stalls_and_clock_discontinuities(void) {
    raven_frame_clock clock = {0};
    CHECK(raven_frame_clock_configure(&clock, 50));
    const uint64_t start = 100;
    CHECK(raven_frame_clock_tick(&clock, start) == 1);
    CHECK(raven_frame_clock_tick(&clock, start) == 0);
    CHECK(raven_frame_clock_tick(&clock, start + 19000000) == 0);
    CHECK(raven_frame_clock_tick(&clock, start + 21000000) == 1);
    CHECK(raven_frame_clock_tick(&clock, start + 41000000) == 1);
    // A long stall cannot generate a storm or leave debt for later draws.
    const uint64_t late = UINT64_C(1000000000000);
    CHECK(raven_frame_clock_tick(&clock, late) == RAVEN_FRAME_CLOCK_MAX_CATCHUP);
    CHECK(raven_frame_clock_tick(&clock, late) == 0);
    CHECK(raven_frame_clock_tick(&clock, late + 19000000) == 0);
    CHECK(raven_frame_clock_tick(&clock, late + 20000000) == 1);
    // Pause, surface creation, ROM change or restore discards the old epoch.
    raven_frame_clock_reset(&clock);
    CHECK(raven_frame_clock_tick(&clock, late * 2) == 1);
    CHECK(raven_frame_clock_tick(&clock, late * 2) == 0);
    CHECK(raven_frame_clock_tick(&clock, 1) == 0);
    CHECK(raven_frame_clock_tick(&clock, 20000001) == 1);
    CHECK(raven_frame_clock_configure(&clock, 100));
    CHECK(raven_frame_clock_tick(&clock, UINT64_MAX - 2) == 1);
    CHECK(raven_frame_clock_tick(&clock, UINT64_MAX) == 0);
    CHECK(raven_frame_clock_tick(&clock, 0) == 0);
    CHECK(raven_frame_clock_tick(&clock, 10000000) == 1);
}

static void invalid_rates_and_disabled_clock(void) {
    raven_frame_clock clock = {0};
    const double invalid[] = {0, -60, NAN, INFINITY, -INFINITY, 0.99, 240.01};
    CHECK(raven_frame_clock_tick(&clock, 99) == 0);
    for (unsigned i = 0; i < sizeof(invalid) / sizeof(*invalid); ++i) {
        CHECK(raven_frame_clock_configure(&clock, 60));
        CHECK(raven_frame_clock_tick(&clock, 1) == 1);
        CHECK(!raven_frame_clock_configure(&clock, invalid[i]));
        CHECK(raven_frame_clock_tick(&clock, UINT64_MAX) == 0);
    }
    CHECK(!raven_frame_clock_configure(NULL, 60));
    CHECK(raven_frame_clock_tick(NULL, 0) == 0);
    raven_frame_clock_reset(NULL);
    CHECK(raven_frame_clock_configure(&clock, 1));
    CHECK(raven_frame_clock_tick(&clock, 0) == 1);
    CHECK(raven_frame_clock_tick(&clock, UINT64_MAX) == RAVEN_FRAME_CLOCK_MAX_CATCHUP);
    CHECK(raven_frame_clock_configure(&clock, 240));
    CHECK(raven_frame_clock_tick(&clock, 0) == 1);
    CHECK(raven_frame_clock_tick(&clock, 12500001) == 3);
    CHECK(raven_frame_clock_tick(&clock, 12500001) == 0);
}

int main(void) {
    display_rates_and_long_term_drift();
    pauses_stalls_and_clock_discontinuities();
    invalid_rates_and_disabled_clock();
    puts("Frame pacing: rates, jitter, hour-long drift, resets and bounded catch-up passed");
    return 0;
}
