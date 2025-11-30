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
#include <mutex>
#include <condition_variable>
#include <thread>
#include <chrono>

#include "logging.h"

// macOS-specific includes for Grand Central Dispatch
#ifdef __APPLE__
#include <dispatch/dispatch.h>
#include <CoreFoundation/CoreFoundation.h>
#include <pthread.h>

// GLFW forward declarations (to check initialization status)
extern "C" {
    int glfwInit(void);
    void glfwTerminate(void);
}

// Helper function to check if we're on the main thread
inline bool isMainThread() {
    return pthread_main_np() != 0;
}
#endif

// Utility macros for JNI
#define JNI_METHOD(return_type, class_name, method_name) \
    JNIEXPORT return_type JNICALL Java_com_kinect_jni_##class_name##_##method_name

// Start of extern "C" block for JNI functions
#ifdef __cplusplus
extern "C" {
#endif

// ============================================================================
// macOS Main Thread Support for GLFW/OpenGL
// ============================================================================

#ifdef __APPLE__
// Global state for GLFW initialization
static bool g_glfwInitialized = false;
static std::mutex g_glfwMutex;
static std::condition_variable g_glfwCondVar;
static bool g_glfwInitInProgress = false;

/**
 * Initialize GLFW on the macOS main thread.
 * This MUST be called on the main thread due to macOS requirements.
 */
void initializeGLFWOnMainThread() {
    std::lock_guard<std::mutex> lock(g_glfwMutex);

    if (g_glfwInitialized) {
        return; // Already initialized
    }

    LOG_INFO("Initializing GLFW on main thread...");

    int result = glfwInit();
    if (result == 0) {
        LOG_ERROR("glfwInit() failed");
    } else {
        g_glfwInitialized = true;
        LOG_INFO("GLFW initialized successfully");
    }
}

/**
 * Ensure GLFW is initialized, dispatching to main thread if necessary.
 */
void ensureGLFWInitialized() {
    std::unique_lock<std::mutex> lock(g_glfwMutex);

    if (g_glfwInitialized) {
        return; // Already initialized
    }

    if (g_glfwInitInProgress) {
        // Another thread is already initializing, wait for it
        g_glfwCondVar.wait(lock, [] { return g_glfwInitialized; });
        return;
    }

    g_glfwInitInProgress = true;
    lock.unlock();

    LOG_INFO("GLFW not initialized, dispatching to main thread...");

    // If we're already on the main thread, just call directly to avoid deadlock
    if (isMainThread()) {
        LOG_INFO("Already on main thread, initializing GLFW directly...");
        initializeGLFWOnMainThread();
    } else {
        // Dispatch to main thread
        dispatch_sync(dispatch_get_main_queue(), ^{
            initializeGLFWOnMainThread();
        });
    }

    lock.lock();
    g_glfwInitInProgress = false;
    g_glfwCondVar.notify_all();
}

/**
 * Create OpenGL pipeline on the main thread.
 * The OpenGLPacketPipeline constructor creates OpenGL contexts which must be
 * done on the main thread on macOS.
 */
libfreenect2::PacketPipeline* createOpenGLPipelineOnMainThread() {
    __block libfreenect2::PacketPipeline* pipeline = nullptr;
    __block bool pipelineCreated = false;
    __block std::string errorMessage;

    auto createPipeline = ^{
        try {
            LOG_INFO("Creating OpenGL pipeline on main thread...");
            pipeline = new libfreenect2::OpenGLPacketPipeline();
            LOG_INFO("OpenGL pipeline created successfully");
            pipelineCreated = true;
        } catch (const std::exception &e) {
            errorMessage = e.what();
            LOG_ERROR("Creating pipeline: %s", e.what());
        } catch (...) {
            errorMessage = "Unknown exception creating OpenGL pipeline";
            LOG_ERROR("Unknown exception creating pipeline");
        }
    };

    // If we're already on the main thread, just call directly to avoid deadlock
    if (isMainThread()) {
        LOG_INFO("Already on main thread, creating pipeline directly...");
        createPipeline();
    } else {
        dispatch_sync(dispatch_get_main_queue(), createPipeline);
    }

    if (!pipelineCreated) {
        throw std::runtime_error(errorMessage);
    }

    return pipeline;
}
#endif

// Structure to hold device state including frame listener
struct DeviceContext {
    libfreenect2::Freenect2Device *device;
    libfreenect2::SyncMultiFrameListener *listener;
    // Persistent frame buffers for registration (allocated once, reused)
    libfreenect2::Frame *undistorted;
    libfreenect2::Frame *registered;
    // Registration object for depth-to-color alignment (lazy initialized after device start)
    libfreenect2::Registration *registration;
    // Persistent buffer for registered color data (512x424x4 BGRX)
    unsigned char *registeredBuffer;
    // Flag to track if registration has been initialized (set to true after device starts)
    bool registrationInitialized;

    DeviceContext(libfreenect2::Freenect2Device *dev)
        : device(dev), listener(nullptr), undistorted(nullptr), registered(nullptr),
          registration(nullptr), registeredBuffer(nullptr), registrationInitialized(false) {}

    ~DeviceContext() {
        if (listener != nullptr) {
            delete listener;
            listener = nullptr;
        }
        if (undistorted != nullptr) {
            delete undistorted;
            undistorted = nullptr;
        }
        if (registered != nullptr) {
            delete registered;
            registered = nullptr;
        }
        if (registration != nullptr) {
            delete registration;
            registration = nullptr;
        }
        if (registeredBuffer != nullptr) {
            delete[] registeredBuffer;
            registeredBuffer = nullptr;
        }
    }
};

// Global registry of device contexts (thread-safe)
static std::map<jlong, DeviceContext*> deviceRegistry;
static std::mutex registryMutex;

// Helper function to throw Java exceptions
void throwRuntimeException(JNIEnv *env, const char *message) {
    jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

// Helper to get DeviceContext safely
DeviceContext* getDeviceContext(jlong handle) {
    std::lock_guard<std::mutex> lock(registryMutex);
    auto it = deviceRegistry.find(handle);
    if (it != deviceRegistry.end()) {
        return it->second;
    }
    return nullptr;
}

// Helper to create Java Frame object from native frame
jobject createJavaFrame(JNIEnv *env, libfreenect2::Frame *frame, int frameTypeValue) {
    // Find Frame class and constructor
    jclass frameClass = env->FindClass("com/kinect/jni/Frame");
    if (frameClass == nullptr) {
        LOG_ERROR("Failed to find Frame class");
        env->ExceptionDescribe();
        return nullptr;
    }

    // Find FrameType enum
    jclass frameTypeClass = env->FindClass("com/kinect/jni/FrameType");
    if (frameTypeClass == nullptr) {
        LOG_ERROR("Failed to find FrameType class");
        env->ExceptionDescribe();
        return nullptr;
    }

    // Get FrameType.fromNativeValue(int) static method
    jmethodID fromNativeValueMethod = env->GetStaticMethodID(
        frameTypeClass, "fromNativeValue", "(I)Lcom/kinect/jni/FrameType;");
    if (fromNativeValueMethod == nullptr) {
        LOG_ERROR("Failed to find fromNativeValue method");
        env->ExceptionDescribe();
        return nullptr;
    }

    // Get FrameType enum instance
    jobject frameType = env->CallStaticObjectMethod(
        frameTypeClass, fromNativeValueMethod, frameTypeValue);
    if (env->ExceptionCheck()) {
        LOG_ERROR("Exception calling fromNativeValue");
        env->ExceptionDescribe();
        return nullptr;
    }
    if (frameType == nullptr) {
        LOG_ERROR("fromNativeValue returned null");
        return nullptr;
    }

    // Find Frame constructor
    // Frame(long nativeHandle, FrameType type, int width, int height,
    //       int bytesPerPixel, long timestamp, long sequence)
    jmethodID frameConstructor = env->GetMethodID(
        frameClass, "<init>",
        "(JLcom/kinect/jni/FrameType;IIIJJ)V");
    if (frameConstructor == nullptr) {
        LOG_ERROR("Failed to find Frame constructor with signature (JLcom/kinect/jni/FrameType;IIIJJ)V");
        env->ExceptionDescribe();
        return nullptr;
    }

    // Create Frame object
    LOG_DEBUG("Creating Frame: width=%d, height=%d, bpp=%d, ts=%ld, seq=%ld",
            frame->width, frame->height, frame->bytes_per_pixel, frame->timestamp, frame->sequence);

    jobject javaFrame = env->NewObject(
        frameClass, frameConstructor,
        reinterpret_cast<jlong>(frame),
        frameType,
        static_cast<jint>(frame->width),
        static_cast<jint>(frame->height),
        static_cast<jint>(frame->bytes_per_pixel),
        static_cast<jlong>(frame->timestamp),
        static_cast<jlong>(frame->sequence)
    );

    if (env->ExceptionCheck()) {
        LOG_ERROR("Exception creating Frame object");
        env->ExceptionDescribe();
        return nullptr;
    }

    if (javaFrame == nullptr) {
        LOG_ERROR("NewObject returned null");
        return nullptr;
    }

    LOG_DEBUG("Frame object created successfully");
    return javaFrame;
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
 * @return native pointer to libfreenect2::Freenect2, or 0 on failure
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

#ifdef __APPLE__
        // On macOS, GLFW cleanup must also happen on the main thread
        // The OpenGLPacketPipeline destructor calls glfwDestroyWindow which requires main thread

        auto destroyContext = ^{
            LOG_INFO("Destroying Freenect2 context on main thread...");
            delete freenect2;
            LOG_INFO("Freenect2 context destroyed");
        };

        // If we're already on the main thread, just call directly to avoid deadlock
        if (isMainThread()) {
            LOG_INFO("Already on main thread, destroying context directly...");
            destroyContext();
        } else {
            dispatch_sync(dispatch_get_main_queue(), destroyContext);
        }
#else
        delete freenect2;
#endif
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
// KinectDevice class native methods
// ============================================================================

/**
 * Create a packet pipeline based on type.
 *
 * @param pipelineType 0 = CPU, 1 = OPENGL
 * @return pointer to PacketPipeline (caller must manage lifetime)
 */
libfreenect2::PacketPipeline* createPipeline(int pipelineType) {
    if (pipelineType == 0) {  // CPU
        LOG_INFO("Creating CPU pipeline...");
        libfreenect2::PacketPipeline *pipeline = new libfreenect2::CpuPacketPipeline();
        LOG_INFO("CPU pipeline created successfully");
        return pipeline;
    } else {  // OPENGL (default)
#ifdef __APPLE__
        // On macOS, create OpenGL pipeline on the main thread
        return createOpenGLPipelineOnMainThread();
#else
        // On other platforms, create pipeline normally
        LOG_INFO("Creating OpenGL pipeline...");
        libfreenect2::PacketPipeline *pipeline = new libfreenect2::OpenGLPacketPipeline();
        LOG_INFO("OpenGL pipeline created");
        return pipeline;
#endif
    }
}

/**
 * Open a Kinect device.
 *
 * @param contextHandle native pointer to libfreenect2::Freenect2
 * @param serial device serial (null for default)
 * @param pipelineType packet pipeline type (0 = CPU, 1 = OPENGL)
 * @return native pointer to DeviceContext
 */
JNI_METHOD(jlong, KinectDevice, nativeOpenDevice)(JNIEnv *env, jobject obj, jlong contextHandle, jstring serial, jint pipelineType) {
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

        // Log debug info
        LOG_DEBUG("nativeOpenDevice: Device serial = %s, pipeline type = %d",
                serialStr.c_str(), pipelineType);

        // Check if serial is empty (no device available)
        if (serialStr.empty()) {
            LOG_ERROR("No device found (empty serial)");
            throwRuntimeException(env, "No Kinect device found");
            return 0;
        }

        // Create pipeline based on type
        libfreenect2::PacketPipeline *pipeline = createPipeline(pipelineType);

        // Open device with selected pipeline
        LOG_INFO("Opening device...");
        libfreenect2::Freenect2Device *device = freenect2->openDevice(serialStr, pipeline);

        LOG_DEBUG("openDevice() returned (device=%p)", device);

        if (device == nullptr) {
            LOG_ERROR("Failed to open device (returned nullptr)");
            delete pipeline;
            throwRuntimeException(env, "Failed to open device (device not found or in use)");
            return 0;
        }

        LOG_INFO("Device opened successfully");

        // Create device context
        // NOTE: Registration is NOT initialized here - it will be lazy-initialized
        // after device starts and calibration data is loaded from firmware
        DeviceContext *ctx = new DeviceContext(device);

        LOG_INFO("DeviceContext created (Registration will be lazy-initialized after device start)");

        jlong handle = reinterpret_cast<jlong>(ctx);

        // Register in global map
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            deviceRegistry[handle] = ctx;
        }

        LOG_DEBUG("Device initialized and registered (handle=%lx)", handle);

        return handle;
    } catch (const std::exception &e) {
        LOG_ERROR("EXCEPTION: %s", e.what());
        throwRuntimeException(env, (std::string("Device open exception: ") + e.what()).c_str());
        return 0;
    } catch (...) {
        LOG_ERROR("UNKNOWN EXCEPTION in nativeOpenDevice");
        throwRuntimeException(env, "Unknown exception opening device");
        return 0;
    }
}


/**
 * Close a Kinect device.
 *
 * @param handle native pointer to DeviceContext
 */
JNI_METHOD(void, KinectDevice, nativeCloseDevice)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        return;
    }

    DeviceContext *ctx = getDeviceContext(handle);
    if (ctx != nullptr) {
        if (ctx->device != nullptr) {
            ctx->device->close();
        }

        // Remove from registry and delete
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            deviceRegistry.erase(handle);
        }
        delete ctx;
    }
}

