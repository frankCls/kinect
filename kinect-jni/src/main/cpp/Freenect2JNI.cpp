/**
 * JNI implementation for libfreenect2 bindings.
 *
 * This file implements the native methods declared in the Java classes:
 * - Freenect
 * - FreenectContext
 * - KinectDevice
 * - Frame
 * - Registration
 *
 * The implementation wraps libfreenect2 C++ API calls.
 */

#include <jni.h>
#include <libfreenect2/libfreenect2.hpp>
#include <libfreenect2/frame_listener_impl.h>
#include <libfreenect2/registration.h>
#include <libfreenect2/packet_pipeline.h>
#include <libfreenect2/logger.h>

#include <string>
#include <memory>
#include <map>

// Utility macros for JNI
#define JNI_METHOD(return_type, class_name, method_name) \
    JNIEXPORT return_type JNICALL Java_com_kinect_jni_##class_name##_##method_name

// Helper function to throw Java exceptions
void throwRuntimeException(JNIEnv *env, const char *message) {
    jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

// ============================================================================
// Freenect class native methods
// ============================================================================

/**
 * Get libfreenect2 version string.
 */
JNI_METHOD(jstring, Freenect, getVersion)(JNIEnv *env, jclass clazz) {
    // libfreenect2 doesn't have a version API, return a constant
    return env->NewStringUTF("0.2.0");
}

// ============================================================================
// FreenectContext class native methods
// ============================================================================

/**
 * Create a new Freenect2 context.
 *
 * @return native pointer to libfreenect2::Freenect2 object
 */
JNI_METHOD(jlong, FreenectContext, nativeCreateContext)(JNIEnv *env, jobject obj) {
    try {
        libfreenect2::Freenect2 *freenect2 = new libfreenect2::Freenect2();
        return reinterpret_cast<jlong>(freenect2);
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return 0;
    }
}

/**
 * Destroy a Freenect2 context.
 *
 * @param handle native pointer to libfreenect2::Freenect2
 */
JNI_METHOD(void, FreenectContext, nativeDestroyContext)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle != 0) {
        libfreenect2::Freenect2 *freenect2 = reinterpret_cast<libfreenect2::Freenect2*>(handle);
        delete freenect2;
    }
}

/**
 * Get number of connected devices.
 *
 * @param handle native pointer to libfreenect2::Freenect2
 * @return device count
 */
JNI_METHOD(jint, FreenectContext, nativeGetDeviceCount)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid context handle");
        return 0;
    }

    try {
        libfreenect2::Freenect2 *freenect2 = reinterpret_cast<libfreenect2::Freenect2*>(handle);
        return static_cast<jint>(freenect2->enumerateDevices());
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return 0;
    }
}

/**
 * Get device serial by index.
 *
 * @param handle native pointer to libfreenect2::Freenect2
 * @param index device index
 * @return device serial string
 */
JNI_METHOD(jstring, FreenectContext, nativeGetDeviceSerial)(JNIEnv *env, jobject obj, jlong handle, jint index) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid context handle");
        return nullptr;
    }

    try {
        libfreenect2::Freenect2 *freenect2 = reinterpret_cast<libfreenect2::Freenect2*>(handle);
        std::string serial = freenect2->getDeviceSerialNumber(static_cast<int>(index));
        return env->NewStringUTF(serial.c_str());
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return nullptr;
    }
}

/**
 * Get default device serial.
 *
 * @param handle native pointer to libfreenect2::Freenect2
 * @return device serial string, or null if no devices
 */
JNI_METHOD(jstring, FreenectContext, nativeGetDefaultDeviceSerial)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid context handle");
        return nullptr;
    }

    try {
        libfreenect2::Freenect2 *freenect2 = reinterpret_cast<libfreenect2::Freenect2*>(handle);
        std::string serial = freenect2->getDefaultDeviceSerialNumber();
        if (serial.empty()) {
            return nullptr;
        }
        return env->NewStringUTF(serial.c_str());
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return nullptr;
    }
}

// ============================================================================
// KinectDevice class native methods (to be continued in next iteration)
// ============================================================================

/**
 * Open a Kinect device.
 *
 * @param contextHandle native pointer to libfreenect2::Freenect2
 * @param serial device serial (null for default)
 * @return native pointer to libfreenect2::Freenect2Device
 */
JNI_METHOD(jlong, KinectDevice, nativeOpenDevice)(JNIEnv *env, jobject obj, jlong contextHandle, jstring serial) {
    if (contextHandle == 0) {
        throwRuntimeException(env, "Invalid context handle");
        return 0;
    }

    try {
        libfreenect2::Freenect2 *freenect2 = reinterpret_cast<libfreenect2::Freenect2*>(contextHandle);

        // Convert Java string to C++ string
        std::string serialStr;
        if (serial != nullptr) {
            const char *serialChars = env->GetStringUTFChars(serial, nullptr);
            serialStr = std::string(serialChars);
            env->ReleaseStringUTFChars(serial, serialChars);
        } else {
            serialStr = freenect2->getDefaultDeviceSerialNumber();
        }

        // Open device with OpenCL pipeline (best performance on macOS)
        libfreenect2::Freenect2Device *device = freenect2->openDevice(serialStr);
        if (device == nullptr) {
            throwRuntimeException(env, "Failed to open device");
            return 0;
        }

        return reinterpret_cast<jlong>(device);
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return 0;
    }
}

