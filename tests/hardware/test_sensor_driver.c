#include "sensor_driver.h"

#include <android/sensor.h>
#include <libretro.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CHECK(c) do { if (!(c)) { \
    fprintf(stderr, "%s:%d: %s\n", __FILE__, __LINE__, #c); exit(1); \
} } while (0)

/* A fake phone: an accelerometer and a light sensor, no gyroscope unless asked. */
struct ALooper { int prepared; };
struct ASensorManager { int unused; };
struct ASensorEventQueue { int live; };
struct ASensor { int type; };

static struct ALooper looper;
static struct ASensorManager manager;
static struct ASensorEventQueue queue;
static struct ASensor accelerometer = {ASENSOR_TYPE_ACCELEROMETER}, gyroscope = {ASENSOR_TYPE_GYROSCOPE},
                      light = {ASENSOR_TYPE_LIGHT};
static int has_gyroscope, queues_created, queues_destroyed, prepare_flags, fail_register;
static int registered[8];
static int32_t last_period_us;
static int64_t last_latency_us;
static ASensorEvent pending[32];
static size_t pending_count;

ALooper *ALooper_forThread(void) { return looper.prepared ? &looper : NULL; }
ALooper *ALooper_prepare(int opts) { prepare_flags = opts; looper.prepared = 1; return &looper; }
ASensorManager *ASensorManager_getInstanceForPackage(const char *package) {
    CHECK(package == NULL);
    return &manager;
}
const ASensor *ASensorManager_getDefaultSensor(ASensorManager *m, int type) {
    CHECK(m == &manager);
    if (type == ASENSOR_TYPE_ACCELEROMETER) return &accelerometer;
    if (type == ASENSOR_TYPE_GYROSCOPE) return has_gyroscope ? &gyroscope : NULL;
    if (type == ASENSOR_TYPE_LIGHT) return &light;
    return NULL;
}
ASensorEventQueue *ASensorManager_createEventQueue(ASensorManager *m, ALooper *l, int ident,
                                                   ALooper_callbackFunc callback, void *data) {
    CHECK(m == &manager && l == &looper && l->prepared && ident > 0 && !callback && !data);
    CHECK(!queue.live);
    queue.live = 1;
    ++queues_created;
    return &queue;
}
int ASensorManager_destroyEventQueue(ASensorManager *m, ASensorEventQueue *q) {
    CHECK(m == &manager && q == &queue && q->live);
    for (int type = 0; type < 8; ++type) CHECK(!registered[type]);
    q->live = 0;
    ++queues_destroyed;
    return 0;
}
int ASensorEventQueue_registerSensor(ASensorEventQueue *q, const ASensor *sensor, int32_t period_us,
                                     int64_t latency_us) {
    CHECK(q == &queue && q->live && !registered[sensor->type]);
    if (fail_register) return -1;
    registered[sensor->type] = 1;
    last_period_us = period_us;
    last_latency_us = latency_us;
    return 0;
}
int ASensorEventQueue_disableSensor(ASensorEventQueue *q, const ASensor *sensor) {
    CHECK(q == &queue && q->live);
    registered[sensor->type] = 0;
    return 0;
}
ssize_t ASensorEventQueue_getEvents(ASensorEventQueue *q, ASensorEvent *events, size_t count) {
    CHECK(q == &queue && q->live);
    if (!pending_count) return -11; /* -EAGAIN, as the real queue */
    const size_t taken = pending_count < count ? pending_count : count;
    memcpy(events, pending, taken * sizeof(*events));
    memmove(pending, pending + taken, (pending_count - taken) * sizeof(*pending));
    pending_count -= taken;
    return (ssize_t)taken;
}

static void push_motion(int type, float x, float y, float z) {
    ASensorEvent event = {.type = type};
    if (type == ASENSOR_TYPE_ACCELEROMETER) event.acceleration = (ASensorVector){x, y, z};
    else event.vector = (ASensorVector){x, y, z};
    CHECK(pending_count < sizeof(pending) / sizeof(*pending));
    pending[pending_count++] = event;
}

static void push_light(float lux) {
    pending[pending_count++] = (ASensorEvent){.type = ASENSOR_TYPE_LIGHT, .light = lux};
}

static int near(float a, float b) { return fabsf(a - b) < 1e-6f; }