/**
 * Get device firmware version.
 *
 * @param handle native pointer to DeviceContext
 * @return firmware version string
 */
JNI_METHOD(jstring, KinectDevice, nativeGetFirmwareVersion)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return nullptr;
    }

    try {
        DeviceContext *ctx = getDeviceContext(handle);
        if (ctx == nullptr || ctx->device == nullptr) {
            throwRuntimeException(env, "Invalid device context");
            return nullptr;
        }

        std::string version = ctx->device->getFirmwareVersion();
        return env->NewStringUTF(version.c_str());
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return nullptr;
    }
}

/**
 * Start streaming with all frame types.
 *
 * @param handle native pointer to DeviceContext
 * @return true if successful
 */
JNI_METHOD(jboolean, KinectDevice, nativeStart)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return JNI_FALSE;
    }

    try {
        DeviceContext *ctx = getDeviceContext(handle);
        if (ctx == nullptr || ctx->device == nullptr) {
            throwRuntimeException(env, "Invalid device context");
            return JNI_FALSE;
        }

        // Create frame listener for all types
        int types = libfreenect2::Frame::Color | libfreenect2::Frame::Ir | libfreenect2::Frame::Depth;
        ctx->listener = new libfreenect2::SyncMultiFrameListener(types);

        ctx->device->setColorFrameListener(ctx->listener);
        ctx->device->setIrAndDepthFrameListener(ctx->listener);

        bool success = ctx->device->start();
        return success ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return JNI_FALSE;
    }
}

