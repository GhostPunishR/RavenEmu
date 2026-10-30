/* The phone's sensors for RavenCore's cartridge sensors (RavenEmu code, AGPLv3),
 * as the beta's CartridgeSensors reads them: the raw accelerometer (the
 * cartridges measure motion too), the gyroscope and the light sensor, only
 * those the core asks for, and the accelerometer turned to the screen. */
#include "sensor_driver.h"

#include <android/looper.h>
#include <android/sensor.h>
#include <libretro.h>
#include <stddef.h>

#define QUEUE_IDENT 3
#define EVENT_BATCH 16

enum { ACCELEROMETER, GYROSCOPE, LIGHT, KINDS };
static const int sensor_types[KINDS] = {ASENSOR_TYPE_ACCELEROMETER, ASENSOR_TYPE_GYROSCOPE, ASENSOR_TYPE_LIGHT};

static ASensorManager *manager;
static ASensorEventQueue *queue;
static const ASensor *sensors[KINDS];
static int wanted[KINDS], enabled[KINDS], running = 1, rotation;
static unsigned rates[KINDS];
static float acceleration[3], turning[3], lux;

static int open_queue(void) {
    if (queue) return 1;
    /* Android 8 and later: without a package name, the system finds ours. */
    if (!manager) manager = ASensorManager_getInstanceForPackage(NULL);
    if (!manager) return 0;
    ALooper *looper = ALooper_forThread();
    if (!looper) looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    if (!looper) return 0;
    queue = ASensorManager_createEventQueue(manager, looper, QUEUE_IDENT, NULL, NULL);
    return queue != NULL;
}

static const ASensor *sensor_for(int kind) {
    if (!sensors[kind] && open_queue())
        sensors[kind] = ASensorManager_getDefaultSensor(manager, sensor_types[kind]);
    return sensors[kind];
}

static void rest(int kind) {
    if (kind == ACCELEROMETER) acceleration[0] = acceleration[1] = acceleration[2] = 0.0f;
    if (kind == GYROSCOPE) turning[0] = turning[1] = turning[2] = 0.0f;
    if (kind == LIGHT) lux = 0.0f;
}

/* On when the core wants it and the game runs; off otherwise, at rest. */
static void apply(int kind) {
    const int should = wanted[kind] && running && sensor_for(kind);
    if (should == enabled[kind]) return;
    if (should) {
        const unsigned rate = rates[kind] ? rates[kind] : 60;
        /* No batching: each event as it comes, for the next frame. */
        if (ASensorEventQueue_registerSensor(queue, sensors[kind], (int32_t)(1000000u / rate), 0) < 0) return;
        enabled[kind] = 1;
    } else {
        if (queue && sensors[kind]) ASensorEventQueue_disableSensor(queue, sensors[kind]);
        enabled[kind] = 0;
        rest(kind);
    }
}

int raven_sensors_set_state(unsigned action, unsigned rate) {
    const int kind = (int)(action / 2);
    if (kind >= KINDS) return 0;
    if (action % 2) {
        wanted[kind] = 0;
        apply(kind);
        return 1;
    }
    if (!sensor_for(kind)) return 0;
    wanted[kind] = 1;
    rates[kind] = rate > 1000 ? 1000 : rate;
    apply(kind);
    /* Paused, it comes on with the game. */
    return enabled[kind] || !running;
}

void raven_sensors_poll(void) {
    if (!queue) return;
    ASensorEvent events[EVENT_BATCH];
    ssize_t count;
    while ((count = ASensorEventQueue_getEvents(queue, events, EVENT_BATCH)) > 0) {
        for (ssize_t i = 0; i < count; ++i) {
            const ASensorEvent *event = &events[i];
            if (event->type == ASENSOR_TYPE_ACCELEROMETER && enabled[ACCELEROMETER]) {
                acceleration[0] = event->acceleration.x;
                acceleration[1] = event->acceleration.y;
                acceleration[2] = event->acceleration.z;
            } else if (event->type == ASENSOR_TYPE_GYROSCOPE && enabled[GYROSCOPE]) {
                turning[0] = event->vector.x;
                turning[1] = event->vector.y;
                turning[2] = event->vector.z;
            } else if (event->type == ASENSOR_TYPE_LIGHT && enabled[LIGHT]) {
                lux = event->light;
            }
        }
    }
}

/* The device's x and y along the screen as displayed, as the beta does. */
static float screen_x(const float *v) {
    switch (rotation) {
        case 1: return -v[1];
        case 2: return -v[0];
        case 3: return v[1];
        default: return v[0];
    }
}

static float screen_y(const float *v) {
    switch (rotation) {
        case 1: return v[0];
        case 2: return -v[1];
        case 3: return -v[0];
        default: return v[1];
    }
}

float raven_sensors_read(unsigned id) {
    switch (id) {
        case RETRO_SENSOR_ACCELEROMETER_X: return screen_x(acceleration);
        case RETRO_SENSOR_ACCELEROMETER_Y: return screen_y(acceleration);
        case RETRO_SENSOR_ACCELEROMETER_Z: return acceleration[2];
        case RETRO_SENSOR_GYROSCOPE_X: return screen_x(turning);
        case RETRO_SENSOR_GYROSCOPE_Y: return screen_y(turning);
        case RETRO_SENSOR_GYROSCOPE_Z: return turning[2];
        case RETRO_SENSOR_ILLUMINANCE: return lux;
        default: return 0.0f;
    }
}

void raven_sensors_set_rotation(int value) {
    rotation = value >= 0 && value <= 3 ? value : 0;
}

void raven_sensors_set_active(int active) {
    running = !!active;
    for (int kind = 0; kind < KINDS; ++kind) apply(kind);
}

void raven_sensors_close(void) {
    for (int kind = 0; kind < KINDS; ++kind) {
        wanted[kind] = 0;
        apply(kind);
        sensors[kind] = NULL;
        rates[kind] = 0;
    }
    if (queue && manager) ASensorManager_destroyEventQueue(manager, queue);
    queue = NULL;
}
