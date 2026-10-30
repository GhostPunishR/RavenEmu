/* Host-side AAudio boundary tests: no Android audio hardware is simulated. */
#include "audio_pipeline.h"
#include "aaudio_driver.h"
#include <aaudio/AAudio.h>
#include <math.h>
#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>

#define CHECK(c) do { if (!(c)) { fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); return 0; } } while (0)

struct AAudioStreamStruct { int unused; };
struct AAudioStreamBuilderStruct { int unused; };
static struct AAudioStreamStruct fake_stream;
static struct AAudioStreamBuilderStruct fake_builder;
static AAudioStream_dataCallback callback;
static void *callback_user;
static int native_rate = 48000, format = AAUDIO_FORMAT_PCM_I16, channels = 2;
static int burst = 192, buffer_capacity = 4096, buffer_size = 4096;
static int requested_rate, requested_share, requested_perf, requested_channels;
static int start_error, starts, pauses, closes, waits, stuck;
static AAudioStream_errorCallback error_callback;
static void *error_user;
static int open_error, opens;
static aaudio_stream_state_t state;

aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **b) { *b = &fake_builder; return AAUDIO_OK; }
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *b, int32_t x) { (void)b; (void)x; }
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *b, int32_t x) { (void)b; requested_share = x; }
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *b, int32_t x) { (void)b; requested_perf = x; }
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *b, int32_t x) { (void)b; (void)x; }
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *b, int32_t x) { (void)b; requested_channels = x; }
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *b, int32_t x) { (void)b; requested_rate = x; }
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *b, AAudioStream_dataCallback cb, void *u) { (void)b; callback = cb; callback_user = u; }
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *b, AAudioStream_errorCallback cb, void *u) { (void)b; error_callback = cb; error_user = u; }
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *b, AAudioStream **s) {
    (void)b; opens++;
    if (open_error) { *s = NULL; return open_error; }
    *s = &fake_stream; state = AAUDIO_STREAM_STATE_PAUSED; return AAUDIO_OK;
}
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *b) { (void)b; return AAUDIO_OK; }
int32_t AAudioStream_getSampleRate(AAudioStream *s) { (void)s; return native_rate; }
int32_t AAudioStream_getFormat(AAudioStream *s) { (void)s; return format; }
int32_t AAudioStream_getChannelCount(AAudioStream *s) { (void)s; return channels; }
int32_t AAudioStream_getFramesPerBurst(AAudioStream *s) { (void)s; return burst; }
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *s) { (void)s; return buffer_capacity; }
int32_t AAudioStream_getBufferSizeInFrames(AAudioStream *s) { (void)s; return buffer_size; }
aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream *s, int32_t n) { (void)s; buffer_size = n; return n; }
aaudio_result_t AAudioStream_requestStart(AAudioStream *s) { (void)s; starts++; state = AAUDIO_STREAM_STATE_STARTED; return start_error; }
aaudio_result_t AAudioStream_requestPause(AAudioStream *s) { (void)s; pauses++; state = AAUDIO_STREAM_STATE_PAUSED; return AAUDIO_OK; }
aaudio_result_t AAudioStream_requestStop(AAudioStream *s) { (void)s; state = AAUDIO_STREAM_STATE_STOPPING; return AAUDIO_OK; }
aaudio_stream_state_t AAudioStream_getState(AAudioStream *s) { (void)s; return state; }
aaudio_result_t AAudioStream_waitForStateChange(AAudioStream *s, aaudio_stream_state_t before, aaudio_stream_state_t *after, int64_t timeout) {
    (void)s; (void)before;
    if (timeout != 50000000) return -1;
    waits++;
    if (!stuck) state = AAUDIO_STREAM_STATE_STOPPED;
    *after = state;
    return AAUDIO_OK;
}
aaudio_result_t AAudioStream_close(AAudioStream *s) { (void)s; closes++; state = AAUDIO_STREAM_STATE_CLOSED; return AAUDIO_OK; }
int __android_log_print(int p, const char *tag, const char *message, ...) { (void)p; (void)tag; (void)message; return 0; }

