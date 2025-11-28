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
     * Container for synchronized frames with registration applied.
     * Contains the depth frame, original color frame, and registered color frame
     * (color aligned to depth coordinate space).
     */
    public static class RegisteredFrameSet {
        /** Raw depth data (512x424 floats, millimeters) */
        public final java.nio.ByteBuffer depth;

        /** Original color data (1920x1080 BGRX) */
        public final java.nio.ByteBuffer color;

        /** Registered color data (512x424 BGRX, aligned to depth) */
        public final java.nio.ByteBuffer registered;

        RegisteredFrameSet(java.nio.ByteBuffer depth, java.nio.ByteBuffer color, java.nio.ByteBuffer registered) {
            this.depth = depth;
            this.color = color;
            this.registered = registered;
        }
    }

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
     * Get the calibration parameters for depth and color cameras.
     *
     * @return calibration object containing camera intrinsics
     * @throws IllegalStateException if device is closed
     */
    public Calibration getCalibration() {
        checkNotClosed();
        return nativeGetCalibration(nativeHandle);
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
     * Get synchronized frames with registration applied.
     *
     * This method waits for new frames from the device and applies registration
     * to align the color data to the depth coordinate space. This is more efficient
     * than manually calling Registration.applyRegistration() because it works with
     * native frames directly without copying.
     *
     * @param registration Registration object to use for alignment
     * @param timeoutMs timeout in milliseconds (0 for no timeout)
     * @return RegisteredFrameSet containing depth, color, and registered frames, or null on timeout
     * @throws IllegalStateException if device is closed or not streaming
     * @throws IllegalArgumentException if registration is null or closed
     */
    public RegisteredFrameSet getRegisteredFrames(Registration registration, long timeoutMs) {
        checkNotClosed();
        if (!streaming) {
            throw new IllegalStateException("Device is not streaming");
        }
        if (registration == null) {
            throw new IllegalArgumentException("Registration cannot be null");
        }
        if (registration.isClosed()) {
            throw new IllegalArgumentException("Registration has been closed");
        }

        Object[] buffers = nativeGetRegisteredFrames(nativeHandle, registration.getNativeHandle(), timeoutMs);
        if (buffers == null) {
            return null; // Timeout
        }

        return new RegisteredFrameSet(
            (java.nio.ByteBuffer) buffers[0],  // depth
            (java.nio.ByteBuffer) buffers[1],  // color
            (java.nio.ByteBuffer) buffers[2]   // registered
        );
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
     * Get calibration parameters from device.
     *
     * @param handle native pointer to libfreenect2::Freenect2Device
     * @return Calibration object containing camera parameters
     */
    private native Calibration nativeGetCalibration(long handle);

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

    /**
     * Get synchronized depth and color frames with registration applied.
     * Returns an array of [depthFrame, colorFrame]. The registered color data can be
     * retrieved separately using getRegisteredBuffer().
     *
     * @param handle native pointer to DeviceContext
     * @param timeoutMs timeout in milliseconds
     * @return Frame array [depthFrame, colorFrame] or null on timeout
     */
    private native Frame[] nativeGetSynchronizedFrames(long handle, long timeoutMs);

    /**
     * Get the registered color buffer (populated by the last getSynchronizedFrames call).
     * This copies the persistent registered buffer to the provided ByteBuffer.
     *
     * @param handle native pointer to DeviceContext
     * @param byteBuffer direct ByteBuffer to copy data into (must be 512x424x4 bytes)
     * @return true if successful, false otherwise
     */
    private native boolean nativeGetRegisteredBuffer(long handle, java.nio.ByteBuffer byteBuffer);

    /**
     * Get synchronized depth and color frames with registration applied.
     *
     * @param timeoutMs timeout in milliseconds
     * @return Frame array [depthFrame, colorFrame] or null on timeout
     * @throws IllegalStateException if device is closed or not streaming
     */
    public Frame[] getSynchronizedFrames(long timeoutMs) {
        checkNotClosed();
        if (!streaming) {
            throw new IllegalStateException("Device is not streaming");
        }
        return nativeGetSynchronizedFrames(nativeHandle, timeoutMs);
    }

    /**
     * Get the registered color buffer (512x424 BGRX format).
     * This must be called after getSynchronizedFrames() to get the registered data.
     *
     * @param byteBuffer direct ByteBuffer to copy data into (must be at least 512x424x4 bytes)
     * @return true if successful, false otherwise
     * @throws IllegalStateException if device is closed
     * @throws IllegalArgumentException if byteBuffer is null or not direct
     */
    public boolean getRegisteredBuffer(java.nio.ByteBuffer byteBuffer) {
        checkNotClosed();
        if (byteBuffer == null) {
            throw new IllegalArgumentException("ByteBuffer cannot be null");
        }
        if (!byteBuffer.isDirect()) {
            throw new IllegalArgumentException("ByteBuffer must be a direct buffer");
        }
        return nativeGetRegisteredBuffer(nativeHandle, byteBuffer);
    }

    /**
     * Get synchronized frames with registration applied (native implementation).
     * DEPRECATED: Use getSynchronizedFrames() + getRegisteredBuffer() instead.
     *
     * @param deviceHandle native pointer to DeviceContext
     * @param registrationHandle native pointer to libfreenect2::Registration
     * @param timeoutMs timeout in milliseconds
     * @return Object array [depthBuffer, colorBuffer, registeredBuffer] or null on timeout
     */
    private native Object[] nativeGetRegisteredFrames(long deviceHandle, long registrationHandle, long timeoutMs);
}