/**
 * Start streaming with specific frame types.
 *
 * @param handle native pointer to DeviceContext
 * @param typeMask bitmask of frame types
 * @return true if successful
 */
JNI_METHOD(jboolean, KinectDevice, nativeStartWithTypes)(JNIEnv *env, jobject obj, jlong handle, jint typeMask) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return JNI_FALSE;
    }

    try {
        DeviceContext *ctx = getDeviceContext(handle);
        if (ctx == nullptr || ctx->device == nullptr) {
            throwRuntimeException(env, "Invalid device context");
            return JNI_FALSE;
        }

        LOG_INFO("nativeStartWithTypes called with typeMask=%d (Color=%d, IR=%d, Depth=%d)",
                typeMask,
                (typeMask & libfreenect2::Frame::Color) ? 1 : 0,
                (typeMask & libfreenect2::Frame::Ir) ? 1 : 0,
                (typeMask & libfreenect2::Frame::Depth) ? 1 : 0);

        // Create frame listener with specified types
        ctx->listener = new libfreenect2::SyncMultiFrameListener(static_cast<int>(typeMask));

        ctx->device->setColorFrameListener(ctx->listener);
        ctx->device->setIrAndDepthFrameListener(ctx->listener);

        bool success = ctx->device->start();
        return success ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return JNI_FALSE;
    }
}

/**
 * Stop streaming.
 *
 * @param handle native pointer to DeviceContext
 */
JNI_METHOD(void, KinectDevice, nativeStop)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return;
    }

    try {
        DeviceContext *ctx = getDeviceContext(handle);
        if (ctx == nullptr || ctx->device == nullptr) {
            throwRuntimeException(env, "Invalid device context");
            return;
        }

        // Stop the device first - this will unblock any waiting listener threads
        ctx->device->stop();

        // Give the listener thread time to exit cleanly from waitForNewFrame()
        // This prevents crashes when deleting the listener while it's still blocked
        std::this_thread::sleep_for(std::chrono::milliseconds(100));

        // Clean up listener
        if (ctx->listener != nullptr) {
            delete ctx->listener;
            ctx->listener = nullptr;
        }
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
    }
}

/**
 * Get next frame from device.
 *
 * @param handle native pointer to DeviceContext
 * @param frameType frame type to retrieve
 * @param timeoutMs timeout in milliseconds
 * @return Frame object, or null on timeout
 */
