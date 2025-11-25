package org.openrndr.kinect2.examples

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.isolated
import org.openrndr.extra.camera.Orbital
import org.openrndr.kinect2.Kinect2
import org.openrndr.kinect2.Kinect2Manager
import org.openrndr.math.Vector3
import com.kinect.jni.PipelineType

/**
 * Kinect V2 Point Cloud Visualization Example
 *
 * Demonstrates real-time 3D point cloud rendering from Kinect V2 depth data.
 *
 * **What it shows**: Point cloud of the CLOSEST object before the sensor.
 * Only points within 1.5m of the nearest detected surface are displayed,
 * effectively isolating the foreground object from the background.
 *
 * Features:
 * - Depth-based point cloud generation focused on closest object
 * - Color mapping from heatmap (blue→cyan→green→yellow→red)
 * - Interactive 3D camera controls
 * - Real-time statistics display
 * - Performance optimization via downsampling
 *
 * Controls:
 * - **Mouse drag**: Orbit camera around the scene
 * - **Mouse scroll**: Zoom in/out
 * - **W/S/A/D/E/Q**: Move camera
 * - **R**: Reset camera to default position
 * - **+/-**: Increase/decrease downsampling for performance
 * - **Space**: Pause/resume point cloud updates
 *
 * **Color Mapping** (relative to closest object):
 * - Blue: Nearest surface on the object
 * - Cyan, Green, Yellow: Mid-range depths on the object
 * - Red: Farthest visible points (up to 1.5m from nearest surface)
 *
 * **Performance Notes**:
 * - Initial downsampling: 2x (processes every 2nd pixel)
 * - Downsampling 2x reduces point count by 75% but retains detail
 * - Point cloud generation is CPU-bound (not GPU)
 * - For optimal performance, keep downsampling ≥ 2x
 */
