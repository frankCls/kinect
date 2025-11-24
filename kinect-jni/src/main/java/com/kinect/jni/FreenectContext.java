package com.kinect.jni;

/**
 * Represents a libfreenect2 context for device enumeration and management.
 *
 * The context is the main entry point for working with Kinect V2 devices.
 * It manages USB connections and device lifecycle.
 *
 * This class implements AutoCloseable for resource management:
 * <pre>
 * try (FreenectContext context = Freenect.createContext()) {
 *     int count = context.getDeviceCount();
 *     System.out.println("Found " + count + " devices");
 * }
 * </pre>
 */
public class FreenectContext implements AutoCloseable {

    /**
     * Native pointer to the libfreenect2::Freenect2 object.
     * This is managed by the native layer.
     */
    private long nativeHandle;

    /**
     * Track whether this context has been closed.
     */
    private boolean closed = false;

    /**
     * Package-private constructor called by Freenect.createContext().
     * The actual initialization happens in the native constructor.
     */
    FreenectContext() {
        this.nativeHandle = nativeCreateContext();
        if (this.nativeHandle == 0) {
            throw new RuntimeException("Failed to create Freenect2 context");
        }
    }

    /**
     * Get the number of connected Kinect V2 devices.
     *
     * @return number of devices found (0 if none)
     * @throws IllegalStateException if context is closed
     */
    public int getDeviceCount() {
        checkNotClosed();
        return nativeGetDeviceCount(nativeHandle);
    }

    /**
     * Get the serial number of a device by index.
     *
     * @param index device index (0-based)
     * @return device serial number as a string
     * @throws IllegalStateException if context is closed
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public String getDeviceSerial(int index) {
        checkNotClosed();
        if (index < 0 || index >= getDeviceCount()) {
            throw new IndexOutOfBoundsException("Invalid device index: " + index);
        }
        return nativeGetDeviceSerial(nativeHandle, index);
    }

    /**
     * Get the default device serial number.
     * This returns the serial of the first detected device.
     *
     * @return default device serial, or null if no devices found
     * @throws IllegalStateException if context is closed
     */
    public String getDefaultDeviceSerial() {
        checkNotClosed();
        return nativeGetDefaultDeviceSerial(nativeHandle);
    }

    /**
     * Open a Kinect device by serial number with specified pipeline type.
     *
     * @param serial device serial number (null for default device)
     * @param pipelineType packet pipeline type (CPU or OPENGL)
     * @return a KinectDevice instance
     * @throws RuntimeException if device cannot be opened
     * @throws IllegalStateException if context is closed
     */
    public KinectDevice openDevice(String serial, PipelineType pipelineType) {
        checkNotClosed();
        return new KinectDevice(this, serial, pipelineType);
    }

    /**
     * Open a Kinect device by serial number.
     * Uses OpenGL pipeline by default for backward compatibility.
     *
     * @param serial device serial number (null for default device)
     * @return a KinectDevice instance
     * @throws RuntimeException if device cannot be opened
     * @throws IllegalStateException if context is closed
     */
    public KinectDevice openDevice(String serial) {
        return openDevice(serial, PipelineType.OPENGL);
    }

    /**
     * Open the default Kinect device (first device found) with specified pipeline type.
     *
     * @param pipelineType packet pipeline type (CPU or OPENGL)
     * @return a KinectDevice instance
     * @throws RuntimeException if no devices found or device cannot be opened
     * @throws IllegalStateException if context is closed
     */
    public KinectDevice openDefaultDevice(PipelineType pipelineType) {
        return openDevice(null, pipelineType);
    }

    /**
     * Open the default Kinect device (first device found).
     * Uses OpenGL pipeline by default for backward compatibility.
     *
     * @return a KinectDevice instance
     * @throws RuntimeException if no devices found or device cannot be opened
     * @throws IllegalStateException if context is closed
     */
    public KinectDevice openDefaultDevice() {
        return openDevice(null, PipelineType.OPENGL);
    }

    /**
     * Get the native handle for this context.
     * This is used internally by other JNI classes.
     *
     * @return native pointer value
     */
    long getNativeHandle() {
        return nativeHandle;
    }

    /**
     * Check if this context has been closed.
     *
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Close the context and release native resources.
     * This should be called when the context is no longer needed.
     */
    @Override
    public void close() {
        if (!closed) {
            if (nativeHandle != 0) {
                nativeDestroyContext(nativeHandle);
                nativeHandle = 0;
            }
            closed = true;
        }
    }

    /**
     * Ensure context is not closed before operations.
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("FreenectContext has been closed");
        }
    }

    /**
     * Finalize method to ensure native resources are freed.
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    // Native method declarations

    /**
     * Create a native Freenect2 context.
     *
     * @return native pointer to libfreenect2::Freenect2, or 0 on failure
     */
    private native long nativeCreateContext();

    /**
     * Destroy a native Freenect2 context.
     *
     * @param handle native pointer to libfreenect2::Freenect2
     */
    private native void nativeDestroyContext(long handle);

    /**
     * Get device count from native context.
     *
     * @param handle native pointer to libfreenect2::Freenect2
     * @return number of devices
     */
    private native int nativeGetDeviceCount(long handle);

    /**
     * Get device serial by index.
     *
     * @param handle native pointer to libfreenect2::Freenect2
     * @param index device index
     * @return device serial string
     */
    private native String nativeGetDeviceSerial(long handle, int index);

    /**
     * Get default device serial.
     *
     * @param handle native pointer to libfreenect2::Freenect2
     * @return device serial string, or null if no devices
     */
    private native String nativeGetDefaultDeviceSerial(long handle);
}