static void accelerometer_follows_the_screen(void) {
    CHECK(raven_sensors_set_state(RETRO_SENSOR_ACCELEROMETER_ENABLE, 60));
    CHECK(queues_created == 1 && prepare_flags == ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    CHECK(registered[ASENSOR_TYPE_ACCELEROMETER]);
    CHECK(last_period_us == 16666 && last_latency_us == 0);
    /* Nothing yet: at rest, as the core expects. */
    raven_sensors_poll();
    CHECK(raven_sensors_read(RETRO_SENSOR_ACCELEROMETER_X) == 0.0f);
    /* Twenty events in one frame (more than a batch): the latest wins. */
    for (int i = 0; i < 19; ++i) push_motion(ASENSOR_TYPE_ACCELEROMETER, 0.0f, 0.0f, 9.8f);
    push_motion(ASENSOR_TYPE_ACCELEROMETER, 1.0f, 2.0f, 9.5f);
    raven_sensors_poll();
    CHECK(pending_count == 0);
    /* Beta CartridgeSensors.screenX/screenY, a quarter turn at a time. */
    const float expected[4][2] = {{1.0f, 2.0f}, {-2.0f, 1.0f}, {-1.0f, -2.0f}, {2.0f, -1.0f}};
    for (int rotation = 0; rotation < 4; ++rotation) {
        raven_sensors_set_rotation(rotation);
        CHECK(near(raven_sensors_read(RETRO_SENSOR_ACCELEROMETER_X), expected[rotation][0]));
        CHECK(near(raven_sensors_read(RETRO_SENSOR_ACCELEROMETER_Y), expected[rotation][1]));
        CHECK(near(raven_sensors_read(RETRO_SENSOR_ACCELEROMETER_Z), 9.5f));
    }
    raven_sensors_set_rotation(7); /* nonsense: upright */
    CHECK(near(raven_sensors_read(RETRO_SENSOR_ACCELEROMETER_X), 1.0f));
    /* Readings the core never asked for stay at zero. */
    CHECK(raven_sensors_read(RETRO_SENSOR_ILLUMINANCE) == 0.0f);
    CHECK(raven_sensors_read(99) == 0.0f);
}

static void a_missing_sensor_is_refused_and_disabling_always_works(void) {
    CHECK(!raven_sensors_set_state(RETRO_SENSOR_GYROSCOPE_ENABLE, 60));
    CHECK(raven_sensors_set_state(RETRO_SENSOR_GYROSCOPE_DISABLE, 0));
    CHECK(!raven_sensors_set_state(RETRO_SENSOR_ILLUMINANCE_DISABLE + 1, 60));
    /* Stray gyroscope events while it is off change nothing. */
    push_motion(ASENSOR_TYPE_GYROSCOPE, 1.0f, 1.0f, 1.0f);
    raven_sensors_poll();
    CHECK(raven_sensors_read(RETRO_SENSOR_GYROSCOPE_Z) == 0.0f);
    CHECK(queues_created == 1);
}

static void a_paused_game_turns_them_off(void) {
    raven_sensors_set_active(0);
    CHECK(!registered[ASENSOR_TYPE_ACCELEROMETER]);
    CHECK(raven_sensors_read(RETRO_SENSOR_ACCELEROMETER_X) == 0.0f);
    /* An event already queued when it stopped is not taken for a tilt. */
    push_motion(ASENSOR_TYPE_ACCELEROMETER, 5.0f, 5.0f, 5.0f);
    raven_sensors_poll();
    CHECK(raven_sensors_read(RETRO_SENSOR_ACCELEROMETER_X) == 0.0f);
    /* Asked while paused: accepted, on with the game. */
    CHECK(raven_sensors_set_state(RETRO_SENSOR_ILLUMINANCE_ENABLE, 5000));
    CHECK(!registered[ASENSOR_TYPE_LIGHT]);
    raven_sensors_set_active(1);
    CHECK(registered[ASENSOR_TYPE_ACCELEROMETER] && registered[ASENSOR_TYPE_LIGHT]);
    CHECK(last_period_us == 1000); /* the rate is capped at 1 kHz */
    push_light(25000.0f);
    raven_sensors_poll();
    CHECK(near(raven_sensors_read(RETRO_SENSOR_ILLUMINANCE), 25000.0f));
    CHECK(raven_sensors_set_state(RETRO_SENSOR_ILLUMINANCE_DISABLE, 0));
    CHECK(!registered[ASENSOR_TYPE_LIGHT] && raven_sensors_read(RETRO_SENSOR_ILLUMINANCE) == 0.0f);
}

static void closing_the_game_releases_everything(void) {
    raven_sensors_close();
    CHECK(!registered[ASENSOR_TYPE_ACCELEROMETER] && queues_destroyed == 1 && !queue.live);
    CHECK(raven_sensors_read(RETRO_SENSOR_ACCELEROMETER_X) == 0.0f);
    raven_sensors_poll(); /* without a queue: nothing to read, no crash */
    raven_sensors_close();
    CHECK(queues_destroyed == 1);
    /* The next game, with a gyroscope this time: a fresh queue. */
    has_gyroscope = 1;
    raven_sensors_set_rotation(1);
    CHECK(raven_sensors_set_state(RETRO_SENSOR_GYROSCOPE_ENABLE, 60));
    CHECK(queues_created == 2 && registered[ASENSOR_TYPE_GYROSCOPE]);
    push_motion(ASENSOR_TYPE_GYROSCOPE, 0.5f, 0.25f, -3.0f);
    raven_sensors_poll();
    CHECK(near(raven_sensors_read(RETRO_SENSOR_GYROSCOPE_X), -0.25f));
    CHECK(near(raven_sensors_read(RETRO_SENSOR_GYROSCOPE_Y), 0.5f));
    CHECK(near(raven_sensors_read(RETRO_SENSOR_GYROSCOPE_Z), -3.0f));
    raven_sensors_close();
    CHECK(queues_destroyed == 2);
}

static void a_sensor_the_system_refuses_is_reported(void) {
    fail_register = 1;
    CHECK(!raven_sensors_set_state(RETRO_SENSOR_ACCELEROMETER_ENABLE, 60));
    fail_register = 0;
    CHECK(raven_sensors_set_state(RETRO_SENSOR_ACCELEROMETER_ENABLE, 60));
    raven_sensors_close();
}

int main(void) {
    accelerometer_follows_the_screen();
    a_missing_sensor_is_refused_and_disabling_always_works();
    a_paused_game_turns_them_off();
    closing_the_game_releases_everything();
    a_sensor_the_system_refuses_is_reported();
    puts("Sensor driver: 5 scenarios passed");
    return 0;
}