JNI_METHOD(jobject, KinectDevice, nativeGetNextFrame)(JNIEnv *env, jobject obj, jlong handle, jint frameType, jlong timeoutMs) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return nullptr;
    }

    try {
        DeviceContext *ctx = getDeviceContext(handle);
        if (ctx == nullptr || ctx->device == nullptr || ctx->listener == nullptr) {
            throwRuntimeException(env, "Invalid device context or not streaming");
            return nullptr;
        }

        // Wait for new frame set
        libfreenect2::FrameMap frames;
        bool gotFrames = ctx->listener->waitForNewFrame(frames, static_cast<int>(timeoutMs));

        if (!gotFrames) {
            // Timeout - return null
            return nullptr;
        }

        // Get the specific frame type
        libfreenect2::Frame *frame = frames[static_cast<libfreenect2::Frame::Type>(frameType)];
        if (frame == nullptr) {
            ctx->listener->release(frames);
            return nullptr;
        }

        // Create a copy of the frame (we need to own it after releasing the frame map)
        libfreenect2::Frame *frameCopy = new libfreenect2::Frame(
            frame->width, frame->height, frame->bytes_per_pixel, frame->data);
        frameCopy->timestamp = frame->timestamp;
        frameCopy->sequence = frame->sequence;
        frameCopy->exposure = frame->exposure;
        frameCopy->gain = frame->gain;
        frameCopy->gamma = frame->gamma;
        frameCopy->status = frame->status;
        frameCopy->format = frame->format;

        // Release the frame map back to the listener
        ctx->listener->release(frames);

        // Create Java Frame object
        jobject javaFrame = createJavaFrame(env, frameCopy, frameType);

        return javaFrame;
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return nullptr;
    }
}

/**
 * Get synchronized depth and color frames with registration applied.
 * This method waits for BOTH depth and color frames, applies registration using the
 * native frames while they're still valid, copies the registered result to a persistent buffer,
 * then creates Java Frame objects and releases the native frames.
 *
 * @param handle native pointer to DeviceContext
 * @param timeoutMs timeout in milliseconds
 * @return Object array containing [depthFrame, colorFrame] as Frame objects, or null on timeout
 */
