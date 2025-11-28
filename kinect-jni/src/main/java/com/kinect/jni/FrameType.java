package com.kinect.jni;

/**
 * Enumeration of Kinect V2 frame types.
 *
 * The Kinect V2 provides three main stream types:
 * - Color: 1920x1080 RGB frames
 * - Depth: 512x424 depth values in millimeters
 * - Infrared: 512x424 IR intensity values
 */
public enum FrameType {
    /**
     * Color frame (RGB, 1920x1080, ~30 FPS).
     */
    COLOR(1),

    /**
     * Infrared frame (16-bit IR intensity, 512x424, ~30 FPS).
     */
    IR(2),

    /**
     * Depth frame (16-bit depth in mm, 512x424, ~30 FPS).
     */
    DEPTH(4);

    /**
     * Native libfreenect2 frame type value.
     * These correspond to libfreenect2::Frame::Type enum.
     */
    private final int nativeValue;

    FrameType(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    /**
     * Get the native libfreenect2 frame type value.
     *
     * @return native enum value
     */
    public int getNativeValue() {
        return nativeValue;
    }

    /**
     * Create FrameType from native value.
     *
     * @param nativeValue libfreenect2 frame type value
     * @return corresponding FrameType enum
     * @throws IllegalArgumentException if value is invalid
     */
    public static FrameType fromNativeValue(int nativeValue) {
        for (FrameType type : values()) {
            if (type.nativeValue == nativeValue) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown frame type: " + nativeValue);
    }

    /**
     * Check if this frame type contains color information.
     *
     * @return true for COLOR frame
     */
    public boolean isColor() {
        return this == COLOR;
    }

    /**
     * Check if this frame type contains depth information.
     *
     * @return true for DEPTH frame
     */
    public boolean isDepth() {
        return this == DEPTH;
    }

    /**
     * Check if this frame type contains infrared information.
     *
     * @return true for IR frame
     */
    public boolean isInfrared() {
        return this == IR;
    }
}
