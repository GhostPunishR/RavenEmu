#include "native_audio.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <math.h>
#include <stdatomic.h>
#include <string.h>

#define TAG "RavenNativeAudio"
#define AUDIO_CAPACITY 32768u /* stereo frames, power of two */
#define AUDIO_MASK (AUDIO_CAPACITY - 1u)

static AAudioStream *stream;
static int16_t queue[AUDIO_CAPACITY * 2u];
static _Atomic uint32_t read_position;
static _Atomic uint32_t write_position;
static double input_rate, output_rate, phase;
static int16_t previous[2];
static int have_previous;

static aaudio_data_callback_result_t data_callback(
    AAudioStream *unused, void *userdata, void *audio, int32_t num_frames) {
    (void)unused;
    (void)userdata;
    int16_t *destination = (int16_t *)audio;
    uint32_t read = atomic_load_explicit(&read_position, memory_order_relaxed);
    const uint32_t write = atomic_load_explicit(&write_position, memory_order_acquire);
    uint32_t available = write - read;
    if (available > AUDIO_CAPACITY) available = AUDIO_CAPACITY;
    for (int32_t i = 0; i < num_frames; ++i) {
        if (available) {
            const uint32_t index = (read & AUDIO_MASK) * 2u;
            destination[2 * i] = queue[index];
            destination[2 * i + 1] = queue[index + 1];
            ++read;
            --available;
        } else {
            destination[2 * i] = 0;
            destination[2 * i + 1] = 0;
        }
    }
    atomic_store_explicit(&read_position, read, memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void enqueue(int16_t left, int16_t right) {
    uint32_t write = atomic_load_explicit(&write_position, memory_order_relaxed);
    const uint32_t read = atomic_load_explicit(&read_position, memory_order_acquire);
    if (write - read >= AUDIO_CAPACITY) return; /* Never block emulation. */
    queue[(write & AUDIO_MASK) * 2u] = left;
    queue[(write & AUDIO_MASK) * 2u + 1] = right;
    atomic_store_explicit(&write_position, write + 1, memory_order_release);
}

static int16_t interpolate(int16_t before, int16_t after, double fraction) {
    double value = (double)before + ((double)after - before) * fraction;
    if (value > 32767.0) value = 32767.0;
    if (value < -32768.0) value = -32768.0;
    return (int16_t)lrint(value);
}

void raven_audio_write(const int16_t *stereo, size_t frames) {
    if (!stream || !stereo || input_rate <= 0.0 || output_rate <= 0.0) return;
    const double step = input_rate / output_rate;
    for (size_t i = 0; i < frames; ++i) {
        const int16_t left = stereo[2 * i], right = stereo[2 * i + 1];
        if (!have_previous) {
            previous[0] = left; previous[1] = right;
            have_previous = 1;
            continue;
        }
        while (phase < 1.0) {
            enqueue(interpolate(previous[0], left, phase),
                    interpolate(previous[1], right, phase));
            phase += step;
        }
        phase -= 1.0;
        previous[0] = left; previous[1] = right;
    }
}

int raven_audio_open(double requested_rate) {
    raven_audio_close();
    if (!(requested_rate >= 8000.0 && requested_rate <= 192000.0)) return 0;
    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t error = AAudio_createStreamBuilder(&builder);
    if (error != AAUDIO_OK || !builder) return 0;
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, 48000);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setDataCallback(builder, data_callback, NULL);
    error = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (error != AAUDIO_OK || !stream) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "AAudio open failed: %d", error);
        stream = NULL;
        return 0;
    }
    if (AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_I16 ||
        AAudioStream_getChannelCount(stream) != 2) {
        raven_audio_close();
        return 0;
    }
    input_rate = requested_rate;
    output_rate = (double)AAudioStream_getSampleRate(stream);
    atomic_store(&read_position, 0u);
    atomic_store(&write_position, 0u);
    phase = 0.0;
    have_previous = 0;
    error = AAudioStream_requestStart(stream);
    if (error != AAUDIO_OK) {
        raven_audio_close();
        return 0;
    }
    return 1;
}

void raven_audio_set_running(int running) {
    if (!stream) return;
    if (running) {
        /* Drop stale buffered audio after the app resumes. */
        uint32_t write = atomic_load_explicit(&write_position, memory_order_acquire);
        atomic_store_explicit(&read_position, write, memory_order_release);
        AAudioStream_requestStart(stream);
    } else {
        AAudioStream_requestStop(stream);
    }
}

void raven_audio_close(void) {
    if (stream) {
        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
        stream = NULL;
    }
    input_rate = output_rate = 0.0;
    phase = 0.0;
    have_previous = 0;
    atomic_store(&read_position, 0u);
    atomic_store(&write_position, 0u);
}