JNI_METHOD(jobjectArray, KinectDevice, nativeGetSynchronizedFrames)(JNIEnv *env, jobject obj, jlong handle, jlong timeoutMs) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return nullptr;
    }

    try {
        DeviceContext *ctx = getDeviceContext(handle);
        if (ctx == nullptr || ctx->device == nullptr || ctx->listener == nullptr) {
            throwRuntimeException(env, "Invalid device context or not streaming");
            return nullptr;
        }

        // Lazy initialization of Registration (must be done AFTER device starts and calibration loads)
        if (!ctx->registrationInitialized) {
            LOG_INFO("Lazy-initializing Registration (device has started, calibration should be loaded)...");

            // Get camera parameters (should be valid now that device has started)
            libfreenect2::Freenect2Device::IrCameraParams irParams = ctx->device->getIrCameraParams();
            libfreenect2::Freenect2Device::ColorCameraParams colorParams = ctx->device->getColorCameraParams();

            LOG_TRACE("IR camera params: fx=%.2f, fy=%.2f, cx=%.2f, cy=%.2f",
                    irParams.fx, irParams.fy, irParams.cx, irParams.cy);
            LOG_TRACE("Color camera params: fx=%.2f, fy=%.2f, cx=%.2f, cy=%.2f",
                    colorParams.fx, colorParams.fy, colorParams.cx, colorParams.cy);

            // Verify calibration data is valid
            if (irParams.fx == 0.0 || irParams.fy == 0.0 || colorParams.fx == 0.0 || colorParams.fy == 0.0) {
                LOG_ERROR("Camera calibration data is invalid (all zeros) - device may not have started properly");
                throwRuntimeException(env, "Camera calibration data is invalid (all zeros) - device may not have started properly");
                return nullptr;
            }

            // Create Registration object
            ctx->registration = new libfreenect2::Registration(irParams, colorParams);

            // Allocate persistent frame buffers for registration
            ctx->undistorted = new libfreenect2::Frame(512, 424, 4);  // Undistorted depth
            ctx->undistorted->format = libfreenect2::Frame::Float;     // Depth is float format

            ctx->registered = new libfreenect2::Frame(512, 424, 4);    // Registered color (BGRX)
            ctx->registered->format = libfreenect2::Frame::BGRX;        // Color is BGRX format

            // Allocate persistent buffer for registered color data
            ctx->registeredBuffer = new unsigned char[512 * 424 * 4];  // 512x424 BGRX
            memset(ctx->registeredBuffer, 0, 512 * 424 * 4);  // Initialize to zeros

            ctx->registrationInitialized = true;

            LOG_INFO("Registration initialized successfully with valid calibration data");
        }

        // Wait for synchronized frames from listener
        // Keep waiting until we have BOTH Color and Depth frames
        libfreenect2::FrameMap frames;
        libfreenect2::Frame *colorFrame = nullptr;
        libfreenect2::Frame *depthFrame = nullptr;

        int attempts = 0;
        const int maxAttempts = 10; // Try up to 10 times
        const int attemptTimeout = static_cast<int>(timeoutMs) / maxAttempts;

        while (attempts < maxAttempts && (colorFrame == nullptr || depthFrame == nullptr)) {
            bool success = ctx->listener->waitForNewFrame(frames, attemptTimeout);

            if (!success) {
                attempts++;
                continue; // Timeout on this attempt, try again
            }

            // Check what frames we got
            colorFrame = frames[libfreenect2::Frame::Color];
            depthFrame = frames[libfreenect2::Frame::Depth];

            LOG_DEBUG("nativeGetSynchronizedFrames attempt %d: Color=%p, Depth=%p",
                    attempts + 1, colorFrame, depthFrame);

            if (colorFrame != nullptr && depthFrame != nullptr) {
                // Got both frames!
                break;
            }

            // Release incomplete frame set and wait for next one
            ctx->listener->release(frames);
            frames.clear();
            colorFrame = nullptr;
            depthFrame = nullptr;
            attempts++;
        }

        if (colorFrame == nullptr || depthFrame == nullptr) {
            if (!frames.empty()) {
                ctx->listener->release(frames);
            }
            LOG_ERROR("Failed to get both frames after %d attempts (Color=%p, Depth=%p)",
                    attempts, colorFrame, depthFrame);
            // Return null on timeout (not an exception)
            return nullptr;
        }

        LOG_DEBUG("Successfully got both frames: Color=%p, Depth=%p", colorFrame, depthFrame);

        // Debug: Check frame dimensions and formats
        LOG_TRACE("Color frame: %dx%d, bpp=%d, format=%d",
                colorFrame->width, colorFrame->height, colorFrame->bytes_per_pixel, colorFrame->format);
        LOG_TRACE("Depth frame: %dx%d, bpp=%d, format=%d",
                depthFrame->width, depthFrame->height, depthFrame->bytes_per_pixel, depthFrame->format);

        // Debug: Check input color frame center pixel (BGRX format: 1920x1080x4)
        int colorCenterIdx = (1080 / 2) * 1920 + (1920 / 2);
        unsigned char *colorCenterPixel = &((unsigned char*)colorFrame->data)[colorCenterIdx * 4];
        LOG_TRACE("Input color center RGB: (%d, %d, %d)",
                colorCenterPixel[2], colorCenterPixel[1], colorCenterPixel[0]); // BGRX: [B, G, R, X]

        // Debug: Check input depth frame center pixel (float mm)
        int depthCenterIdx = (424 / 2) * 512 + (512 / 2);
        float depthCenterValue = ((float*)depthFrame->data)[depthCenterIdx];
        LOG_TRACE("Input depth center: %.2f mm", depthCenterValue);

        // Apply registration using native frames while they're still valid
        // This populates ctx->undistorted and ctx->registered (persistent buffers)
        // Try with enable_filter=true to see if that helps
        ctx->registration->apply(colorFrame, depthFrame, ctx->undistorted, ctx->registered, true);

        LOG_DEBUG("Registration applied successfully (enable_filter=true)");
        LOG_TRACE("Registered buffer after apply: %dx%d, bpp=%d, format=%d",
                ctx->registered->width, ctx->registered->height, ctx->registered->bytes_per_pixel, ctx->registered->format);

        // Copy registered data to persistent buffer (persists after we release frames)
        // ctx->registered->data is BGRX format (512x424x4 bytes)
        memcpy(ctx->registeredBuffer, ctx->registered->data, 512 * 424 * 4);

        // Debug: Check MULTIPLE pixels in registered buffer to see if ANY have valid RGB
        LOG_TRACE("Checking registered buffer pixels:");
        for (int i = 0; i < 5; i++) {
            int testY = 100 + i * 50;  // Sample at different Y positions
            int testX = 256;            // Center X
            int idx = (testY * 512 + testX) * 4;
            unsigned char *pixel = &((unsigned char*)ctx->registered->data)[idx];
            LOG_TRACE("  Pixel[%d,%d] RGB: (%d, %d, %d)",
                    testX, testY, pixel[2], pixel[1], pixel[0]);
        }
        // Also check center
        int centerIdx = (424 / 2) * 512 + (512 / 2);
        unsigned char *centerPixel = &((unsigned char*)ctx->registered->data)[centerIdx * 4];
        LOG_TRACE("  Center[%d,%d] RGB: (%d, %d, %d)",
                256, 212, centerPixel[2], centerPixel[1], centerPixel[0]);

        LOG_DEBUG("Registered data copied to persistent buffer");

        // Create copies of depth and color frames for Java (we need to own them after releasing the frame map)
        libfreenect2::Frame *depthCopy = new libfreenect2::Frame(
            depthFrame->width, depthFrame->height, depthFrame->bytes_per_pixel, depthFrame->data);
        depthCopy->timestamp = depthFrame->timestamp;
        depthCopy->sequence = depthFrame->sequence;
        depthCopy->exposure = depthFrame->exposure;
        depthCopy->gain = depthFrame->gain;
        depthCopy->gamma = depthFrame->gamma;
        depthCopy->status = depthFrame->status;
        depthCopy->format = depthFrame->format;

        libfreenect2::Frame *colorCopy = new libfreenect2::Frame(
            colorFrame->width, colorFrame->height, colorFrame->bytes_per_pixel, colorFrame->data);
        colorCopy->timestamp = colorFrame->timestamp;
        colorCopy->sequence = colorFrame->sequence;
        colorCopy->exposure = colorFrame->exposure;
        colorCopy->gain = colorFrame->gain;
        colorCopy->gamma = colorFrame->gamma;
        colorCopy->status = colorFrame->status;
        colorCopy->format = colorFrame->format;

        // Release native frames back to listener (they will be recycled)
        ctx->listener->release(frames);

        LOG_DEBUG("Releasing frames");

        // Create Java Frame objects
        jobject depthJavaFrame = createJavaFrame(env, depthCopy, libfreenect2::Frame::Depth);
        if (depthJavaFrame == nullptr) {
            delete depthCopy;
            delete colorCopy;
            throwRuntimeException(env, "Failed to create depth Frame object");
            return nullptr;
        }

        jobject colorJavaFrame = createJavaFrame(env, colorCopy, libfreenect2::Frame::Color);
        if (colorJavaFrame == nullptr) {
            delete colorCopy;
            throwRuntimeException(env, "Failed to create color Frame object");
            return nullptr;
        }

        LOG_DEBUG("Java Frame objects created successfully");

        // Create result array [depthFrame, colorFrame]
        jclass frameClass = env->FindClass("com/kinect/jni/Frame");
        jobjectArray result = env->NewObjectArray(2, frameClass, nullptr);

        env->SetObjectArrayElement(result, 0, depthJavaFrame);
        env->SetObjectArrayElement(result, 1, colorJavaFrame);

        return result;

    } catch (const std::exception &e) {
        LOG_ERROR("EXCEPTION in nativeGetSynchronizedFrames: %s", e.what());
        throwRuntimeException(env, e.what());
        return nullptr;
    }
}

/**
 * Get the registered color buffer data.
 * This copies the persistent registered buffer (populated by nativeGetSynchronizedFrames)
 * to the provided Java ByteBuffer.
 *
 * @param handle native pointer to DeviceContext
 * @param byteBuffer Java ByteBuffer to copy data into (must be direct, 512x424x4 bytes)
 * @return true if successful, false if buffer is not available or wrong size
 */
