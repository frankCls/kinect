package org.openrndr.kinect2.examples

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.*
import org.openrndr.extra.camera.Orbital
import org.openrndr.extra.gui.GUI
import org.openrndr.extra.parameters.BooleanParameter
import org.openrndr.kinect2.Kinect2
import org.openrndr.kinect2.Kinect2Manager
import org.openrndr.math.Vector3
import com.kinect.jni.PipelineType
import org.slf4j.LoggerFactory

/**
 * Kinect V2 Point Cloud Visualization Example
 *
 * Demonstrates real-time 3D point cloud rendering from Kinect V2 depth data.
 *
 * **What it shows**: Complete 3D point cloud from Kinect sensor with real RGB colors.
 * Shows ALL valid depth points mapped to their actual RGB colors from the color camera.
 *
 * Features:
 * - Complete 3D point cloud from all valid depth readings
 * - Real RGB colors mapped from Kinect's 1920x1080 color camera
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
 * - **F11**: Toggle UI panel visibility
 *
 * **Performance Notes**:
 * - Initial downsampling: 4x (processes every 4th pixel) = ~13K points
 * - Uses GPU VertexBuffer batching for 10-100x rendering speed vs individual draws
 * - Point cloud generation is CPU-bound, rendering is GPU-accelerated
 * - Adjust downsampling with +/- keys for performance/quality tradeoff
 * - 1x = ~217K points (slow), 2x = ~54K points, 4x = ~13K points (smooth)
 */