static void consume(int16_t *out, int frames) { callback(&fake_stream, callback_user, out, frames); }
static void defaults(void) {
    raven_audio_close();
    raven_audio_set_output(1, 100);
    // Exact conversion counts: the rate control has its own scenarios.
    raven_audio_set_rate_control(0.0);
    raven_audio_set_speed(1.0);
    open_error = opens = 0;
    native_rate = 48000; format = AAUDIO_FORMAT_PCM_I16; channels = 2;
    burst = 192; buffer_capacity = buffer_size = 4096;
    start_error = starts = pauses = closes = waits = stuck = 0;
}

static int open_and_device_negotiation(void) {
    defaults();
    CHECK(raven_audio_open(44100));
    CHECK(requested_rate == AAUDIO_UNSPECIFIED);
    CHECK(requested_channels == 2 && requested_share == AAUDIO_SHARING_MODE_SHARED);
    CHECK(requested_perf == AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    CHECK(buffer_size == 384 && starts == 1);
    raven_audio_close(); CHECK(waits == 1 && closes == 1);
    CHECK(!raven_audio_open(NAN)); CHECK(!raven_audio_open(0)); CHECK(!raven_audio_open(200000));
    channels = 1; CHECK(!raven_audio_open(48000)); CHECK(closes == 2);
    channels = 2; native_rate = 0; CHECK(!raven_audio_open(48000));
    native_rate = 48000; start_error = AAUDIO_ERROR_DISCONNECTED;
    CHECK(!raven_audio_open(48000)); CHECK(!raven_aaudio_running());
    return 1;
}

static int rates_chunks_and_silence(void) {
    defaults(); CHECK(raven_audio_open(44100));
    int16_t samples[882 * 2], out[1200 * 2];
    for (int i = 0; i < 882; ++i) { samples[i * 2] = (int16_t)(1000 + i); samples[i * 2 + 1] = (int16_t)(-1000 - i); }
    raven_audio_write(samples, 441); raven_audio_write(samples + 882, 441);
    memset(out, 0x7f, sizeof(out)); consume(out, 1200);
    int count = 0;
    for (int i = 0; i < 1200; ++i) {
        if (out[i * 2]) { CHECK(out[i * 2 + 1] == -out[i * 2]); count++; }
        else CHECK(out[i * 2 + 1] == 0);
    }
    CHECK(count == 959);
    CHECK(out[0] == 1000 && out[1] == -1000);
    raven_audio_close(); native_rate = 44100; CHECK(raven_audio_open(48000));
    raven_audio_write(samples, 480); consume(out, 1200); count = 0;
    for (int i = 0; i < 1200; ++i) if (out[i * 2]) count++;
    CHECK(count == 441);
    return 1;
}

static int pause_discards_old_audio_without_erasing_fresh_samples(void) {
    defaults(); int rate = 0; CHECK(raven_aaudio_open(&rate));
    raven_aaudio_push(11, -11); raven_aaudio_push(12, -12);
    raven_aaudio_set_running(0); raven_aaudio_push(99, -99);
    raven_aaudio_set_running(1); raven_aaudio_push(21, -21); raven_aaudio_push(22, -22);
    int16_t out[8]; consume(out, 4);
    CHECK(out[0] == 21 && out[1] == -21 && out[2] == 22 && out[3] == -22);
    CHECK(out[4] == 0 && out[5] == 0 && out[6] == 0 && out[7] == 0);
    raven_aaudio_set_running(0); consume(out, 4);
    for (int i = 0; i < 8; ++i) CHECK(out[i] == 0);
    raven_aaudio_set_running(1); raven_aaudio_push(31, -31); consume(out, 4);
    CHECK(out[0] == 31 && out[1] == -31);
    int before = starts; raven_aaudio_set_running(1); CHECK(starts == before);
    return 1;
}

static int bounded_queue_wrap_and_stop(void) {
    defaults(); int rate; CHECK(raven_aaudio_open(&rate));
    for (int i = 0; i < 40000; ++i) raven_aaudio_push(1, -1);
    int16_t out[4096 * 2]; consume(out, 4096);
    int count = 0;
    for (int i = 0; i < 4096; ++i) if (out[i * 2]) { CHECK(out[i * 2 + 1] == -1); count++; }
    CHECK(count == 2688); // 64ms at 48kHz, minus the 384 device frames.
    for (int lap = 0; lap < 300; ++lap) {
        for (int i = 0; i < 256; ++i) raven_aaudio_push((int16_t)(i + 1), (int16_t)(-i - 1));
        consume(out, 256);
        for (int i = 0; i < 256; ++i) CHECK(out[i * 2] == i + 1 && out[i * 2 + 1] == -i - 1);
    }
    stuck = 1; waits = 0; raven_audio_close(); CHECK(waits == 4 && closes == 1);
    defaults(); burst = -1; buffer_capacity = 192; CHECK(raven_audio_open(48000)); CHECK(buffer_size == 192);
    return 1;
}

static int output_gain_mute_pause_and_reopen(void) {
    defaults(); CHECK(raven_audio_open(48000));
    const int16_t constant[] = {1000, -1000, 1000, -1000, 1000, -1000};
    int16_t out[8];
    raven_audio_write(constant, 3); // Pending old gain must not leak forward.
    raven_audio_set_output(1, 50);
    raven_audio_write(constant, 3); consume(out, 3);
    for (int i = 0; i < 3; ++i) CHECK(out[i * 2] == 500 && out[i * 2 + 1] == -500);
    const int before_starts = starts, before_pauses = pauses;
    raven_audio_write(constant, 3);
    raven_audio_set_output(0, 50);
    raven_audio_write(constant, 3); consume(out, 3);
    for (int i = 0; i < 6; ++i) CHECK(out[i] == 0);
    CHECK(raven_aaudio_running() && starts == before_starts && pauses == before_pauses);
    raven_audio_set_running(0);
    raven_audio_set_output(1, 50); // Output preference cannot resume lifecycle.
    CHECK(!raven_aaudio_running() && starts == before_starts);
    raven_audio_write(constant, 3); consume(out, 3);
    for (int i = 0; i < 6; ++i) CHECK(out[i] == 0);
    raven_audio_set_running(1);
    raven_audio_write(constant, 3); consume(out, 3);
    CHECK(out[0] == 500 && out[1] == -500);
    raven_audio_close(); CHECK(raven_audio_open(48000));
    raven_audio_write(constant, 3); consume(out, 3);
    CHECK(out[0] == 500 && out[1] == -500); // Preference survives stream reopen.
    raven_audio_set_output(1, 0); raven_audio_write(constant, 3); consume(out, 3);
    for (int i = 0; i < 6; ++i) CHECK(out[i] == 0);
    raven_audio_set_output(1, -200); raven_audio_write(constant, 3); consume(out, 3);
    for (int i = 0; i < 6; ++i) CHECK(out[i] == 0);
    raven_audio_set_output(1, 1000); raven_audio_write(constant, 3); consume(out, 3);
    CHECK(out[0] == 1000 && out[1] == -1000);

    raven_audio_close(); CHECK(raven_audio_open(48000));
    raven_audio_set_output(1, 50);
    const int16_t extremes[] = {32767, -32768, 1001, -1001, -1, 1, 0, 0};
    raven_audio_write(extremes, 4); consume(out, 4);
    CHECK(out[0] == 16384 && out[1] == -16384);
    CHECK(out[2] == 501 && out[3] == -501);
    CHECK(out[4] == -1 && out[5] == 1 && out[6] == 0 && out[7] == 0);
    return 1;
}

static _Atomic int consumer_stop;
static int consumer_bad;
static size_t consumer_samples;
static void *concurrent_consumer(void *unused) {
    (void)unused;
    int16_t out[128 * 2];
    while (!atomic_load_explicit(&consumer_stop, memory_order_acquire)) {
        consume(out, 128);
        for (int i = 0; i < 128; ++i) {
            if (!((out[i * 2] == 0 && out[i * 2 + 1] == 0) ||
                    (out[i * 2] == 1000 && out[i * 2 + 1] == -1000) ||
                    (out[i * 2] == 500 && out[i * 2 + 1] == -500))) consumer_bad = 1;
            if (out[i * 2]) consumer_samples++;
        }
        sched_yield();
    }
    return NULL;
}
static int producer_and_callback_run_concurrently(void) {
    defaults(); CHECK(raven_audio_open(48000));
    atomic_store(&consumer_stop, 0); consumer_bad = 0; consumer_samples = 0;
    pthread_t consumer;
    CHECK(pthread_create(&consumer, NULL, concurrent_consumer, NULL) == 0);
    int16_t samples[64 * 2];
    for (int i = 0; i < 64; ++i) { samples[i * 2] = 1000; samples[i * 2 + 1] = -1000; }
    for (int i = 0; i < 4096; ++i) {
        if (i % 32 == 0) { raven_audio_set_running(0); raven_audio_set_running(1); }
        if (i % 16 == 0) raven_audio_set_output((i / 16) % 3 != 0, (i / 16) % 2 ? 50 : 100);
        raven_audio_write(samples, 64);
        sched_yield();
    }
    atomic_store_explicit(&consumer_stop, 1, memory_order_release);
    CHECK(pthread_join(consumer, NULL) == 0);
    CHECK(!consumer_bad && consumer_samples > 0);
    return 1;
}

static int rate_adjust_steers_towards_a_half_full_queue(void) {
    CHECK(raven_audio_rate_adjust(0, 2688, 0.005) == 1.005);
    CHECK(raven_audio_rate_adjust(2688, 2688, 0.005) == 0.995);
    CHECK(raven_audio_rate_adjust(1344, 2688, 0.005) == 1.0);
    CHECK(raven_audio_rate_adjust(99999, 2688, 0.005) == 0.995);
    CHECK(raven_audio_rate_adjust(0, 0, 0.005) == 1.0);
    CHECK(raven_audio_rate_adjust(0, 2688, 0.0) == 1.0);
    return 1;
}

/* A device whose clock runs `consumed`/800 against the emulation's: 800 core
 * frames per draw at 48 kHz, the device taking what it takes. Returns the
 * frames of silence the device had to play after the first draws. */
static int device_clock(double consumed, uint32_t *final_fill) {
    CHECK(raven_audio_open(48000));
    int16_t samples[800 * 2], out[900 * 2];
    for (int i = 0; i < 800; ++i) { samples[i * 2] = 1000; samples[i * 2 + 1] = -1000; }
    double owed = 0.0;
    int silence = 0;
    for (int draw = 0; draw < 3000; ++draw) {
        raven_audio_write(samples, 800);
        owed += consumed;
        const int take = (int)owed;
        owed -= take;
        consume(out, take);
        if (draw >= 10) for (int i = 0; i < take; ++i) if (!out[i * 2]) silence++;
    }
    raven_aaudio_fill(final_fill, NULL);
    return silence;
}

static int rate_control_absorbs_a_device_clock_drift(void) {
    uint32_t fill = 0, limit = 0;
    // The device 0.2 % faster, far more than two real clocks differ: without
    // control the queue runs dry, crackling.
    defaults();
    CHECK(device_clock(801.6, &fill) > 0);
    defaults(); raven_audio_set_rate_control(RAVEN_AUDIO_RATE_CONTROL);
    CHECK(device_clock(801.6, &fill) == 0);
    raven_aaudio_fill(NULL, &limit);
    CHECK(fill > 0 && fill < limit);
    // The device 0.2 % slower: without control the queue fills up and
    // drops samples; with it, it stays below its limit.
    defaults();
    device_clock(798.4, &fill); raven_aaudio_fill(NULL, &limit);
    CHECK(fill >= limit - 800);
    defaults(); raven_audio_set_rate_control(RAVEN_AUDIO_RATE_CONTROL);
    CHECK(device_clock(798.4, &fill) == 0);
    raven_aaudio_fill(NULL, &limit);
    CHECK(fill > limit / 2 && fill < limit - 800);
    return 1;
}

static int the_display_speed_reaches_the_resampler(void) {
    defaults(); CHECK(raven_audio_open(48000));
    // Locked on a 60 Hz screen, a GBA game runs 0.46 % fast: its 48000 core
    // samples a second must still fill only 48000 device samples.
    raven_audio_set_speed(60.0 / 59.727500569606);
    int16_t samples[480 * 2], out[600 * 2];
    for (int i = 0; i < 480; ++i) { samples[i * 2] = 1000; samples[i * 2 + 1] = -1000; }
    int produced = 0;
    for (int chunk = 0; chunk < 100; ++chunk) {
        raven_audio_write(samples, 480);
        uint32_t queued = 0; raven_aaudio_fill(&queued, NULL);
        consume(out, (int)queued);
        for (uint32_t i = 0; i < queued; ++i) if (out[i * 2]) produced++;
    }
    CHECK(produced >= 47779 && produced <= 47783); // 48000 / 1.004562
    raven_audio_set_speed(5.0); // nonsense: the console's own speed
    raven_audio_write(samples, 480);
    uint32_t queued = 0; raven_aaudio_fill(&queued, NULL);
    CHECK(queued >= 479 && queued <= 481);
    return 1;
}

static int a_lost_device_is_reopened_with_its_pause_state(void) {
    defaults(); CHECK(raven_audio_open(44100));
    CHECK(!raven_aaudio_lost() && !raven_audio_recover());
    void *first = error_user;
    error_callback(&fake_stream, first, AAUDIO_ERROR_DISCONNECTED);
    CHECK(raven_aaudio_lost());
    const int before_opens = opens, before_closes = closes;
    CHECK(raven_audio_recover());
    CHECK(opens == before_opens + 1 && closes == before_closes + 1);
    CHECK(!raven_aaudio_lost() && raven_aaudio_running());
    // A late report from the stream already replaced changes nothing.
    error_callback(&fake_stream, first, AAUDIO_ERROR_DISCONNECTED);
    CHECK(!raven_aaudio_lost() && !raven_audio_recover());
    // Paused when lost: paused when reopened.
    raven_audio_set_running(0);
    error_callback(&fake_stream, error_user, AAUDIO_ERROR_DISCONNECTED);
    CHECK(raven_audio_recover());
    CHECK(!raven_aaudio_running());
    raven_audio_set_running(1);
    CHECK(raven_aaudio_running());
    // No device yet: try again about once a second, not on every draw.
    error_callback(&fake_stream, error_user, AAUDIO_ERROR_DISCONNECTED);
    open_error = AAUDIO_ERROR_DISCONNECTED;
    const int failed_from = opens;
    CHECK(!raven_audio_recover());
    for (int draw = 0; draw < 60; ++draw) CHECK(!raven_audio_recover());
    CHECK(opens == failed_from + 1);
    open_error = 0;
    CHECK(raven_audio_recover());
    CHECK(opens == failed_from + 2 && raven_aaudio_running());
    // Sound flows again through the reopened stream.
    const int16_t constant[] = {700, -700, 700, -700};
    int16_t out[4];
    raven_audio_write(constant, 2); consume(out, 1);
    CHECK(out[0] == 700 && out[1] == -700);
    // A closed session is not reopened.
    error_callback(&fake_stream, error_user, AAUDIO_ERROR_DISCONNECTED);
    raven_audio_close();
    const int closed_opens = opens;
    CHECK(!raven_audio_recover() && opens == closed_opens);
    return 1;
}

int main(void) {
    int okay = open_and_device_negotiation() && rates_chunks_and_silence() &&
        pause_discards_old_audio_without_erasing_fresh_samples() && bounded_queue_wrap_and_stop() &&
        output_gain_mute_pause_and_reopen() &&
        producer_and_callback_run_concurrently() &&
        rate_adjust_steers_towards_a_half_full_queue() &&
        rate_control_absorbs_a_device_clock_drift() &&
        the_display_speed_reaches_the_resampler() &&
        a_lost_device_is_reopened_with_its_pause_state();
    raven_audio_close();
    if (!okay) return 1;
    puts("AAudio subset: 10 scenarios passed (negotiation, resampling, pause, bounds, output settings, SPSC, "
         "rate control, display speed, device loss)");
    return 0;
}