/**
 * Close a Kinect device.
 *
 * @param handle native pointer to libfreenect2::Freenect2Device
 */
JNI_METHOD(void, KinectDevice, nativeCloseDevice)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle != 0) {
        libfreenect2::Freenect2Device *device = reinterpret_cast<libfreenect2::Freenect2Device*>(handle);
        device->close();
        // Note: libfreenect2 manages device lifetime, we don't delete it
    }
}

/**
 * Get device firmware version.
 *
 * @param handle native pointer to libfreenect2::Freenect2Device
 * @return firmware version string
 */
JNI_METHOD(jstring, KinectDevice, nativeGetFirmwareVersion)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return nullptr;
    }

    try {
        libfreenect2::Freenect2Device *device = reinterpret_cast<libfreenect2::Freenect2Device*>(handle);
        std::string version = device->getFirmwareVersion();
        return env->NewStringUTF(version.c_str());
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return nullptr;
    }
}

/**
 * Start streaming with all frame types.
 *
 * @param handle native pointer to libfreenect2::Freenect2Device
 * @return true if successful
 */
JNI_METHOD(jboolean, KinectDevice, nativeStart)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return JNI_FALSE;
    }

    try {
        libfreenect2::Freenect2Device *device = reinterpret_cast<libfreenect2::Freenect2Device*>(handle);

        // Create frame listener for all types
        int types = libfreenect2::Frame::Color | libfreenect2::Frame::Ir | libfreenect2::Frame::Depth;
        libfreenect2::SyncMultiFrameListener *listener = new libfreenect2::SyncMultiFrameListener(types);

        device->setColorFrameListener(listener);
        device->setIrAndDepthFrameListener(listener);

        bool success = device->start();
        return success ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return JNI_FALSE;
    }
}

/**
 * Start streaming with specific frame types.
 *
 * @param handle native pointer to libfreenect2::Freenect2Device
 * @param typeMask bitmask of frame types
 * @return true if successful
 */
JNI_METHOD(jboolean, KinectDevice, nativeStartWithTypes)(JNIEnv *env, jobject obj, jlong handle, jint typeMask) {
    // For now, just call nativeStart - we'll implement type filtering later
    return Java_com_kinect_jni_KinectDevice_nativeStart(env, obj, handle);
}

/**
 * Stop streaming.
 *
 * @param handle native pointer to libfreenect2::Freenect2Device
 */
JNI_METHOD(void, KinectDevice, nativeStop)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return;
    }

    try {
        libfreenect2::Freenect2Device *device = reinterpret_cast<libfreenect2::Freenect2Device*>(handle);
        device->stop();
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
    }
}

/**
 * Get next frame from device.
 * PLACEHOLDER - full implementation requires frame listener management
 *
 * @param handle native pointer to libfreenect2::Freenect2Device
 * @param frameType frame type to retrieve
 * @param timeoutMs timeout in milliseconds
 * @return Frame object
 */
JNI_METHOD(jobject, KinectDevice, nativeGetNextFrame)(JNIEnv *env, jobject obj, jlong handle, jint frameType, jlong timeoutMs) {
    // Placeholder - full implementation requires frame management
    throwRuntimeException(env, "nativeGetNextFrame not yet fully implemented");
    return nullptr;
}

// ============================================================================
// Frame class native methods
// ============================================================================

/**
 * Get frame data as a direct ByteBuffer.
 * PLACEHOLDER
 *
 * @param handle native pointer to libfreenect2::Frame
 * @return direct ByteBuffer
 */
JNI_METHOD(jobject, Frame, nativeGetFrameData)(JNIEnv *env, jobject obj, jlong handle) {
    // Placeholder
    throwRuntimeException(env, "nativeGetFrameData not yet fully implemented");
    return nullptr;
}

/**
 * Release a frame.
 * PLACEHOLDER
 *
 * @param handle native pointer to libfreenect2::Frame
 */
JNI_METHOD(void, Frame, nativeReleaseFrame)(JNIEnv *env, jobject obj, jlong handle) {
    // Placeholder - will be implemented with frame listener management
}

// ============================================================================
// Registration class native methods
// ============================================================================

/**
 * Destroy registration object.
 * PLACEHOLDER
 *
 * @param handle native pointer to libfreenect2::Registration
 */
JNI_METHOD(void, Registration, nativeDestroyRegistration)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle != 0) {
        libfreenect2::Registration *registration = reinterpret_cast<libfreenect2::Registration*>(handle);
        delete registration;
    }
}
