package com.kinect.jni;

/**
 * Registration and coordinate mapping for Kinect V2.
 *
 * The Registration class provides methods to:
 * - Align depth frames to color frames
 * - Convert depth pixels to 3D coordinates
 * - Map between depth and color coordinate spaces
 *
 * This is essential for creating colorized point clouds and aligning
 * data from the physically separate depth and color cameras.
 */
public class Registration implements AutoCloseable {

    /**
     * Native pointer to libfreenect2::Registration object.
     */
    private long nativeHandle;

    /**
     * Device calibration parameters.
     */
    private final Calibration calibration;

    /**
     * Track whether this registration has been closed.
     */
    private boolean closed = false;

    /**
     * Create a Registration instance from a device.
     *
     * @param device Kinect device to get calibration from
     * @return Registration instance
     */
    public static Registration create(KinectDevice device) {
        Calibration calibration = device.getCalibration();
        long nativeHandle = nativeCreateRegistration(calibration);
        if (nativeHandle == 0) {
            throw new RuntimeException("Failed to create Registration");
        }
        return new Registration(nativeHandle, calibration);
    }

    /**
     * Package-private constructor.
     *
     * @param nativeHandle native registration pointer
     * @param calibration device calibration
     */
    Registration(long nativeHandle, Calibration calibration) {
        this.nativeHandle = nativeHandle;
        this.calibration = calibration;
    }

    /**
     * Get the calibration parameters.
     *
     * @return calibration object
     */
    public Calibration getCalibration() {
        return calibration;
    }

    /**
     * Get the native handle (package-private for use by KinectDevice).
     *
     * @return native pointer to libfreenect2::Registration
     */
    long getNativeHandle() {
        return nativeHandle;
    }

    /**
     * Apply depth-to-color registration to get RGB color for each depth pixel.
     *
     * This maps each depth frame pixel to its corresponding color from the color frame.
     * The output is a 512x424 BGRX image where each pixel contains the color from
     * the corresponding 3D point in the color camera's view.
     *
     * @param depthData depth frame data buffer (512x424 floats, in millimeters)
     * @param colorData color frame data buffer (1920x1080 BGRX, 4 bytes per pixel)
     * @param outputBuffer output buffer for registered colors (512x424 BGRX, 4 bytes per pixel)
     * @throws IllegalStateException if registration is closed
     */
    public void applyRegistration(java.nio.ByteBuffer depthData,
                                   java.nio.ByteBuffer colorData,
                                   java.nio.ByteBuffer outputBuffer) {
        checkNotClosed();
        nativeApplyRegistration(nativeHandle, depthData, colorData, outputBuffer);
    }

    /**
     * Convert a depth pixel to 3D coordinates.
     *
     * @param x depth image x coordinate (0-511)
     * @param y depth image y coordinate (0-423)
     * @param depth depth value in millimeters
     * @return float array [X, Y, Z] in meters
     * @throws IllegalStateException if registration is closed
     */
    public float[] depthTo3D(int x, int y, float depth) {
        checkNotClosed();
        // Will be implemented with native methods
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * Check if this registration has been closed.
     *
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Release registration resources.
     */
    @Override
    public void close() {
        if (!closed) {
            if (nativeHandle != 0) {
                nativeDestroyRegistration(nativeHandle);
                nativeHandle = 0;
            }
            closed = true;
        }
    }

    /**
     * Ensure registration is not closed before operations.
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Registration has been closed");
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
     * Create native registration object from calibration.
     *
     * @param calibration device calibration parameters
     * @return native pointer to libfreenect2::Registration
     */
    private static native long nativeCreateRegistration(Calibration calibration);

    /**
     * Apply depth-to-color registration.
     *
     * @param handle native pointer to libfreenect2::Registration
     * @param depthData depth frame data buffer (512x424 floats, mm)
     * @param colorData color frame data buffer (1920x1080 BGRX, 4 bytes per pixel)
     * @param outputBuffer output buffer for registered colors (512x424 BGRX, 4 bytes per pixel)
     */
    private native void nativeApplyRegistration(long handle, java.nio.ByteBuffer depthData,
                                                 java.nio.ByteBuffer colorData,
                                                 java.nio.ByteBuffer outputBuffer);

    /**
     * Destroy native registration object.
     *
     * @param handle native pointer to libfreenect2::Registration
     */
    private native void nativeDestroyRegistration(long handle);
}
