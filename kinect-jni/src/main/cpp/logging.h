#ifndef KINECT_JNI_LOGGING_H
#define KINECT_JNI_LOGGING_H

#include <stdio.h>
#include <stdlib.h>

// Log level constants
#define LOG_LEVEL_ERROR 0
#define LOG_LEVEL_INFO  1
#define LOG_LEVEL_DEBUG 2
#define LOG_LEVEL_TRACE 3

// Get current log level from environment variable or default
static inline int getKinectLogLevel() {
    static int level = -1;
    if (level == -1) {
        const char* envLevel = getenv("KINECT_JNI_LOG_LEVEL");
        if (envLevel != nullptr) {
            level = atoi(envLevel);
            // Validate range
            if (level < LOG_LEVEL_ERROR || level > LOG_LEVEL_TRACE) {
                fprintf(stderr, "[JNI] WARNING: Invalid KINECT_JNI_LOG_LEVEL=%s, using INFO level\n", envLevel);
                level = LOG_LEVEL_INFO;
            }
        } else {
            level = LOG_LEVEL_INFO; // Default to INFO
        }
    }
    return level;
}

// Logging macros with automatic flushing
#define LOG_ERROR(fmt, ...) \
    do { \
        if (getKinectLogLevel() >= LOG_LEVEL_ERROR) { \
            fprintf(stderr, "[JNI ERROR] " fmt "\n", ##__VA_ARGS__); \
            fflush(stderr); \
        } \
    } while(0)

#define LOG_INFO(fmt, ...) \
    do { \
        if (getKinectLogLevel() >= LOG_LEVEL_INFO) { \
            fprintf(stderr, "[JNI INFO] " fmt "\n", ##__VA_ARGS__); \
            fflush(stderr); \
        } \
    } while(0)

#define LOG_DEBUG(fmt, ...) \
    do { \
        if (getKinectLogLevel() >= LOG_LEVEL_DEBUG) { \
            fprintf(stderr, "[JNI DEBUG] " fmt "\n", ##__VA_ARGS__); \
            fflush(stderr); \
        } \
    } while(0)

#define LOG_TRACE(fmt, ...) \
    do { \
        if (getKinectLogLevel() >= LOG_LEVEL_TRACE) { \
            fprintf(stderr, "[JNI TRACE] " fmt "\n", ##__VA_ARGS__); \
            fflush(stderr); \
        } \
    } while(0)

#endif // KINECT_JNI_LOGGING_H
