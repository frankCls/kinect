package com.kinect.jni;

/**
 * Entry point for libfreenect2 JNI bindings.
 *
 * This class provides static factory methods for creating Freenect2 contexts
 * and handles native library loading.
 *
 * Usage:
 * <pre>
 * FreenectContext context = Freenect.createContext();
 * int deviceCount = context.getDeviceCount();
 * </pre>
 */
public class Freenect {

    private static final String NATIVE_LIBRARY_NAME = "kinect-jni";
    private static boolean libraryLoaded = false;

    /**
     * Static initializer to load the native library.
     */
    static {
        try {
            System.loadLibrary(NATIVE_LIBRARY_NAME);
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            System.err.println("Failed to load native library: " + NATIVE_LIBRARY_NAME);
            System.err.println("java.library.path: " + System.getProperty("java.library.path"));
            throw new RuntimeException("Unable to load native library: " + NATIVE_LIBRARY_NAME, e);
        }
    }

    /**
     * Private constructor to prevent instantiation.
     */
    private Freenect() {
        throw new AssertionError("Freenect is a utility class and should not be instantiated");
    }

    /**
     * Check if the native library has been successfully loaded.
     *
     * @return true if the library is loaded, false otherwise
     */
    public static boolean isLibraryLoaded() {
        return libraryLoaded;
    }

    /**
     * Create a new Freenect2 context.
     *
     * A context is required to enumerate and open Kinect V2 devices.
     * The context must be closed when no longer needed to free resources.
     *
     * @return a new FreenectContext instance
     * @throws RuntimeException if context creation fails
     */
    public static FreenectContext createContext() {
        return new FreenectContext();
    }

    /**
     * Get the version of the libfreenect2 library.
     *
     * @return version string (e.g., "0.2.0")
     */
    public static native String getVersion();
}
