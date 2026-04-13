package org.openrndr.kinect2.airdrums

import org.openrndr.math.Vector3
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Maps between RGB camera coordinates and depth camera coordinates.
 *
 * The Kinect V2 has two cameras with different resolutions and FOVs:
 *   - RGB: 1920x1080, ~84.1° H x 53.8° V (color camera)
 *   - Depth: 512x424, ~70.6° H x 60.0° V (IR/depth camera)
 *
 * When using the registered color buffer (512x424 BGRX aligned to depth space),
 * the mapping is trivial: normalized coordinates map directly to depth pixels.
 *
 * For raw RGB frames, we need to account for the resolution and FOV differences.
 *
 * The mapper supports manual calibration offsets that the user can adjust
 * at runtime with arrow keys to fine-tune alignment.
 */
class CoordinateMapper {
    private val logger = LoggerFactory.getLogger(CoordinateMapper::class.java)

    // Depth camera specs
    val depthWidth = 512
    val depthHeight = 424
    private val depthFovH = Math.toRadians(70.6)
    private val depthFovV = Math.toRadians(60.0)

    // Depth camera intrinsics
    private val depthFx = (depthWidth / 2.0) / Math.tan(depthFovH / 2.0)
    private val depthFy = (depthHeight / 2.0) / Math.tan(depthFovV / 2.0)
    private val depthCx = depthWidth / 2.0
    private val depthCy = depthHeight / 2.0

    // Manual calibration offsets (pixels in depth space)
    // Adjustable at runtime via keyboard
    var offsetX = 0.0
    var offsetY = 0.0

    // Whether we're using registered color (depth-aligned 512x424)
    // vs raw RGB (1920x1080) input
    var useRegisteredColor = true

    /**
     * Convert MediaPipe normalized RGB coordinates to 3D camera space position.
     *
     * MediaPipe outputs landmarks as normalized [0,1] coordinates in the input image.
     * We convert these to depth pixel coordinates, look up the depth value, and
     * project into 3D camera space.
     *
     * @param normX Normalized X coordinate from MediaPipe (0=left, 1=right)
     * @param normY Normalized Y coordinate from MediaPipe (0=top, 1=bottom)
     * @param inputWidth Width of the image that was sent to MediaPipe
     * @param inputHeight Height of the image that was sent to MediaPipe
     * @param depthData Raw depth data (float32 mm, 512x424) or null
     * @param depthW Depth frame width (512)
     * @param depthH Depth frame height (424)
     * @return 3D position in camera space (meters), or null if no valid depth
     */
    fun rgbNormalizedTo3D(
        normX: Double,
        normY: Double,
        inputWidth: Int,
        inputHeight: Int,
        depthData: ByteBuffer?,
        depthW: Int = depthWidth,
        depthH: Int = depthHeight
    ): Vector3? {
        // Step 1: Convert normalized coordinates to depth pixel coordinates
        val depthPixel = if (useRegisteredColor) {
            // Registered color buffer is already aligned to depth space (512x424)
            // Normalized coordinates map directly to depth pixels
            Pair(
                (normX * depthW + offsetX).coerceIn(0.0, depthW - 1.0),
                (normY * depthH + offsetY).coerceIn(0.0, depthH - 1.0)
            )
        } else {
            // Raw RGB: scale from RGB resolution to depth resolution
            // Simple proportional mapping (approximate, ignores FOV difference)
            Pair(
                (normX * depthW + offsetX).coerceIn(0.0, depthW - 1.0),
                (normY * depthH + offsetY).coerceIn(0.0, depthH - 1.0)
            )
        }

        val px = depthPixel.first.toInt().coerceIn(0, depthW - 1)
        val py = depthPixel.second.toInt().coerceIn(0, depthH - 1)

        // Step 2: Look up depth value
        val depthMm = if (depthData != null) {
            lookupDepth(depthData, px, py, depthW, depthH)
        } else {
            // No depth data available - use a default depth estimate
            // 0.7m is typical arm's reach distance
            700.0f
        }

        if (depthMm <= 0f || depthMm.isNaN() || depthMm.isInfinite()) {
            // Try searching a small neighborhood for valid depth
            val fallbackDepth = searchNeighborhoodDepth(depthData, px, py, depthW, depthH, radius = 5)
            if (fallbackDepth <= 0f) {
                return null  // No valid depth anywhere nearby
            }
            return depthPixelTo3D(px, py, fallbackDepth.toDouble() / 1000.0, depthW, depthH)
        }

        val depthM = depthMm.toDouble() / 1000.0

        // Step 3: Project to 3D camera space
        return depthPixelTo3D(px, py, depthM, depthW, depthH)
    }

    /**
     * Look up depth value at a pixel position.
     * The depth buffer is flipped vertically during processing (see Kinect2DepthCamera),
     * but the raw depth buffer from getDepthMillimeters() is NOT flipped.
     */
    private fun lookupDepth(
        depthData: ByteBuffer,
        px: Int,
        py: Int,
        width: Int,
        height: Int
    ): Float {
        val floatBuffer = depthData.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val idx = py * width + px
        if (idx < 0 || idx >= floatBuffer.capacity()) return 0f
        return floatBuffer.get(idx)
    }

    /**
     * Search a small neighborhood for a valid depth value.
     * Useful when the exact pixel has no depth (common at hand edges).
     */
    private fun searchNeighborhoodDepth(
        depthData: ByteBuffer?,
        cx: Int,
        cy: Int,
        width: Int,
        height: Int,
        radius: Int
    ): Float {
        if (depthData == null) return 0f
        val floatBuffer = depthData.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

        var bestDepth = 0f
        var bestDist = Int.MAX_VALUE

        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue

                val idx = ny * width + nx
                if (idx < 0 || idx >= floatBuffer.capacity()) continue

                val d = floatBuffer.get(idx)
                if (d > 0f && !d.isNaN() && !d.isInfinite()) {
                    val dist = dx * dx + dy * dy
                    if (dist < bestDist) {
                        bestDist = dist
                        bestDepth = d
                    }
                }
            }
        }

        return bestDepth
    }

    /**
     * Convert depth pixel coordinates to 3D camera space.
     * Same projection as HandTracker.depthPixelTo3D but exposed publicly.
     */
    fun depthPixelTo3D(px: Int, py: Int, depthM: Double, width: Int = depthWidth, height: Int = depthHeight): Vector3 {
        val fx = (width / 2.0) / Math.tan(depthFovH / 2.0)
        val fy = (height / 2.0) / Math.tan(depthFovV / 2.0)
        val cx = width / 2.0
        val cy = height / 2.0

        val x = ((px - cx) / fx) * depthM
        val y = ((cy - py) / fy) * depthM  // Flip Y (image Y down, camera Y up)
        val z = depthM

        return Vector3(x, y, z)
    }

    /**
     * Adjust calibration offset. Called from keyboard handlers.
     */
    fun adjustOffset(dx: Double, dy: Double) {
        offsetX += dx
        offsetY += dy
        logger.info("Calibration offset: (${"%.1f".format(offsetX)}, ${"%.1f".format(offsetY)})")
    }

    /**
     * Reset calibration to defaults.
     */
    fun resetCalibration() {
        offsetX = 0.0
        offsetY = 0.0
        logger.info("Calibration reset to (0, 0)")
    }
}
