package com.kinect.jni;

import java.nio.ByteBuffer;

/**
 * Represents a frame of data from the Kinect V2.
 *
 * Frames contain raw sensor data (color, depth, or IR) along with metadata
 * like dimensions, format, and timestamp.
 *
 * Frame data is accessible via a direct ByteBuffer for zero-copy performance.
 * The frame must be released when no longer needed.
 */
public class Frame implements AutoCloseable {

    /**
     * Native pointer to libfreenect2::Frame object.
     */
    private long nativeHandle;

    /**
     * Type of frame (Color, Depth, or IR).
     */
    private final FrameType type;

    /**
     * Frame width in pixels.
     */
    private final int width;

    /**
     * Frame height in pixels.
     */
    private final int height;

    /**
     * Bytes per pixel.
     */
    private final int bytesPerPixel;

    /**
     * Frame timestamp (microseconds since device start).
     */
    private final long timestamp;

    /**
     * Frame sequence number.
     */
    private final long sequence;

    /**
     * Direct ByteBuffer pointing to frame data.
     * This provides zero-copy access to native memory.
     */
    private ByteBuffer data;

    /**
     * Track whether this frame has been closed.
     */
    private boolean closed = false;

    /**
     * Package-private constructor called from native code.
     *
     * @param nativeHandle pointer to libfreenect2::Frame
     * @param type frame type
     * @param width frame width
     * @param height frame height
     * @param bytesPerPixel bytes per pixel
     * @param timestamp frame timestamp
     * @param sequence frame sequence number
     */
    Frame(long nativeHandle, FrameType type, int width, int height,
          int bytesPerPixel, long timestamp, long sequence) {
        this.nativeHandle = nativeHandle;
        this.type = type;
        this.width = width;
        this.height = height;
        this.bytesPerPixel = bytesPerPixel;
        this.timestamp = timestamp;
        this.sequence = sequence;

        // Get direct ByteBuffer from native memory
        this.data = nativeGetFrameData(nativeHandle);
    }

    /**
     * Get the frame type.
     *
     * @return frame type (Color, Depth, or IR)
     */
    public FrameType getType() {
        return type;
    }

    /**
     * Get frame width in pixels.
     *
     * @return width (1920 for color, 512 for depth/IR)
     */
    public int getWidth() {
        return width;
    }

    /**
     * Get frame height in pixels.
     *
     * @return height (1080 for color, 424 for depth/IR)
     */
    public int getHeight() {
        return height;
    }

    /**
     * Get bytes per pixel.
     *
     * @return bytes per pixel (4 for BGRX color, 4 for depth/IR)
     */
    public int getBytesPerPixel() {
        return bytesPerPixel;
    }

    /**
     * Get frame timestamp in microseconds.
     *
     * @return timestamp since device start
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * Get frame sequence number.
     *
     * @return sequence number (increments with each frame)
     */
    public long getSequence() {
        return sequence;
    }

    /**
     * Get the total size of the frame data in bytes.
     *
     * @return width * height * bytesPerPixel
     */
    public int getDataSize() {
        return width * height * bytesPerPixel;
    }

    /**
     * Get direct access to frame data as a ByteBuffer.
     *
     * This buffer points directly to native memory for zero-copy performance.
     * The buffer is read-only and remains valid until the frame is closed.
     *
     * @return direct ByteBuffer containing frame data
     * @throws IllegalStateException if frame is closed
     */
    public ByteBuffer getData() {
        checkNotClosed();
        return data;
    }

    /**
     * Check if this frame has been closed.
     *
     * @return true if closed, false otherwise
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Release the frame and its native resources.
     * After calling this, the frame data is no longer accessible.
     */
    @Override
    public void close() {
        if (!closed) {
            if (nativeHandle != 0) {
                nativeReleaseFrame(nativeHandle);
                nativeHandle = 0;
            }
            data = null;
            closed = true;
        }
    }

    /**
     * Ensure frame is not closed before operations.
     */
    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("Frame has been closed");
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
        return String.format("Frame{type=%s, size=%dx%d, bpp=%d, timestamp=%d, sequence=%d, closed=%b}",
                type, width, height, bytesPerPixel, timestamp, sequence, closed);
    }

    // Native method declarations

    /**
     * Get frame data as a direct ByteBuffer.
     *
     * @param handle native pointer to libfreenect2::Frame
     * @return direct ByteBuffer pointing to frame data
     */
    private native ByteBuffer nativeGetFrameData(long handle);

    /**
     * Release a native frame.
     *
     * @param handle native pointer to libfreenect2::Frame
     */
    private native void nativeReleaseFrame(long handle);
}
