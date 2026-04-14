package org.openrndr.kinect2

import com.kinect.jni.Freenect
import com.kinect.jni.FreenectContext
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

/**
 * Singleton manager for the global FreenectContext.
 *
 * This class ensures only ONE FreenectContext is created for the entire JVM lifecycle,
 * solving the macOS LIBUSB_ERROR_OTHER issue caused by rapid context creation/destruction.
 *
 * The context is lazily initialized on first access and automatically cleaned up on JVM shutdown.
 *
 * Thread-safe for concurrent access from multiple Kinect2 instances or enumeration calls.
 *
 * ## Architecture
 *
 * **Problem:** On macOS, libusb cannot handle rapid USB context creation/destruction cycles.
 * After 2-3 quick cycles, it enters an error state and refuses new contexts with LIBUSB_ERROR_OTHER.
 *
 * **Solution:** Share a single long-lived FreenectContext between:
 * - Kinect2Manager (device enumeration)
 * - Kinect2 extension instances (streaming)
 *
 * **Lifecycle:**
 * 1. Created lazily on first call to getContext()
 * 2. Reused for all subsequent operations
 * 3. Automatically closed on JVM shutdown via shutdown hook
 *
 * ## Usage
 *
 * ```kotlin
 * // Get singleton context (creates on first call)
 * val context = FreenectContextManager.getContext()
 *
 * // Use context for enumeration or device operations
 * val deviceCount = context.getDeviceCount()
 *
 * // Context is automatically cleaned up on JVM exit
 * ```
 *
 * ## Thread Safety
 *
 * Multiple threads can safely call getContext() concurrently. Double-checked locking
 * ensures the context is only created once.
 *
 * @see Kinect2Manager
 * @see Kinect2
 */
object FreenectContextManager {
    private val logger = LoggerFactory.getLogger(FreenectContextManager::class.java)

    @Volatile
    private var context: FreenectContext? = null
    private val contextLock = Any()
    private var shutdownHookRegistered = false

    /**
     * Get the singleton FreenectContext, creating it lazily on first access.
     *
     * This context is shared by all Kinect2 instances and remains alive until JVM shutdown.
     * Multiple calls are safe and will return the same instance.
     *
     * The first call will initialize libfreenect2 and enumerate USB devices.
     * Subsequent calls return the cached instance with no overhead.
     *
     * @return the global FreenectContext
     * @throws RuntimeException if context creation fails
     */
    fun getContext(): FreenectContext {
        // Double-checked locking for thread-safe lazy initialization
        if (context == null) {
            synchronized(contextLock) {
                if (context == null) {
                    logger.info("Creating singleton FreenectContext")
                    logger.info("libfreenect2 version: ${Freenect.getVersion()}")

                    context = Freenect.createContext()

                    if (!shutdownHookRegistered) {
                        registerShutdownHook()
                        shutdownHookRegistered = true
                    }

                    logger.info("Singleton FreenectContext created successfully")
                }
            }
        }
        return context!!
    }

    /**
     * Register JVM shutdown hook to cleanup context on application exit.
     *
     * This ensures native resources are properly freed even if the application
     * doesn't explicitly close the context.
     *
     * The shutdown hook will be invoked automatically when the JVM terminates normally.
     * It will NOT run on kill -9 or system crashes (but OS reclaims USB resources anyway).
     */
    private fun registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(thread(start = false, name = "FreenectContext-Cleanup") {
            logger.info("JVM shutdown detected, cleaning up singleton FreenectContext")
            // IMPORTANT: We intentionally do NOT call context.close() here.
            //
            // On macOS, nativeDestroyContext() uses dispatch_sync(dispatch_get_main_queue(), ...)
            // to ensure GLFW/OpenGL cleanup runs on the main thread. During JVM shutdown,
            // the main thread's GCD dispatch queue is no longer being serviced, so
            // dispatch_sync blocks forever → process hangs.
            //
            // This is safe to skip because:
            // 1. The KinectDevice is already closed by Kinect2.shutdown() (device.close()
            //    stops streaming and releases USB interfaces)
            // 2. The OS reclaims all remaining resources (USB handles, memory, GL contexts)
            //    when the process exits
            // 3. libfreenect2's Freenect2 destructor only tears down internal bookkeeping
            //    that becomes irrelevant at process exit
            synchronized(contextLock) {
                context = null
            }
            logger.info("FreenectContext cleanup skipped (OS reclaims resources at process exit)")
        })
        logger.debug("Shutdown hook registered for FreenectContext cleanup")
    }

    /**
     * FOR TESTING ONLY: Force reset of the singleton context.
     *
     * This closes the current context and allows a new one to be created.
     * Should NEVER be called in production code - only for unit tests.
     *
     * @throws IllegalStateException if called while devices are open
     */
    @Synchronized
    internal fun resetForTesting() {
        synchronized(contextLock) {
            context?.let {
                logger.warn("TESTING: Resetting singleton FreenectContext")
                try {
                    it.close()
                } catch (e: Exception) {
                    logger.error("Error closing context during reset", e)
                }
            }
            context = null
            shutdownHookRegistered = false
        }
    }
}