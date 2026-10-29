/*  RetroArch - A frontend for libretro.
 *  Copyright (C) 2026 - The RetroArch team
 *
 *  RetroArch is free software: you can redistribute it and/or modify it under the terms
 *  of the GNU General Public License as published by the Free Software Found-
 *  ation, either version 3 of the License, or (at your option) any later version.
 *
 *  RetroArch is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR
 *  PURPOSE.  See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with RetroArch.
 *  If not, see <http://www.gnu.org/licenses/>.
 */

/* SPDX-License-Identifier: GPL-3.0-or-later
 * Modified for RavenEmu by GhostPunishR, 2026-10-29.
 * Source: RetroArch 7986022baf6822677ad2cec4bc215069e89bd8ce,
 * audio/drivers/aaudio.c (aaudio_data_cb, aaudio_open/open_pcm,
 * aaudio_init device/ring sizing, and aaudio_close_stream).
 * https://github.com/libretro/RetroArch/blob/7986022baf6822677ad2cec4bc215069e89bd8ce/audio/drivers/aaudio.c
 *
 * Adaptation: link Android's API26 AAudio directly; retain PCM16 stereo only;
 * replace RetroArch's frontend settings/eventcount/retro_spsc dependencies
 * with a bounded C11 queue. The emulation producer never waits for room.
 * Lifecycle purges are executed by the consumer, which alone owns its cursor.
 * No RetroArch resampler, multichannel, AC3 or device-selection code is used.
 */
#include "retroarch_aaudio.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <stddef.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>

#define TAG "RavenRetroArchAAudio"
#define AUDIO_CAPACITY 32768u
#define AUDIO_MASK (AUDIO_CAPACITY - 1u)
#define AUDIO_LATENCY_MS 64u
#define STOP_TIMEOUT_NS INT64_C(200000000)
#define CONTROL_RUNNING (UINT64_C(1) << 32u)

_Static_assert(ATOMIC_LLONG_LOCK_FREE == 2, "Audio control requires lock-free 64-bit atomics");

static AAudioStream *stream;
static int running; /* Emulation owner only, never read by the callback. */
static uint32_t ring_limit;
static int16_t queue[AUDIO_CAPACITY * 2u];
static _Atomic uint32_t read_position;
static _Atomic uint32_t write_position;
/* Low32: discard through this write cursor; bit32: running; high31: change id.
 * One release/acquire snapshot keeps the purge target and mode coherent. */
static _Atomic uint64_t control;
static uint32_t control_generation; /* owner only */
static uint64_t callback_control;   /* consumer only */

static void publish_control(int active) {
    uint32_t target = atomic_load_explicit(&write_position, memory_order_acquire);
    ++control_generation;
    uint64_t value = ((uint64_t)control_generation << 33u) | target;
    if (active) value |= CONTROL_RUNNING;
    atomic_store_explicit(&control, value, memory_order_release);
}

/* Adapted from RetroArch's aaudio_data_cb: read what is present, pad the rest
 * with silence, and never allocate, wait or call the platform from here. */