fun main() {
    // Configuration constants
    val TARGET_FPS = 30.0
    val DEPTH_MIN = 500.0     // mm (0.5m)
    val DEPTH_MAX = 5000.0    // mm (5m)
    val DEPTH_RANGE = 1500.0  // mm (1.5m) - show points within this range from closest
    val DOWNSAMPLE_INITIAL = 2  // Lower initial downsampling for more points
    val DOWNSAMPLE_MIN = 1
    val DOWNSAMPLE_MAX = 8

    application {
        configure {
            width = 1280
            height = 720
            title = "Kinect V2 Point Cloud"
        }

        program {
            // Check for devices
            if (!Kinect2Manager.hasDevices()) {
                println("ERROR: No Kinect V2 devices found!")
                application.exit()
                return@program
            }

            // Timing
            var frameCount = 0
            var lastFpsTime = System.currentTimeMillis()
            var fps = 0.0

            // Data storage
            val points = mutableListOf<Vector3>()
            val colors = mutableListOf<ColorRGBa>()
            var minDepth = DEPTH_MIN
            var maxDepthForCloud = DEPTH_MAX
            var downsample = DOWNSAMPLE_INITIAL
            var isPaused = false

            // Intrinsic camera parameters (Kinect V2 depth camera)
            // These are typical values; adjust based on calibration
            val fx = 365.0    // focal length in pixels (X)
            val fy = 365.0    // focal length in pixels (Y)
            val cx = 256.0    // principal point (X)
            val cy = 212.0    // principal point (Y)

            fun unproject(x: Int, y: Int, depth: Double): Vector3 {
                val z = depth / 1000.0  // Convert mm to meters
                val xVal = (x.toDouble() - cx) * z / fx
                val yVal = (y.toDouble() - cy) * z / fy
                return Vector3(xVal, -yVal, z)  // Negate Y for standard coordinate system
            }

            fun getColorForDepth(depth: Double, minD: Double, maxD: Double): ColorRGBa {
                val normalized = (depth - minD) / (maxD - minD).coerceAtLeast(0.001)
                return when {
                    normalized < 0.25 -> {
                        // Blue to Cyan
                        val t = normalized / 0.25
                        ColorRGBa(0.0, t, 1.0)
                    }
                    normalized < 0.5 -> {
                        // Cyan to Green
                        val t = (normalized - 0.25) / 0.25
                        ColorRGBa(0.0, 1.0, 1.0 - t)
                    }
                    normalized < 0.75 -> {
                        // Green to Yellow
                        val t = (normalized - 0.5) / 0.25
                        ColorRGBa(t, 1.0, 0.0)
                    }
                    else -> {
                        // Yellow to Red
                        val t = (normalized - 0.75) / 0.25
                        ColorRGBa(1.0, 1.0 - t, 0.0)
                    }
                }
            }

            println("=== Kinect V2 Point Cloud Visualization ===")
            println("Found ${Kinect2Manager.getDeviceCount()} Kinect V2 device(s)")
            println("\nControls:")
            println("  - Mouse: Orbit")
            println("  - Scroll: Zoom")
            println("  - W/S/A/D/E/Q: Move camera")
            println("  - R: Reset camera")
            println("  - +/-: Change downsampling")
            println("  - Space: Pause point cloud update")

            // Create Kinect2 extension
            val kinect = extend(Kinect2()) {
                enableDepth = true
                enableColor = true
                enableInfrared = false
                pipelineType = PipelineType.CPU  // Use CPU pipeline
            }

            // Setup 3D camera
            val camera = Orbital()
            camera.eye = Vector3(0.0, 0.0, -2.0)  // Camera position: 2m back from origin
            camera.lookAt = Vector3.ZERO          // Look at origin
            camera.fov = 60.0                     // Field of view
            camera.near = 0.01                    // Near clip plane
            camera.far = 10.0                     // Far clip plane (10m)

            extend(camera)

            keyboard.keyDown.listen { event ->
                when (event.name) {
                    "r" -> {
                        camera.eye = Vector3(0.0, 0.0, -2.0)
                        camera.lookAt = Vector3.ZERO
                    }
                    "+" -> downsample = (downsample - 1).coerceAtLeast(DOWNSAMPLE_MIN)
                    "-" -> downsample = (downsample + 1).coerceAtMost(DOWNSAMPLE_MAX)
                    " " -> isPaused = !isPaused
                }
            }

            extend {
                // FPS calculation
                frameCount++
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastFpsTime >= 1000) {
                    fps = frameCount * 1000.0 / (currentTime - lastFpsTime)
                    frameCount = 0
                    lastFpsTime = currentTime
                }

                // Background
                drawer.background(ColorRGBa.BLACK)

                // Step 1: Get depth data from camera buffer (thread-safe)
                val depthData = kinect.depthCamera.getDataBuffer()

                if (depthData != null && kinect.depthCamera.framesReceived > 0) {
                    // Step 2: Generate point cloud
                    points.clear()
                    colors.clear()
                    minDepth = Double.MAX_VALUE
                    var maxDepth = 0.0

                    // Depth camera specs
                    val depthWidth = 512
                    val depthHeight = 424

                    for (y in 0 until depthHeight step downsample) {
                        for (x in 0 until depthWidth step downsample) {
                            val depthIdx = (y * depthWidth + x) * 2
                            if (depthIdx + 1 < depthData.capacity()) {
                                val shortDepth = depthData.getShort(depthIdx)
                                val depthMm = shortDepth.toDouble()

                                if (depthMm > 0 && !depthMm.isNaN()) {
                                    minDepth = minOf(minDepth, depthMm)
                                    maxDepth = maxOf(maxDepth, depthMm)
                                }
                            }
                        }
                    }

                    // Focus on closest object: show points within DEPTH_RANGE of minimum
                    maxDepthForCloud = minOf(minDepth + DEPTH_RANGE, DEPTH_MAX)

                    if (!isPaused) {
                        for (y in 0 until depthHeight step downsample) {
                            for (x in 0 until depthWidth step downsample) {
                                val depthIdx = (y * depthWidth + x) * 2
                                if (depthIdx + 1 < depthData.capacity()) {
                                    val shortDepth = depthData.getShort(depthIdx)
                                    val depthMm = shortDepth.toDouble()

                                    // Filter: only show closest object (within DEPTH_RANGE from minimum)
                                    if (depthMm >= minDepth && depthMm <= maxDepthForCloud && !depthMm.isNaN()) {
                                        val point3D = unproject(x, y, depthMm)
                                        points.add(point3D)
                                        colors.add(getColorForDepth(depthMm, minDepth, maxDepthForCloud))
                                    }
                                }
                            }
                        }
                    }

                    // Step 3: Draw 3D scene
                    drawer.isolated {
                        // The Orbital camera is automatically applied via extension
                        // No need to manually set view/projection matrices

                        // Draw reference grid (ground plane at Y=0)
                        drawer.stroke = ColorRGBa.GRAY.opacify(0.3)
                        drawer.strokeWeight = 1.0
                        val gridSize = 2.0
                        val gridStep = 0.2
                        for (i in (-10..10)) {
                            val pos = i * gridStep
                            // X lines
                            drawer.lineSegment(Vector3(pos, 0.0, -gridSize), Vector3(pos, 0.0, gridSize))
                            // Z lines
                            drawer.lineSegment(Vector3(-gridSize, 0.0, pos), Vector3(gridSize, 0.0, pos))
                        }

                        // Draw coordinate axes
                        drawer.strokeWeight = 2.0
                        // X axis (red)
                        drawer.stroke = ColorRGBa.RED
                        drawer.lineSegment(Vector3.ZERO, Vector3(0.5, 0.0, 0.0))
                        // Y axis (green)
                        drawer.stroke = ColorRGBa.GREEN
                        drawer.lineSegment(Vector3.ZERO, Vector3(0.0, 0.5, 0.0))
                        // Z axis (blue)
                        drawer.stroke = ColorRGBa.BLUE
                        drawer.lineSegment(Vector3.ZERO, Vector3(0.0, 0.0, 0.5))

                        // Draw point cloud as dots
                        if (points.isNotEmpty()) {
                            for (i in points.indices) {
                                drawer.fill = colors[i]
                                drawer.stroke = null
                                // Draw small rectangles as point surrogates (circles in 3D space aren't natively supported)
                                val pos = points[i]
                                val scale = 0.003
                                drawer.rectangle(pos.x - scale, pos.y - scale, scale * 2, scale * 2)
                            }
                        }
                    }

                    // Draw 2D overlay (stats)
                    drawer.fill = ColorRGBa.WHITE
                    drawer.text("FPS: ${"%.1f".format(fps)}", 10.0, 30.0)
                    drawer.text("Points: ${points.size}", 10.0, 50.0)
                    drawer.text("Closest depth: ${"%.0f".format(minDepth)}mm (${"%.2f".format(minDepth / 1000.0)}m)", 10.0, 70.0)
                    drawer.text("Depth range: ${"%.0f".format(minDepth)}mm - ${"%.0f".format(maxDepthForCloud)}mm", 10.0, 90.0)
                    drawer.text("Downsample: ${downsample}x", 10.0, 110.0)

                    // Instructions
                    drawer.text("Controls: Mouse=orbit, Scroll=zoom, WASD=move, EQ=up/down, R=reset", 10.0, height - 30.0)
                } else {
                    drawer.fill = ColorRGBa.RED
                    drawer.text("Waiting for depth/color frames...", 10.0, 30.0)
                }
            }
        }
    }
}
