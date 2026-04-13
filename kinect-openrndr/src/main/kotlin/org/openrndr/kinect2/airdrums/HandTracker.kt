package org.openrndr.kinect2.airdrums

import org.openrndr.math.Vector3
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Simple depth-based hand tracking for air drums.
 * 
 * Finds the closest points in the depth image, which typically correspond
 * to hands when the user is drumming in front of the Kinect.
 * 
 * This is a lightweight MVP approach - can be enhanced with MediaPipe later.
 */
class HandTracker : VelocityProvider {
    private val logger = LoggerFactory.getLogger(HandTracker::class.java)
    
    // Configuration
    var minDepth = 0.3  // Minimum depth in meters (ignore closer objects)
    var maxDepth = 1.5  // Maximum depth in meters (ignore farther objects)
    var minDistance = 0.15  // Minimum distance between hands in meters
    
    // Previous hand positions for velocity calculation
    private val handHistory = mutableListOf<List<HandPosition>>()
    private val maxHistoryFrames = 5
    
    /**
     * Detect hands from Kinect depth data.
     * 
     * Strategy: Find the top 2 closest points within the valid depth range,
     * ensuring they are sufficiently separated (to avoid detecting the same hand twice).
     * 
     * @param depthData Depth frame as ByteBuffer (512x424, float32 millimeters)
     * @param width Depth frame width (512)
     * @param height Depth frame height (424)
     * @return List of detected hand positions (up to 2)
     */
    fun detectHands(depthData: ByteBuffer, width: Int, height: Int): List<HandPosition> {
        val floatBuffer = depthData.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        
        // Find all valid depth points within range
        val candidates = mutableListOf<Triple<Int, Int, Float>>()  // (x, y, depth_mm)
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val depthMm = floatBuffer.get(idx)
                val depthM = depthMm / 1000.0f
                
                // Filter by depth range
                if (depthM in minDepth..maxDepth && depthMm > 0) {
                    candidates.add(Triple(x, y, depthMm))
                }
            }
        }
        
        if (candidates.isEmpty()) {
            logger.trace("No hands detected (no points in depth range)")
            return emptyList()
        }
        
        // Sort by depth (closest first)
        candidates.sortBy { it.third }
        
        // Find the two closest points that are sufficiently separated
        val hands = mutableListOf<HandPosition>()
        
        for (candidate in candidates.take(100)) {  // Check top 100 closest points
            val (x, y, depthMm) = candidate
            val depthM = depthMm / 1000.0
            
            // Convert pixel coordinates to 3D camera space (meters)
            // Kinect V2 depth camera FOV: 70.6° horizontal, 60° vertical
            val position = depthPixelTo3D(x, y, depthM, width, height)
            
            // Check if this point is far enough from already-detected hands
            val tooClose = hands.any { it.position.distanceTo(position) < minDistance }
            
            if (!tooClose) {
                // Determine handedness based on X position (left/right of center)
                val handedness = if (position.x < 0) Handedness.LEFT else Handedness.RIGHT
                
                hands.add(HandPosition(position, handedness, 1.0f))
                
                if (hands.size >= 2) break  // Found both hands
            }
        }
        
        // Update history for velocity calculation
        updateHistory(hands)
        
        logger.trace("Detected ${hands.size} hand(s)")
        return hands
    }
    
    /**
     * Convert depth pixel coordinates to 3D camera space.
     * 
     * Kinect V2 depth camera specs:
     * - Resolution: 512x424
     * - Horizontal FOV: 70.6°
     * - Vertical FOV: 60°
     * 
     * Camera coordinate system:
     * - X: left(-) to right(+)
     * - Y: down(-) to up(+)
     * - Z: depth into scene(+)
     * 
     * Origin is at camera center.
     */
    private fun depthPixelTo3D(px: Int, py: Int, depthM: Double, width: Int, height: Int): Vector3 {
        // Kinect V2 depth camera intrinsics (approximate)
        val fovH = Math.toRadians(70.6)
        val fovV = Math.toRadians(60.0)
        
        // Focal lengths in pixels
        val fx = (width / 2.0) / Math.tan(fovH / 2.0)
        val fy = (height / 2.0) / Math.tan(fovV / 2.0)
        
        // Principal point (center of image)
        val cx = width / 2.0
        val cy = height / 2.0
        
        // Convert to normalized camera coordinates
        val x = ((px - cx) / fx) * depthM
        val y = ((cy - py) / fy) * depthM  // Flip Y (image Y increases downward, camera Y increases upward)
        val z = depthM
        
        return Vector3(x, y, z)
    }
    
    /**
     * Update hand position history for velocity calculation.
     */
    private fun updateHistory(hands: List<HandPosition>) {
        handHistory.add(hands)
        if (handHistory.size > maxHistoryFrames) {
            handHistory.removeAt(0)
        }
    }
    
    /**
     * Calculate hand velocity (m/s) based on recent history.
     * Returns 0 if insufficient history.
     */
    override fun getHandVelocity(currentPosition: Vector3): Double {
        if (handHistory.size < 2) return 0.0
        
        // Find matching hand in previous frame (closest position)
        val prevFrame = handHistory[handHistory.size - 2]
        val prevHand = prevFrame.minByOrNull { it.position.distanceTo(currentPosition) } ?: return 0.0
        
        // Calculate distance traveled
        val distance = prevHand.position.distanceTo(currentPosition)
        
        // Assume 30 FPS (typical Kinect frame rate)
        val deltaTime = 1.0 / 30.0
        
        return distance / deltaTime
    }
    
    /**
     * Get hand velocity vector (m/s).
     */
    override fun getHandVelocityVector(currentPosition: Vector3): Vector3 {
        if (handHistory.size < 2) return Vector3.ZERO
        
        val prevFrame = handHistory[handHistory.size - 2]
        val prevHand = prevFrame.minByOrNull { it.position.distanceTo(currentPosition) } ?: return Vector3.ZERO
        
        val displacement = currentPosition - prevHand.position
        val deltaTime = 1.0 / 30.0
        
        return displacement / deltaTime
    }
}

/**
 * Detected hand position in 3D camera space.
 * 
 * @property position 3D position in meters (camera coordinate system)
 * @property handedness LEFT or RIGHT based on X position
 * @property confidence Detection confidence (0-1, currently always 1.0)
 */
data class HandPosition(
    val position: Vector3,
    val handedness: Handedness,
    val confidence: Float
)

/**
 * Hand side (left or right).
 */
enum class Handedness {
    LEFT,
    RIGHT,
    UNKNOWN
}

/**
 * Finger identifiers (for future MediaPipe integration).
 */
enum class Finger {
    THUMB,
    INDEX,
    MIDDLE,
    RING,
    PINKY
}
