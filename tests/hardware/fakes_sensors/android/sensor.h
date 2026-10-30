#ifndef RAVEN_TEST_SENSOR_H
#define RAVEN_TEST_SENSOR_H
/* The few NDK sensor declarations the driver uses, with the same names. */
#include <android/looper.h>
#include <stdint.h>
#include <sys/types.h>
typedef struct ASensorManager ASensorManager;
typedef struct ASensorEventQueue ASensorEventQueue;
typedef struct ASensor ASensor;
typedef struct ASensorVector { float x, y, z; } ASensorVector;
typedef struct ASensorEvent {
    int32_t type;
    ASensorVector acceleration, vector;
    float light;
} ASensorEvent;
#define ASENSOR_TYPE_ACCELEROMETER 1
#define ASENSOR_TYPE_GYROSCOPE 4
#define ASENSOR_TYPE_LIGHT 5
ASensorManager *ASensorManager_getInstanceForPackage(const char *package);
const ASensor *ASensorManager_getDefaultSensor(ASensorManager *, int type);
ASensorEventQueue *ASensorManager_createEventQueue(ASensorManager *, ALooper *, int ident,
                                                   ALooper_callbackFunc, void *data);
int ASensorManager_destroyEventQueue(ASensorManager *, ASensorEventQueue *);
int ASensorEventQueue_registerSensor(ASensorEventQueue *, const ASensor *, int32_t period_us,
                                     int64_t max_latency_us);
int ASensorEventQueue_disableSensor(ASensorEventQueue *, const ASensor *);
ssize_t ASensorEventQueue_getEvents(ASensorEventQueue *, ASensorEvent *, size_t count);
#endif
