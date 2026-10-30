#ifndef RAVEN_TEST_LOOPER_H
#define RAVEN_TEST_LOOPER_H
typedef struct ALooper ALooper;
typedef int (*ALooper_callbackFunc)(int fd, int events, void *data);
#define ALOOPER_PREPARE_ALLOW_NON_CALLBACKS 1
ALooper *ALooper_forThread(void);
ALooper *ALooper_prepare(int opts);
#endif