JNI_METHOD(jboolean, KinectDevice, nativeGetRegisteredBuffer)(JNIEnv *env, jobject obj, jlong handle, jobject byteBuffer) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return JNI_FALSE;
    }

    if (byteBuffer == nullptr) {
        throwRuntimeException(env, "ByteBuffer is null");
        return JNI_FALSE;
    }

    try {
        DeviceContext *ctx = getDeviceContext(handle);
        if (ctx == nullptr || ctx->device == nullptr) {
            throwRuntimeException(env, "Invalid device context");
            return JNI_FALSE;
        }

        if (ctx->registeredBuffer == nullptr) {
            throwRuntimeException(env, "Registered buffer not initialized");
            return JNI_FALSE;
        }

        // Get buffer address and capacity
        void *bufferAddr = env->GetDirectBufferAddress(byteBuffer);
        if (bufferAddr == nullptr) {
            throwRuntimeException(env, "ByteBuffer is not a direct buffer");
            return JNI_FALSE;
        }

        jlong bufferCapacity = env->GetDirectBufferCapacity(byteBuffer);
        const jlong expectedSize = 512 * 424 * 4; // 512x424 BGRX

        if (bufferCapacity < expectedSize) {
            char errorMsg[256];
            snprintf(errorMsg, sizeof(errorMsg),
                     "ByteBuffer too small: expected %lld bytes, got %lld bytes",
                     expectedSize, bufferCapacity);
            throwRuntimeException(env, errorMsg);
            return JNI_FALSE;
        }

        // Copy registered buffer data to Java ByteBuffer
        memcpy(bufferAddr, ctx->registeredBuffer, expectedSize);

        LOG_DEBUG("Copied %lld bytes to registered buffer", expectedSize);

        return JNI_TRUE;

    } catch (const std::exception &e) {
        LOG_ERROR("EXCEPTION in nativeGetRegisteredBuffer: %s", e.what());
        throwRuntimeException(env, e.what());
        return JNI_FALSE;
    }
}

// ============================================================================
// Frame class native methods
// ============================================================================

/**
 * Get frame data as a direct ByteBuffer.
 *
 * @param handle native pointer to libfreenect2::Frame
 * @return direct ByteBuffer
 */
JNI_METHOD(jobject, Frame, nativeGetFrameData)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid frame handle");
        return nullptr;
    }

    try {
        libfreenect2::Frame *frame = reinterpret_cast<libfreenect2::Frame*>(handle);

        // Create direct ByteBuffer pointing to frame data
        size_t dataSize = frame->width * frame->height * frame->bytes_per_pixel;
        jobject byteBuffer = env->NewDirectByteBuffer(frame->data, dataSize);

        return byteBuffer;
    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return nullptr;
    }
}

/**
 * Release a frame.
 *
 * @param handle native pointer to libfreenect2::Frame
 */
JNI_METHOD(void, Frame, nativeReleaseFrame)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle != 0) {
        libfreenect2::Frame *frame = reinterpret_cast<libfreenect2::Frame*>(handle);
        delete frame;
    }
}

// ============================================================================
// KinectDevice - getCalibration
// ============================================================================

/**
 * Get calibration parameters from device.
 *
 * @param handle native pointer to DeviceContext
 * @return Calibration object
 */
JNI_METHOD(jobject, KinectDevice, nativeGetCalibration)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return nullptr;
    }

    try {
        DeviceContext *ctx = reinterpret_cast<DeviceContext*>(handle);

        // Get color and IR camera parameters from libfreenect2
        libfreenect2::Freenect2Device::ColorCameraParams colorParams = ctx->device->getColorCameraParams();
        libfreenect2::Freenect2Device::IrCameraParams irParams = ctx->device->getIrCameraParams();

        // Find Java classes
        jclass calibrationClass = env->FindClass("com/kinect/jni/Calibration");
        jclass cameraParamsClass = env->FindClass("com/kinect/jni/Calibration$CameraParams");

        if (!calibrationClass || !cameraParamsClass) {
            throwRuntimeException(env, "Failed to find Calibration classes");
            return nullptr;
        }

        // Find constructors
        jmethodID cameraParamsConstructor = env->GetMethodID(cameraParamsClass, "<init>",
                                                             "(DDDDDDDDD)V");
        jmethodID calibrationConstructor = env->GetMethodID(calibrationClass, "<init>",
                                                            "(Lcom/kinect/jni/Calibration$CameraParams;Lcom/kinect/jni/Calibration$CameraParams;)V");

        if (!cameraParamsConstructor || !calibrationConstructor) {
            throwRuntimeException(env, "Failed to find Calibration constructors");
            return nullptr;
        }

        // Create color camera params object
        // Note: ColorCameraParams only has fx, fy, cx, cy - no distortion coefficients
        jobject colorParamsObj = env->NewObject(cameraParamsClass, cameraParamsConstructor,
                                               (jdouble)colorParams.fx, (jdouble)colorParams.fy,
                                               (jdouble)colorParams.cx, (jdouble)colorParams.cy,
                                               0.0, 0.0, 0.0, 0.0, 0.0);  // Set distortion to zero

        // Create depth (IR) camera params object
        jobject depthParamsObj = env->NewObject(cameraParamsClass, cameraParamsConstructor,
                                               (jdouble)irParams.fx, (jdouble)irParams.fy,
                                               (jdouble)irParams.cx, (jdouble)irParams.cy,
                                               (jdouble)irParams.k1, (jdouble)irParams.k2,
                                               (jdouble)irParams.p1, (jdouble)irParams.p2,
                                               (jdouble)irParams.k3);

        // Create Calibration object
        jobject calibrationObj = env->NewObject(calibrationClass, calibrationConstructor,
                                               colorParamsObj, depthParamsObj);

        return calibrationObj;

    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return nullptr;
    }
}

// ============================================================================
// Registration class native methods
// ============================================================================

/**
 * Create Registration object from calibration.
 *
 * @param calibration Calibration object
 * @return native pointer to libfreenect2::Registration
 */
