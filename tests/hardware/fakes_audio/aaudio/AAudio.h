#ifndef RAVEN_TEST_AAUDIO_H
#define RAVEN_TEST_AAUDIO_H
#include <stdint.h>
typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;
typedef int32_t aaudio_result_t;
typedef int32_t aaudio_stream_state_t;
typedef int32_t aaudio_data_callback_result_t;
typedef aaudio_data_callback_result_t (*AAudioStream_dataCallback)(AAudioStream *, void *, void *, int32_t);
typedef void (*AAudioStream_errorCallback)(AAudioStream *, void *, aaudio_result_t);
#define AAUDIO_OK 0
#define AAUDIO_ERROR_DISCONNECTED (-899)
#define AAUDIO_UNSPECIFIED 0
#define AAUDIO_DIRECTION_OUTPUT 0
#define AAUDIO_FORMAT_PCM_I16 1
#define AAUDIO_SHARING_MODE_SHARED 1
#define AAUDIO_PERFORMANCE_MODE_LOW_LATENCY 12
#define AAUDIO_CALLBACK_RESULT_CONTINUE 0
#define AAUDIO_STREAM_STATE_STARTING 3
#define AAUDIO_STREAM_STATE_STARTED 4
#define AAUDIO_STREAM_STATE_PAUSED 6
#define AAUDIO_STREAM_STATE_STOPPING 9
#define AAUDIO_STREAM_STATE_STOPPED 10
#define AAUDIO_STREAM_STATE_CLOSED 12
#define AAUDIO_STREAM_STATE_DISCONNECTED 13
aaudio_result_t AAudio_createStreamBuilder(AAudioStreamBuilder **);
void AAudioStreamBuilder_setDirection(AAudioStreamBuilder *, int32_t);
void AAudioStreamBuilder_setSharingMode(AAudioStreamBuilder *, int32_t);
void AAudioStreamBuilder_setPerformanceMode(AAudioStreamBuilder *, int32_t);
void AAudioStreamBuilder_setFormat(AAudioStreamBuilder *, int32_t);
void AAudioStreamBuilder_setChannelCount(AAudioStreamBuilder *, int32_t);
void AAudioStreamBuilder_setSampleRate(AAudioStreamBuilder *, int32_t);
void AAudioStreamBuilder_setDataCallback(AAudioStreamBuilder *, AAudioStream_dataCallback, void *);
void AAudioStreamBuilder_setErrorCallback(AAudioStreamBuilder *, AAudioStream_errorCallback, void *);
aaudio_result_t AAudioStreamBuilder_openStream(AAudioStreamBuilder *, AAudioStream **);
aaudio_result_t AAudioStreamBuilder_delete(AAudioStreamBuilder *);
int32_t AAudioStream_getSampleRate(AAudioStream *);
int32_t AAudioStream_getFormat(AAudioStream *);
int32_t AAudioStream_getChannelCount(AAudioStream *);
int32_t AAudioStream_getFramesPerBurst(AAudioStream *);
int32_t AAudioStream_getBufferCapacityInFrames(AAudioStream *);
int32_t AAudioStream_getBufferSizeInFrames(AAudioStream *);
aaudio_result_t AAudioStream_setBufferSizeInFrames(AAudioStream *, int32_t);
aaudio_result_t AAudioStream_requestStart(AAudioStream *);
aaudio_result_t AAudioStream_requestPause(AAudioStream *);
aaudio_result_t AAudioStream_requestStop(AAudioStream *);
aaudio_stream_state_t AAudioStream_getState(AAudioStream *);
aaudio_result_t AAudioStream_waitForStateChange(AAudioStream *, aaudio_stream_state_t, aaudio_stream_state_t *, int64_t);
aaudio_result_t AAudioStream_close(AAudioStream *);
#endif
