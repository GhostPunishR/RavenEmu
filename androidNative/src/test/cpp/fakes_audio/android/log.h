#ifndef RAVEN_TEST_ANDROID_LOG_H
#define RAVEN_TEST_ANDROID_LOG_H
#define ANDROID_LOG_WARN 5
int __android_log_print(int priority, const char *tag, const char *format, ...);
#endif
