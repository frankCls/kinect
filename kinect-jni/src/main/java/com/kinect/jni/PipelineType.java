package com.kinect.jni;

/**
 * Packet pipeline types for Kinect V2 frame processing.
 *
 * The pipeline determines how depth and IR frames are processed:
 * - CPU: Software-based processing (safe for creative frameworks like OPENRNDR)
 * - OPENGL: GPU-accelerated processing (faster but may conflict with other OpenGL contexts)
 */
public enum PipelineType {
    /**
     * CPU-based packet pipeline.
     * Uses software processing for depth/IR frames.
     *
     * Pros:
     * - No OpenGL context conflicts
     * - Works reliably with OPENRNDR, Processing, and other frameworks
     * - Thread-safe from any context
     *
     * Cons:
     * - Slower depth processing (~30Hz vs ~296Hz with OpenGL)
     * - Higher CPU usage
     *
     * Recommended for: Creative coding frameworks, multi-window applications
     */
    CPU(0),

    /**
     * OpenGL-accelerated packet pipeline.
     * Uses GPU shaders for depth/IR processing.
     *
     * Pros:
     * - Much faster depth processing (~296Hz)
     * - Lower CPU usage
     * - Better for real-time applications
     *
     * Cons:
     * - Requires OpenGL context initialization on macOS main thread
     * - May conflict with creative framework GL contexts (OPENRNDR, Processing)
     * - Thread-sensitive
     *
     * Recommended for: Standalone applications, maximum performance needs
     */
    OPENGL(1);

    private final int nativeValue;

    PipelineType(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    /**
     * Get the native integer value for JNI communication.
     * @return native pipeline type value
     */
    public int getNativeValue() {
        return nativeValue;
    }

    /**
     * Convert native value back to enum.
     * @param nativeValue the native integer value
     * @return corresponding PipelineType
     * @throws IllegalArgumentException if value is invalid
     */
    public static PipelineType fromNativeValue(int nativeValue) {
        for (PipelineType type : values()) {
            if (type.nativeValue == nativeValue) {
                return type;
            }
        }
        throw new IllegalArgumentException("Invalid pipeline type: " + nativeValue);
    }
}