static aaudio_data_callback_result_t data_callback(
    AAudioStream *unused, void *userdata, void *audio, int32_t frames) {
    (void)unused; (void)userdata;
    if (!audio || frames <= 0) return AAUDIO_CALLBACK_RESULT_CONTINUE;
    int16_t *out = audio;
    const uint64_t command = atomic_load_explicit(&control, memory_order_acquire);
    uint32_t read = atomic_load_explicit(&read_position, memory_order_relaxed);
    const uint32_t write = atomic_load_explicit(&write_position, memory_order_acquire);
    uint32_t available = write - read;
    if (command != callback_control) {
        uint32_t target = (uint32_t)command;
        uint32_t discard = target - read;
        // The consumer may already have passed a lifecycle purge target.
        if (discard <= available) read = target;
        callback_control = command;
        available = write - read;
    }
    if (!(command & CONTROL_RUNNING)) {
        atomic_store_explicit(&read_position, write, memory_order_release);
        memset(out, 0, (size_t)frames * 2u * sizeof(*out));
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    uint32_t count = available < (uint32_t)frames ? available : (uint32_t)frames;
    uint32_t first = AUDIO_CAPACITY - (read & AUDIO_MASK);
    if (first > count) first = count;
    memcpy(out, queue + (read & AUDIO_MASK) * 2u, (size_t)first * 2u * sizeof(*out));
    memcpy(out + first * 2u, queue, (size_t)(count - first) * 2u * sizeof(*out));
    if (count < (uint32_t)frames)
        memset(out + count * 2u, 0, ((size_t)frames - count) * 2u * sizeof(*out));
    atomic_store_explicit(&read_position, read + count, memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void raven_aaudio_push(int16_t left, int16_t right) {
    if (!stream || !running) return;
    uint32_t write = atomic_load_explicit(&write_position, memory_order_relaxed);
    uint32_t read = atomic_load_explicit(&read_position, memory_order_acquire);
    if (write - read >= ring_limit) return;
    queue[(write & AUDIO_MASK) * 2u] = left;
    queue[(write & AUDIO_MASK) * 2u + 1] = right;
    atomic_store_explicit(&write_position, write + 1u, memory_order_release);
}

/* Adapted from aaudio_close_stream: bounded stop-state wait before close.
 * AAudioStream_close completes callback teardown before queue cursors reset. */
static void close_stream(AAudioStream *closing) {
    if (!closing) return;
    AAudioStream_requestStop(closing);
    aaudio_stream_state_t state = AAudioStream_getState(closing);
    for (int i = 0; i < 4 && state != AAUDIO_STREAM_STATE_STOPPED &&
            state != AAUDIO_STREAM_STATE_DISCONNECTED && state != AAUDIO_STREAM_STATE_CLOSED; ++i) {
        aaudio_stream_state_t next = state;
        if (AAudioStream_waitForStateChange(closing, state, &next, STOP_TIMEOUT_NS / 4) != AAUDIO_OK)
            break;
        state = next;
    }
    AAudioStream_close(closing);
}

int raven_aaudio_open(int *sample_rate) {
    raven_aaudio_close();
    if (!sample_rate) return 0;
    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || !builder) return 0;

    /* Adapted from aaudio_open/open_pcm. The native rate avoids Android doing
     * another resample after RavenEmu's own core-to-device conversion. */
    AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setSampleRate(builder, AAUDIO_UNSPECIFIED);
    AAudioStreamBuilder_setDataCallback(builder, data_callback, NULL);
    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || !stream) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "AAudio open failed: %d", result);
        stream = NULL;
        return 0;
    }
    int32_t rate = AAudioStream_getSampleRate(stream);
    if (AAudioStream_getFormat(stream) != AAUDIO_FORMAT_PCM_I16 ||
            AAudioStream_getChannelCount(stream) != 2 || rate < 8000 || rate > 192000) {
        raven_aaudio_close();
        return 0;
    }

    /* Adapted from aaudio_init: two device bursts, then enough bounded ring
     * capacity for the remaining latency budget, at least two more bursts. */
    int32_t burst = AAudioStream_getFramesPerBurst(stream);
    if (burst <= 0 || burst > (int32_t)(AUDIO_CAPACITY / 2u)) burst = 192;
    int32_t wanted = burst * 2;
    int32_t capacity = AAudioStream_getBufferCapacityInFrames(stream);
    if (wanted > capacity) wanted = capacity;
    if (wanted > 0) AAudioStream_setBufferSizeInFrames(stream, wanted);
    int32_t device_frames = AAudioStream_getBufferSizeInFrames(stream);
    if (device_frames < 0) device_frames = 0;
    size_t frames = (size_t)rate * AUDIO_LATENCY_MS / 1000u;
    frames = frames > (size_t)device_frames ? frames - (size_t)device_frames : 0;
    if (frames < (size_t)burst * 2u) frames = (size_t)burst * 2u;
    if (frames > AUDIO_CAPACITY) frames = AUDIO_CAPACITY;
    ring_limit = (uint32_t)frames;
    atomic_store_explicit(&read_position, 0u, memory_order_relaxed);
    atomic_store_explicit(&write_position, 0u, memory_order_relaxed);
    callback_control = 0;
    control_generation = 0;
    publish_control(1);
    running = 1;
    result = AAudioStream_requestStart(stream);
    if (result != AAUDIO_OK) {
        raven_aaudio_close();
        return 0;
    }
    *sample_rate = rate;
    return 1;
}

int raven_aaudio_running(void) { return stream && running; }

void raven_aaudio_set_running(int active) {
    active = !!active;
    if (!stream || active == running) return;
    publish_control(active);
    running = active;
    aaudio_result_t result = active ? AAudioStream_requestStart(stream) : AAudioStream_requestPause(stream);
    if (result != AAUDIO_OK) {
        running = 0;
        publish_control(0);
        __android_log_print(ANDROID_LOG_WARN, TAG, "AAudio lifecycle request failed: %d", result);
    }
}

void raven_aaudio_close(void) {
    if (stream) {
        running = 0;
        publish_control(0);
        close_stream(stream);
        stream = NULL;
    }
    // Only after callback teardown; lifecycle never writes its live cursor.
    atomic_store_explicit(&read_position, 0u, memory_order_relaxed);
    atomic_store_explicit(&write_position, 0u, memory_order_relaxed);
    atomic_store_explicit(&control, 0u, memory_order_relaxed);
    control_generation = 0;
    callback_control = 0;
    ring_limit = 0;
}
