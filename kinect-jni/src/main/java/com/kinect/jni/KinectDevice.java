package com.kinect.jni;

/**
 * Represents an opened Kinect V2 device.
 *
 * This class provides methods to start/stop streaming, retrieve frames,
 * and manage device lifecycle.
 *
 * Usage:
 * <pre>
 * try (FreenectContext context = Freenect.createContext();
 *      KinectDevice device = context.openDefaultDevice()) {
 *     device.start();
 *     Frame colorFrame = device.getNextFrame(FrameType.COLOR, 1000);
 *     // Process frame...
 *     colorFrame.close();
 *     device.stop();
 * }
 * </pre>
 */
public class KinectDevice implements AutoCloseable {

    /**
     * Native pointer to libfreenect2::Freenect2Device object.
     */
    private long nativeHandle;

    /**
     * Reference to the parent context.
     */
    private final FreenectContext context;

    /**
     * Device serial number.
     */
    private final String serial;

    /**
     * Pipeline type used for this device.
     */
    private final PipelineType pipelineType;

    /**
     * Track whether streaming is active.
     */
    private boolean streaming = false;

    /**
     * Track whether device is closed.
     */
    private boolean closed = false;

    /**
     * Package-private constructor called by FreenectContext.
     *
     * @param context parent context
     * @param serial device serial (null for default device)
     * @param pipelineType packet pipeline type (CPU or OPENGL)
     */
    KinectDevice(FreenectContext context, String serial, PipelineType pipelineType) {
        this.context = context;
        this.serial = serial;
        this.pipelineType = pipelineType;
        this.nativeHandle = nativeOpenDevice(
                context.getNativeHandle(),
                serial,
                pipelineType.getNativeValue());
        if (this.nativeHandle == 0) {
            throw new RuntimeException("Failed to open Kinect device: " +
                    (serial != null ? serial : "default"));
        }
    }

    /**
     * Get the device serial number.
     *
     * @return device serial string
     */
    public String getSerial() {
        return serial;
    }

    /**
     * Get the pipeline type used for this device.
     *
     * @return pipeline type (CPU or OPENGL)
     */
    public PipelineType getPipelineType() {
        return pipelineType;
    }

    /**
     * Get the firmware version string from the device.
     *
     * @return firmware version
     * @throws IllegalStateException if device is closed
     */
    public String getFirmwareVersion() {
        checkNotClosed();
        return nativeGetFirmwareVersion(nativeHandle);
    }

    /**
     * Start streaming from the device.
     *
     * This enables the specified frame types for capture.
     * By default, all frame types (Color, Depth, IR) are enabled.
     *
     * @throws RuntimeException if streaming fails to start
     * @throws IllegalStateException if device is closed or already streaming
     */
    public void start() {
        checkNotClosed();
        if (streaming) {
            throw new IllegalStateException("Device is already streaming");
        }
        boolean success = nativeStart(nativeHandle);
        if (!success) {
            throw new RuntimeException("Failed to start device streaming");
        }
        streaming = true;
    }

    /**
     * Start streaming with specific frame types enabled.
     *
     * @param types frame types to enable (Color, Depth, IR)
     * @throws RuntimeException if streaming fails to start
     * @throws IllegalStateException if device is closed or already streaming
     */
    public void start(FrameType... types) {
        checkNotClosed();
        if (streaming) {
            throw new IllegalStateException("Device is already streaming");
        }

        int typeMask = 0;
        for (FrameType type : types) {
            typeMask |= type.getNativeValue();
        }

        boolean success = nativeStartWithTypes(nativeHandle, typeMask);
        if (!success) {
            throw new RuntimeException("Failed to start device streaming");
        }
        streaming = true;
    }

    /**
     * Stop streaming from the device.
     *
     * @throws IllegalStateException if device is closed or not streaming
     */
    public void stop() {
        checkNotClosed();
        if (!streaming) {
            throw new IllegalStateException("Device is not streaming");
        }
        nativeStop(nativeHandle);
        streaming = false;
    }

    /**
     * Check if the device is currently streaming.
     *
     * @return true if streaming, false otherwise
     */
    public boolean isStreaming() {
        return streaming && !closed;
    }

    /**
     * Get the next frame of the specified type.
     *
     * This method blocks until a frame is available or the timeout expires.
     *
     * @param type frame type to retrieve
     * @param timeoutMs timeout in milliseconds (0 for no timeout)
     * @return Frame object, or null if timeout occurred
     * @throws IllegalStateException if device is closed or not streaming
     */
    public Frame getNextFrame(FrameType type, long timeoutMs) {
        checkNotClosed();
        if (!streaming) {
            throw new IllegalStateException("Device is not streaming");
        }
        return nativeGetNextFrame(nativeHandle, type.getNativeValue(), timeoutMs);
    }

    /**
     * Get the next frame of the specified type with default 1 second timeout.
     *
     * @param type frame type to retrieve
     * @return Frame object, or null if timeout occurred
     * @throws IllegalStateException if device is closed or not streaming
     */
    public Frame getNextFrame(FrameType type) {
        return getNextFrame(type, 1000);
    }

    /**
     * Check if this device has been closed.
     *
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Close the device and release native resources.
     *
     * If the device is streaming, it will be stopped first.
     */
    @Override
    public void close() {
        if (!closed) {
            if (streaming) {
                try {
                    stop();
                } catch (Exception e) {
                    // Ignore errors during close
                }
            }
            if (nativeHandle != 0) {
                nativeCloseDevice(nativeHandle);
                nativeHandle = 0;
            }
            closed = true;
        }
    }

    /**
     * Ensure device is not closed before operations.
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("KinectDevice has been closed");
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

    @Override
    public String toString() {
        return String.format("KinectDevice{serial=%s, pipeline=%s, streaming=%b, closed=%b}",
                serial, pipelineType, streaming, closed);
    }

    // Native method declarations

    /**
     * Open a Kinect device.
     *
     * @param contextHandle native pointer to libfreenect2::Freenect2
     * @param serial device serial (null for default)
     * @param pipelineType packet pipeline type (0 = CPU, 1 = OPENGL)
     * @return native pointer to libfreenect2::Freenect2Device, or 0 on failure
     */
    private native long nativeOpenDevice(long contextHandle, String serial, int pipelineType);

    /**
     * Close a Kinect device.
     *
     * @param handle native pointer to libfreenect2::Freenect2Device
     */
    private native void nativeCloseDevice(long handle);

    /**
     * Get firmware version from device.
     *
     * @param handle native pointer to libfreenect2::Freenect2Device
     * @return firmware version string
     */
    private native String nativeGetFirmwareVersion(long handle);

    /**
     * Start streaming with all frame types.
     *
     * @param handle native pointer to libfreenect2::Freenect2Device
     * @return true if successful, false otherwise
     */
    private native boolean nativeStart(long handle);

    /**
     * Start streaming with specific frame types.
     *
     * @param handle native pointer to libfreenect2::Freenect2Device
     * @param typeMask bitmask of frame types
     * @return true if successful, false otherwise
     */
    private native boolean nativeStartWithTypes(long handle, int typeMask);

    /**
     * Stop streaming.
     *
     * @param handle native pointer to libfreenect2::Freenect2Device
     */
    private native void nativeStop(long handle);

    /**
     * Get next frame from device.
     *
     * @param handle native pointer to libfreenect2::Freenect2Device
     * @param frameType frame type to retrieve
     * @param timeoutMs timeout in milliseconds
     * @return Frame object, or null on timeout
     */
    private native Frame nativeGetNextFrame(long handle, int frameType, long timeoutMs);
}