fun main() {
    val logger = LoggerFactory.getLogger("Kinect2PointCloudExample")

    println("=== Kinect V2 Point Cloud Example Starting ===")

    // Register shutdown hook for clean Ctrl+C handling
    Runtime.getRuntime().addShutdownHook(Thread {
        println("\n=== Shutdown hook triggered (Ctrl+C or system exit) ===")
        println("Cleaning up resources...")
    })

    // UI Settings
    val settings = object {
        @BooleanParameter("Show Coordinate Axes", order = 0)
        var showAxes = false

        @BooleanParameter("Show Reference Grid", order = 1)
        var showGrid = true
    }

    // Configuration constants
    val TARGET_FPS = 30.0
    val DEPTH_MIN = 50.0     // mm (0.5m)
    val DEPTH_MAX = 5000.0    // mm (5m)
    val DEPTH_RANGE = 1500.0  // mm (1.5m) - show points within this range from closest
    val DOWNSAMPLE_INITIAL = 4  // Balance between detail and performance
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

            // GPU vertex buffer for efficient point rendering
            var pointCloudVB: VertexBuffer? = null

            // Intrinsic camera parameters (Kinect V2 depth camera)
            // These are typical values; adjust based on calibration
            val fx = 365.0    // focal length in pixels (X)
            val fy = 365.0    // focal length in pixels (Y)
            val cx = 256.0    // principal point (X)
            val cy = 212.0    // principal point (Y)

            fun unProject(x: Int, y: Int, depth: Double): Vector3 {
                val z = depth / 1000.0  // Convert mm to meters
                val xVal = (x.toDouble() - cx) * z / fx
                val yVal = (y.toDouble() - cy) * z / fy
                return Vector3(xVal, -yVal, z)  // Negate Y for standard coordinate system
            }

            logger.info("=== Kinect V2 Point Cloud Visualization ===")
            println("Found ${Kinect2Manager.getDeviceCount()} Kinect V2 device(s)")
            println("\nControls:")
            println("  - Mouse: Orbit")
            println("  - Scroll: Zoom")
            println("  - W/S/A/D/E/Q: Move camera")
            println("  - R: Reset camera")
            println("  - +/-: Change downsampling")
            println("  - Space: Pause point cloud update")
            println("  - F11: Toggle UI panel")

            // Create Kinect2 extension
            val kinect = extend(Kinect2()) {
                enableDepth = true
                enableColor = true
                enableInfrared = false
                pipelineType = PipelineType.CPU  // Use CPU pipeline
            }

            // Setup 3D camera
            val camera = Orbital()
            // Point cloud Z values are positive (0.5m to 4.5m), so camera must be farther in positive Z
            camera.eye = Vector3(0.0, 0.0, 3.0)  // Camera position: 3m in front, looking back at point cloud
            camera.lookAt = Vector3(0.0, 0.0, 1.5)  // Look at center of typical depth range
            camera.fov = 60.0                     // Field of view
            camera.near = 0.01                    // Near clip plane
            camera.far = 10.0                     // Far clip plane (10m)

            extend(camera)

            // Setup GUI
            val gui = GUI()
            gui.add(settings, "Display Settings")
            extend(gui)

            // Handle window close event for clean shutdown
            window.closed.listen {
                logger.info("Window closed, requesting application exit...")
                application.exit()
            }

            keyboard.keyDown.listen { event ->
                when (event.name) {
                    "r" -> {
                        camera.eye = Vector3(0.0, 0.0, 3.0)
                        camera.lookAt = Vector3(0.0, 0.0, 1.5)
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
                drawer.clear(ColorRGBa.BLACK)

                // Get depth data and registered color buffer from kinect wrapper
                // The registered buffer is automatically populated via getSynchronizedFrames + getRegisteredBuffer
                val depthData = kinect.depthCamera.getDepthMillimeters()
                val registeredBuffer = kinect.getRegisteredColorBuffer()

                if (depthData != null && registeredBuffer != null && !isPaused) {
                    // Camera specs
                    val depthWidth = 512
                    val depthHeight = 424

                    // Debug: Check RGB values from center pixel
                    if (frameCount % 30 == 0) {
                        registeredBuffer.rewind()
                        val centerIdx = (depthHeight / 2 * depthWidth + depthWidth / 2) * 4  // BGRX = 4 bytes
                        val b = (registeredBuffer.get(centerIdx).toInt() and 0xFF)
                        val g = (registeredBuffer.get(centerIdx + 1).toInt() and 0xFF)
                        val r = (registeredBuffer.get(centerIdx + 2).toInt() and 0xFF)
                        logger.debug("Registration: center pixel RGB: ($r, $g, $b)")
                    }

                    // Generate point cloud with registered colors
                    points.clear()
                    colors.clear()
                    minDepth = DEPTH_MAX
                    maxDepthForCloud = 0.0

                    var validPoints = 0
                    depthData.rewind()
                    registeredBuffer.rewind()

                    for (y in 0 until depthHeight step downsample) {
                        for (x in 0 until depthWidth step downsample) {
                            val depthIdx = (y * depthWidth + x) * 4  // 4 bytes per float
                            if (depthIdx + 3 < depthData.capacity()) {
                                val depthFloat = depthData.getFloat(depthIdx)
                                val depthMm = depthFloat.toDouble()

                                // Show ALL valid points (just exclude NaN and zero)
                                if (depthMm > 0 && !depthMm.isNaN()) {
                                    validPoints++
                                    val point3D = unProject(x, y, depthMm)
                                    points.add(point3D)

                                    // Get RGB color from registered buffer (BGRX format, 4 bytes per pixel)
                                    val colorIdx = (y * depthWidth + x) * 4  // BGRX = 4 bytes per pixel
                                    val color = if (colorIdx + 2 < registeredBuffer.capacity()) {
                                        val b = (registeredBuffer[colorIdx].toInt() and 0xFF) / 255.0
                                        val g = (registeredBuffer[colorIdx + 1].toInt() and 0xFF) / 255.0
                                        val r = (registeredBuffer[colorIdx + 2].toInt() and 0xFF) / 255.0
                                        ColorRGBa(r, g, b)
                                    } else {
                                        ColorRGBa.WHITE  // Fallback
                                    }
                                    colors.add(color)

                                    // Track min/max for stats
                                    minDepth = minOf(minDepth, depthMm)
                                    maxDepthForCloud = maxOf(maxDepthForCloud, depthMm)
                                }
                            }
                        }
                    }
                    if (frameCount % 30 == 0) {
                        logger.debug("Generated ${points.size} points (validPoints=$validPoints), minDepth=${minDepth}mm, maxDepth=${maxDepthForCloud}mm")
                    }

                    // Draw 3D scene
                    drawer.isolated {
                        // The Orbital camera is automatically applied via extension
                        // No need to manually set view/projection matrices

                        // Draw reference grid (ground plane at Y=0)
                        if (settings.showGrid) {
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
                        }

                        // Draw coordinate axes
                        if (settings.showAxes) {
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
                        }

                        // Draw point cloud using GPU vertex buffer (much faster than individual draws)
                        if (points.isNotEmpty()) {
                            // Recreate vertex buffer if size changed
                            if (pointCloudVB == null || pointCloudVB!!.vertexCount != points.size) {
                                pointCloudVB?.destroy()
                                pointCloudVB = vertexBuffer(
                                    vertexFormat {
                                        position(3)
                                        color(4)
                                    },
                                    points.size
                                )
                            }

                            // Use local variable for closure (avoids smart cast issues)
                            val vb = pointCloudVB!!

                            // Upload point positions and colors to GPU
                            vb.put {
                                for (i in points.indices) {
                                    write(points[i])
                                    write(colors[i])
                                }
                            }

                            // Draw all points in single GPU call (10-100x faster than individual draws)
                            drawer.shadeStyle = shadeStyle {
                                fragmentTransform = """
                                    x_fill.rgb = va_color.rgb;
                                    x_fill.a = 1.0;
                                """
                            }
                            drawer.vertexBuffer(vb, DrawPrimitive.POINTS)
                            drawer.shadeStyle = null
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
                    drawer.text("Controls: Mouse=orbit, Scroll=zoom, WASD=move, EQ=up/down, R=reset, F11=UI", 10.0, height - 30.0)
                } else {
                    drawer.fill = ColorRGBa.RED
                    drawer.text("Waiting for depth/color frames...", 10.0, 30.0)
                }
            }
        }
    }

    println("=== Kinect V2 Point Cloud Example Finished ===")
}