JNI_METHOD(jlong, Registration, nativeCreateRegistration)(JNIEnv *env, jclass clazz, jobject calibration) {
    try {
        // Get Calibration class and methods
        jclass calibrationClass = env->GetObjectClass(calibration);
        jmethodID getColorParamsMethod = env->GetMethodID(calibrationClass, "getColorParams",
                                                          "()Lcom/kinect/jni/Calibration$CameraParams;");
        jmethodID getDepthParamsMethod = env->GetMethodID(calibrationClass, "getDepthParams",
                                                          "()Lcom/kinect/jni/Calibration$CameraParams;");

        // Get camera params objects
        jobject colorParamsObj = env->CallObjectMethod(calibration, getColorParamsMethod);
        jobject depthParamsObj = env->CallObjectMethod(calibration, getDepthParamsMethod);

        // Get CameraParams class and fields
        jclass cameraParamsClass = env->FindClass("com/kinect/jni/Calibration$CameraParams");
        jfieldID fxField = env->GetFieldID(cameraParamsClass, "fx", "D");
        jfieldID fyField = env->GetFieldID(cameraParamsClass, "fy", "D");
        jfieldID cxField = env->GetFieldID(cameraParamsClass, "cx", "D");
        jfieldID cyField = env->GetFieldID(cameraParamsClass, "cy", "D");
        jfieldID k1Field = env->GetFieldID(cameraParamsClass, "k1", "D");
        jfieldID k2Field = env->GetFieldID(cameraParamsClass, "k2", "D");
        jfieldID p1Field = env->GetFieldID(cameraParamsClass, "p1", "D");
        jfieldID p2Field = env->GetFieldID(cameraParamsClass, "p2", "D");
        jfieldID k3Field = env->GetFieldID(cameraParamsClass, "k3", "D");

        // Extract color camera params
        // Note: ColorCameraParams only has fx, fy, cx, cy - no distortion fields
        libfreenect2::Freenect2Device::ColorCameraParams colorParams;
        colorParams.fx = env->GetDoubleField(colorParamsObj, fxField);
        colorParams.fy = env->GetDoubleField(colorParamsObj, fyField);
        colorParams.cx = env->GetDoubleField(colorParamsObj, cxField);
        colorParams.cy = env->GetDoubleField(colorParamsObj, cyField);
        // ColorCameraParams has no k1, k2, k3, p1, p2 fields - ignore Java distortion values

        // Extract depth camera params
        libfreenect2::Freenect2Device::IrCameraParams irParams;
        irParams.fx = env->GetDoubleField(depthParamsObj, fxField);
        irParams.fy = env->GetDoubleField(depthParamsObj, fyField);
        irParams.cx = env->GetDoubleField(depthParamsObj, cxField);
        irParams.cy = env->GetDoubleField(depthParamsObj, cyField);
        irParams.k1 = env->GetDoubleField(depthParamsObj, k1Field);
        irParams.k2 = env->GetDoubleField(depthParamsObj, k2Field);
        irParams.p1 = env->GetDoubleField(depthParamsObj, p1Field);
        irParams.p2 = env->GetDoubleField(depthParamsObj, p2Field);
        irParams.k3 = env->GetDoubleField(depthParamsObj, k3Field);

        // Create Registration object
        libfreenect2::Registration *registration = new libfreenect2::Registration(irParams, colorParams);

        LOG_DEBUG("Created Registration object (handle=%p)", registration);

        return reinterpret_cast<jlong>(registration);

    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return 0;
    }
}

/**
 * Apply depth-to-color registration.
 *
 * @param handle native pointer to libfreenect2::Registration
 * @param depthData depth frame buffer (512x424 floats in millimeters)
 * @param colorData color frame buffer (1920x1080 RGB bytes)
 * @param outputBuffer output buffer for RGB colors (512x424x3 bytes)
 */
JNI_METHOD(void, Registration, nativeApplyRegistration)(JNIEnv *env, jobject obj, jlong handle,
                                                        jobject depthData, jobject colorData, jobject outputBuffer) {
    if (handle == 0) {
        throwRuntimeException(env, "Invalid registration handle");
        return;
    }

    try {
        libfreenect2::Registration *registration = reinterpret_cast<libfreenect2::Registration*>(handle);

        // Get buffer pointers
        float *depth = (float*)env->GetDirectBufferAddress(depthData);
        uint8_t *color = (uint8_t*)env->GetDirectBufferAddress(colorData);
        uint8_t *output = (uint8_t*)env->GetDirectBufferAddress(outputBuffer);

        if (!depth || !color || !output) {
            throwRuntimeException(env, "Failed to get buffer addresses");
            return;
        }

        const int depthWidth = 512;
        const int depthHeight = 424;
        const int colorWidth = 1920;
        const int colorHeight = 1080;

        // Create temporary Frame objects for libfreenect2 API
        libfreenect2::Frame depthFrame(depthWidth, depthHeight, 4, (unsigned char*)depth);
        depthFrame.format = libfreenect2::Frame::Float;  // Depth is float format

        // Color input is already in BGRX format from OPENRNDR, so use it directly
        libfreenect2::Frame colorFrame(colorWidth, colorHeight, 4, color);  // BGRX format
        colorFrame.format = libfreenect2::Frame::BGRX;

        libfreenect2::Frame registered(depthWidth, depthHeight, 4);  // Output: registered color
        registered.format = libfreenect2::Frame::BGRX;

        libfreenect2::Frame undistorted(depthWidth, depthHeight, 4); // Temporary undistorted depth
        undistorted.format = libfreenect2::Frame::Float;

        // Debug: Check input color data
        static int debugInput = 0;
        if (debugInput++ % 100 == 0) {
            // Check center pixel of input color data (BGRX format, 4 bytes per pixel)
            int centerColorIdx = (colorHeight / 2 * colorWidth + colorWidth / 2) * 4;
            uint8_t b = color[centerColorIdx];
            uint8_t g = color[centerColorIdx + 1];
            uint8_t r = color[centerColorIdx + 2];
            LOG_TRACE("Input color center RGB: (%d, %d, %d)", r, g, b);

            // Check depth center value
            int centerDepthIdx = (depthHeight / 2 * depthWidth + depthWidth / 2);
            float centerDepth = depth[centerDepthIdx];
            LOG_TRACE("Input depth center: %.2f mm", centerDepth);
        }

        // Apply registration using frame-based API
        // Set enable_filter=false to include all pixels, not just those visible to both cameras
        registration->apply(&colorFrame, &depthFrame, &undistorted, &registered, false);

        // Debug: Check registered frame after apply()
        if (debugInput % 100 == 0) {
            LOG_TRACE("After apply(): registered.data=%p, size=%zu x %zu",
                    registered.data, registered.width, registered.height);
            uint32_t *regData = (uint32_t*)registered.data;
            uint32_t centerPixel = regData[depthHeight / 2 * depthWidth + depthWidth / 2];
            LOG_TRACE("Registered center pixel BGRX: 0x%08X", centerPixel);
        }

        // Copy registered BGRX frame directly to output buffer (no conversion needed)
        // Both registered frame and output buffer are in BGRX format (4 bytes per pixel)
        memcpy(output, registered.data, depthWidth * depthHeight * 4);

        // Debug: Check center pixel in output buffer
        static int debugCount = 0;
        if (debugCount++ % 100 == 0) {
            int centerIdx = (depthHeight / 2 * depthWidth + depthWidth / 2) * 4;  // BGRX = 4 bytes
            uint8_t b = output[centerIdx];
            uint8_t g = output[centerIdx + 1];
            uint8_t r = output[centerIdx + 2];
            LOG_TRACE("Registration center pixel RGB: (%d, %d, %d)", r, g, b);
        }

    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
    }
}

