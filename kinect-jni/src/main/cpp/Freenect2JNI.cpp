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

    fprintf(stderr, "[JNI] Initializing GLFW on main thread...\n");
    fflush(stderr);

    int result = glfwInit();
    if (result == 0) {
        fprintf(stderr, "[JNI] ERROR: glfwInit() failed\n");
        fflush(stderr);
    } else {
        g_glfwInitialized = true;
        fprintf(stderr, "[JNI] GLFW initialized successfully\n");
        fflush(stderr);
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

    fprintf(stderr, "[JNI] GLFW not initialized, dispatching to main thread...\n");
    fflush(stderr);

    // If we're already on the main thread, just call directly to avoid deadlock
    if (isMainThread()) {
        fprintf(stderr, "[JNI] Already on main thread, initializing GLFW directly...\n");
        fflush(stderr);
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
            fprintf(stderr, "[JNI] Creating OpenGL pipeline on main thread...\n");
            fflush(stderr);
            pipeline = new libfreenect2::OpenGLPacketPipeline();
            fprintf(stderr, "[JNI] OpenGL pipeline created successfully\n");
            fflush(stderr);
            pipelineCreated = true;
        } catch (const std::exception &e) {
            errorMessage = e.what();
            fprintf(stderr, "[JNI] ERROR creating pipeline: %s\n", e.what());
            fflush(stderr);
        } catch (...) {
            errorMessage = "Unknown exception creating OpenGL pipeline";
            fprintf(stderr, "[JNI] ERROR: Unknown exception creating pipeline\n");
            fflush(stderr);
        }
    };

    // If we're already on the main thread, just call directly to avoid deadlock
    if (isMainThread()) {
        fprintf(stderr, "[JNI] Already on main thread, creating pipeline directly...\n");
        fflush(stderr);
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

    DeviceContext(libfreenect2::Freenect2Device *dev)
        : device(dev), listener(nullptr) {}

    ~DeviceContext() {
        if (listener != nullptr) {
            delete listener;
            listener = nullptr;
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
        fprintf(stderr, "[JNI] ERROR: Failed to find Frame class\n");
        fflush(stderr);
        env->ExceptionDescribe();
        return nullptr;
    }

    // Find FrameType enum
    jclass frameTypeClass = env->FindClass("com/kinect/jni/FrameType");
    if (frameTypeClass == nullptr) {
        fprintf(stderr, "[JNI] ERROR: Failed to find FrameType class\n");
        fflush(stderr);
        env->ExceptionDescribe();
        return nullptr;
    }

    // Get FrameType.fromNativeValue(int) static method
    jmethodID fromNativeValueMethod = env->GetStaticMethodID(
        frameTypeClass, "fromNativeValue", "(I)Lcom/kinect/jni/FrameType;");
    if (fromNativeValueMethod == nullptr) {
        fprintf(stderr, "[JNI] ERROR: Failed to find fromNativeValue method\n");
        fflush(stderr);
        env->ExceptionDescribe();
        return nullptr;
    }

    // Get FrameType enum instance
    jobject frameType = env->CallStaticObjectMethod(
        frameTypeClass, fromNativeValueMethod, frameTypeValue);
    if (env->ExceptionCheck()) {
        fprintf(stderr, "[JNI] ERROR: Exception calling fromNativeValue\n");
        fflush(stderr);
        env->ExceptionDescribe();
        return nullptr;
    }
    if (frameType == nullptr) {
        fprintf(stderr, "[JNI] ERROR: fromNativeValue returned null\n");
        fflush(stderr);
        return nullptr;
    }

    // Find Frame constructor
    // Frame(long nativeHandle, FrameType type, int width, int height,
    //       int bytesPerPixel, long timestamp, long sequence)
    jmethodID frameConstructor = env->GetMethodID(
        frameClass, "<init>",
        "(JLcom/kinect/jni/FrameType;IIIJJ)V");
    if (frameConstructor == nullptr) {
        fprintf(stderr, "[JNI] ERROR: Failed to find Frame constructor with signature (JLcom/kinect/jni/FrameType;IIIJJ)V\n");
        fflush(stderr);
        env->ExceptionDescribe();
        return nullptr;
    }

    // Create Frame object
    fprintf(stderr, "[JNI] Creating Frame: width=%d, height=%d, bpp=%d, ts=%ld, seq=%ld\n",
            frame->width, frame->height, frame->bytes_per_pixel, frame->timestamp, frame->sequence);
    fflush(stderr);

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
        fprintf(stderr, "[JNI] ERROR: Exception creating Frame object\n");
        fflush(stderr);
        env->ExceptionDescribe();
        return nullptr;
    }

    if (javaFrame == nullptr) {
        fprintf(stderr, "[JNI] ERROR: NewObject returned null\n");
        fflush(stderr);
        return nullptr;
    }

    fprintf(stderr, "[JNI] Frame object created successfully\n");
    fflush(stderr);
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
            fprintf(stderr, "[JNI] Destroying Freenect2 context on main thread...\n");
            fflush(stderr);
            delete freenect2;
            fprintf(stderr, "[JNI] Freenect2 context destroyed\n");
            fflush(stderr);
        };

        // If we're already on the main thread, just call directly to avoid deadlock
        if (isMainThread()) {
            fprintf(stderr, "[JNI] Already on main thread, destroying context directly...\n");
            fflush(stderr);
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
        fprintf(stderr, "[JNI] Creating CPU pipeline...\n");
        fflush(stderr);
        libfreenect2::PacketPipeline *pipeline = new libfreenect2::CpuPacketPipeline();
        fprintf(stderr, "[JNI] CPU pipeline created successfully\n");
        fflush(stderr);
        return pipeline;
    } else {  // OPENGL (default)
#ifdef __APPLE__
        // On macOS, create OpenGL pipeline on the main thread
        return createOpenGLPipelineOnMainThread();
#else
        // On other platforms, create pipeline normally
        fprintf(stderr, "[JNI] Creating OpenGL pipeline...\n");
        fflush(stderr);
        libfreenect2::PacketPipeline *pipeline = new libfreenect2::OpenGLPacketPipeline();
        fprintf(stderr, "[JNI] OpenGL pipeline created\n");
        fflush(stderr);
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
        fprintf(stderr, "[JNI] nativeOpenDevice: Device serial = %s, pipeline type = %d\n",
                serialStr.c_str(), pipelineType);
        fflush(stderr);

        // Check if serial is empty (no device available)
        if (serialStr.empty()) {
            fprintf(stderr, "[JNI] ERROR: No device found (empty serial)\n");
            fflush(stderr);
            throwRuntimeException(env, "No Kinect device found");
            return 0;
        }

        // Create pipeline based on type
        libfreenect2::PacketPipeline *pipeline = createPipeline(pipelineType);

        // Open device with selected pipeline
        fprintf(stderr, "[JNI] Opening device...\n");
        fflush(stderr);
        libfreenect2::Freenect2Device *device = freenect2->openDevice(serialStr, pipeline);

        fprintf(stderr, "[JNI] openDevice() returned (device=%p)\n", device);
        fflush(stderr);

        if (device == nullptr) {
            fprintf(stderr, "[JNI] ERROR: Failed to open device (returned nullptr)\n");
            fflush(stderr);
            delete pipeline;
            throwRuntimeException(env, "Failed to open device (device not found or in use)");
            return 0;
        }

        fprintf(stderr, "[JNI] Device opened successfully\n");
        fflush(stderr);

        // Create device context
        DeviceContext *ctx = new DeviceContext(device);
        jlong handle = reinterpret_cast<jlong>(ctx);

        // Register in global map
        {
            std::lock_guard<std::mutex> lock(registryMutex);
            deviceRegistry[handle] = ctx;
        }

        fprintf(stderr, "[JNI] Device initialized and registered (handle=%lx)\n", handle);
        fflush(stderr);

        return handle;
    } catch (const std::exception &e) {
        fprintf(stderr, "[JNI] EXCEPTION: %s\n", e.what());
        fflush(stderr);
        throwRuntimeException(env, (std::string("Device open exception: ") + e.what()).c_str());
        return 0;
    } catch (...) {
        fprintf(stderr, "[JNI] UNKNOWN EXCEPTION in nativeOpenDevice\n");
        fflush(stderr);
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

        ctx->device->stop();

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
// Registration class native methods
// ============================================================================

/**
 * Destroy registration object.
 *
 * @param handle native pointer to libfreenect2::Registration
 */
JNI_METHOD(void, Registration, nativeDestroyRegistration)(JNIEnv *env, jobject obj, jlong handle) {
    if (handle != 0) {
        libfreenect2::Registration *registration = reinterpret_cast<libfreenect2::Registration*>(handle);
        delete registration;
    }
}

// End of extern "C" block
#ifdef __cplusplus
}
#endif
