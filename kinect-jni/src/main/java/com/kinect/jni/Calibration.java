package com.kinect.jni;

/**
 * Camera calibration parameters for Kinect V2.
 *
 * Contains intrinsic parameters (focal length, principal point, distortion coefficients)
 * for both the color and depth cameras.
 *
 * These parameters are used for:
 * - Converting 2D depth pixels to 3D coordinates (unprojection)
 * - Aligning depth and color frames (registration)
 * - Lens distortion correction
 */
public class Calibration {

    /**
     * Intrinsic parameters for a single camera.
     */
    public static class CameraParams {
        /** Focal length in X direction (pixels) */
        public final double fx;

        /** Focal length in Y direction (pixels) */
        public final double fy;

        /** Principal point X coordinate (pixels) */
        public final double cx;

        /** Principal point Y coordinate (pixels) */
        public final double cy;

        /** Radial distortion coefficient k1 */
        public final double k1;

        /** Radial distortion coefficient k2 */
        public final double k2;

        /** Tangential distortion coefficient p1 */
        public final double p1;

        /** Tangential distortion coefficient p2 */
        public final double p2;

        /** Radial distortion coefficient k3 */
        public final double k3;

        public CameraParams(double fx, double fy, double cx, double cy,
                            double k1, double k2, double p1, double p2, double k3) {
            this.fx = fx;
            this.fy = fy;
            this.cx = cx;
            this.cy = cy;
            this.k1 = k1;
            this.k2 = k2;
            this.p1 = p1;
            this.p2 = p2;
            this.k3 = k3;
        }

        @Override
        public String toString() {
            return String.format("CameraParams{fx=%.2f, fy=%.2f, cx=%.2f, cy=%.2f}", fx, fy, cx, cy);
        }
    }

    /** Color camera parameters */
    private final CameraParams colorParams;

    /** Depth camera parameters */
    private final CameraParams depthParams;

    /**
     * Package-private constructor called from native code.
     *
     * @param colorParams color camera parameters
     * @param depthParams depth camera parameters
     */
    Calibration(CameraParams colorParams, CameraParams depthParams) {
        this.colorParams = colorParams;
        this.depthParams = depthParams;
    }

    /**
     * Get color camera calibration parameters.
     *
     * @return color camera parameters
     */
    public CameraParams getColorParams() {
        return colorParams;
    }

    /**
     * Get depth camera calibration parameters.
     *
     * @return depth camera parameters
     */
    public CameraParams getDepthParams() {
        return depthParams;
    }

    @Override
    public String toString() {
        return String.format("Calibration{color=%s, depth=%s}", colorParams, depthParams);
    }
}