/**
 * Get synchronized frames with registration applied.
 * This method waits for new frames from the listener, applies registration using
 * the native frames directly (no copying), then returns the results as ByteBuffers.
 *
 * @param deviceHandle native pointer to DeviceContext
 * @param registrationHandle native pointer to libfreenect2::Registration
 * @param timeoutMs timeout in milliseconds
 * @return Object array containing [depthBuffer, colorBuffer, registeredBuffer] or null on timeout
 */
JNI_METHOD(jobjectArray, KinectDevice, nativeGetRegisteredFrames)(
    JNIEnv *env, jobject obj, jlong deviceHandle, jlong registrationHandle, jlong timeoutMs) {

    if (deviceHandle == 0) {
        throwRuntimeException(env, "Invalid device handle");
        return nullptr;
    }

    if (registrationHandle == 0) {
        throwRuntimeException(env, "Invalid registration handle");
        return nullptr;
    }

    try {
        DeviceContext *ctx = reinterpret_cast<DeviceContext*>(deviceHandle);
        libfreenect2::Registration *registration = reinterpret_cast<libfreenect2::Registration*>(registrationHandle);

        if (ctx->listener == nullptr) {
            throwRuntimeException(env, "Device listener not initialized. Call start() first.");
            return nullptr;
        }

        // Wait for synchronized frames from listener
        // Keep waiting until we have BOTH Color and Depth frames
        libfreenect2::FrameMap frames;
        libfreenect2::Frame *colorFrame = nullptr;
        libfreenect2::Frame *depthFrame = nullptr;

        int attempts = 0;
        const int maxAttempts = 10; // Try up to 10 times
        const int attemptTimeout = static_cast<int>(timeoutMs) / maxAttempts;

        while (attempts < maxAttempts && (colorFrame == nullptr || depthFrame == nullptr)) {
            bool success = ctx->listener->waitForNewFrame(frames, attemptTimeout);

            if (!success) {
                attempts++;
                continue; // Timeout on this attempt, try again
            }

            // Check what frames we got
            colorFrame = frames[libfreenect2::Frame::Color];
            depthFrame = frames[libfreenect2::Frame::Depth];

            LOG_DEBUG("Attempt %d: Color=%p, Depth=%p", attempts + 1, colorFrame, depthFrame);

            if (colorFrame != nullptr && depthFrame != nullptr) {
                // Got both frames!
                break;
            }

            // Release incomplete frame set and wait for next one
            ctx->listener->release(frames);
            frames.clear();
            colorFrame = nullptr;
            depthFrame = nullptr;
            attempts++;
        }

        if (colorFrame == nullptr || depthFrame == nullptr) {
            if (!frames.empty()) {
                ctx->listener->release(frames);
            }
            LOG_ERROR("Failed to get both frames after %d attempts (Color=%p, Depth=%p)",
                    attempts, colorFrame, depthFrame);
            throwRuntimeException(env, "Required frames not available");
            return nullptr;
        }

        LOG_DEBUG("Successfully got both frames: Color=%p, Depth=%p", colorFrame, depthFrame);

        // Allocate persistent frame buffers on first call (reused for subsequent calls)
        if (ctx->undistorted == nullptr) {
            ctx->undistorted = new libfreenect2::Frame(512, 424, 4);
        }
        if (ctx->registered == nullptr) {
            ctx->registered = new libfreenect2::Frame(512, 424, 4);
        }

        // Apply registration using native frames
        registration->apply(colorFrame, depthFrame, ctx->undistorted, ctx->registered, false);

        // Create ByteBuffers for results
        // 1. Depth buffer (512x424x4 bytes = float mm values)
        jobject depthBuffer = env->NewDirectByteBuffer(
            depthFrame->data,
            depthFrame->width * depthFrame->height * depthFrame->bytes_per_pixel
        );

        // 2. Color buffer (1920x1080x4 bytes = BGRX)
        jobject colorBuffer = env->NewDirectByteBuffer(
            colorFrame->data,
            colorFrame->width * colorFrame->height * colorFrame->bytes_per_pixel
        );

        // 3. Registered color buffer (512x424x4 bytes = BGRX aligned to depth)
        // Use persistent frame buffer (ctx->registered persists across calls)
        jobject registeredBuffer = env->NewDirectByteBuffer(
            ctx->registered->data,
            ctx->registered->width * ctx->registered->height * ctx->registered->bytes_per_pixel
        );

        // Release input frames back to listener (they will be recycled)
        // NOTE: ctx->registered is NOT released - it's a persistent buffer we manage
        ctx->listener->release(frames);

        // Create result array [depth, color, registered]
        jclass byteBufferClass = env->FindClass("java/nio/ByteBuffer");
        jobjectArray result = env->NewObjectArray(3, byteBufferClass, nullptr);

        env->SetObjectArrayElement(result, 0, depthBuffer);
        env->SetObjectArrayElement(result, 1, colorBuffer);
        env->SetObjectArrayElement(result, 2, registeredBuffer);

        return result;

    } catch (const std::exception &e) {
        throwRuntimeException(env, e.what());
        return nullptr;
    }
}

/**
 * Destroy registration object.
 *
 * @param handle native pointer to libfreenect2::Registration
 */
JNI_METHOD(void, Registration, nativeDestroyRegistration)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle != 0) {
        libfreenect2::Registration *registration = reinterpret_cast<libfreenect2::Registration*>(handle);
        LOG_DEBUG("Destroying Registration object (handle=%p)", registration);
        delete registration;
    }
}

// End of extern "C" block
#ifdef __cplusplus
}
#endif
