package org.openrndr.kinect2.airdrums

import com.kinect.jni.PipelineType
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.isolated
import org.openrndr.kinect2.Kinect2
import org.openrndr.kinect2.Kinect2Manager
import org.openrndr.math.Vector2
import org.slf4j.LoggerFactory

/**
 * Air Drums - Interactive drum kit using Kinect V2 + MediaPipe hand tracking.
 *
 * Uses the Kinect V2 depth + color cameras with MediaPipe (via Python subprocess)
 * for reliable hand detection. The registered color buffer (depth-aligned 512x424)
 * is sent to MediaPipe for hand landmark detection. Depth data provides accurate
 * 3D positioning. Falls back to depth-only tracking if MediaPipe is unavailable.
 *
 * Controls:
 *   1: Load Standard kit (6 pieces)
 *   2: Load Minimal kit (4 pieces)
 *   Space: Toggle depth/color background
 *   D: Toggle debug info
 *   M: List MIDI devices
 *   R: Reset hit detector
 *   T: Toggle between MediaPipe and depth-only tracking
 *   Arrow keys: Adjust calibration offset (when MediaPipe active)
 *   0: Reset calibration offset
 *   ESC: Quit
 */
fun main() = application {
    // Display at 2x depth resolution for comfortable viewing
    val displayScale = 2.0
    val depthW = 512
    val depthH = 424

    configure {
        width = (depthW * displayScale).toInt()
        height = (depthH * displayScale).toInt()
        title = "Air Drums - Kinect V2 + MediaPipe"
    }

    program {
        val logger = LoggerFactory.getLogger("AirDrumsApp")

        // Check for Kinect devices
        if (!Kinect2Manager.hasDevices()) {
            logger.error("No Kinect V2 devices found!")
            application.exit()
            return@program
        }

        logger.info("=== Air Drums Starting ===")
        logger.info("Kinect V2 devices: ${Kinect2Manager.getDeviceCount()}")

        // Initialize Kinect extension (depth + color for MediaPipe)
        val kinect = extend(Kinect2()) {
            enableDepth = true
            enableColor = true       // Needed for MediaPipe + registered color
            enableInfrared = false
            pipelineType = PipelineType.CPU
        }

        // Initialize air drums components
        val drumKit = DrumKit()
        drumKit.loadPreset(DrumKitPreset.STANDARD)

        // Coordinate mapper for RGB -> depth -> 3D conversion
        val coordinateMapper = CoordinateMapper()

        // Both trackers available - user can toggle with 'T'
        val depthHandTracker = HandTracker()
        val pythonHandTracker = PythonHandTracker(coordinateMapper)

        // Start with depth tracker, attempt to launch MediaPipe
        var useMediaPipe = false
        val hitDetector = HitDetector(drumKit, depthHandTracker)

        val midiController = MidiController()

        // Auto-connect to MIDI device
        logger.info("Available MIDI devices:")
        midiController.listDevices().forEach { logger.info("  - $it") }

        if (midiController.connectToFirstAvailable()) {
            logger.info("Connected to MIDI: ${midiController.currentDeviceName}")
        } else {
            logger.warn("No MIDI devices available - hits will be logged but no sound")
        }

        // Try to start MediaPipe tracker
        logger.info("Starting MediaPipe hand tracker...")
        if (pythonHandTracker.start()) {
            useMediaPipe = true
            hitDetector.setVelocityProvider(pythonHandTracker)
            logger.info("MediaPipe hand tracking ACTIVE")
        } else {
            logger.warn("MediaPipe unavailable - using depth-only tracking")
            logger.warn("Run: kinect-openrndr/scripts/setup_python.sh to install MediaPipe")
        }

        // Visual state
        var showDepthView = true    // true=depth background, false=color background
        var showDebugInfo = true
        val recentHits = mutableListOf<Pair<DrumHit, Double>>()  // hit, timestamp
        val hitFlashDuration = 0.5  // seconds

        // Track detected hands for rendering
        var lastHands = emptyList<HandPosition>()

        // Keyboard controls
        keyboard.keyDown.listen { event ->
            when (event.name) {
                "1" -> {
                    drumKit.loadPreset(DrumKitPreset.STANDARD)
                    hitDetector.reset()
                    logger.info("Loaded STANDARD drum kit")
                }
                "2" -> {
                    drumKit.loadPreset(DrumKitPreset.MINIMAL)
                    hitDetector.reset()
                    logger.info("Loaded MINIMAL drum kit")
                }
                "space" -> {
                    showDepthView = !showDepthView
                    logger.info("Background: ${if (showDepthView) "depth" else "color"}")
                }
                "d" -> {
                    showDebugInfo = !showDebugInfo
                    logger.info("Debug info: $showDebugInfo")
                }
                "m" -> {
                    logger.info("=== MIDI Devices ===")
                    midiController.listDevices().forEach { logger.info("  $it") }
                }
                "r" -> {
                    hitDetector.reset()
                    logger.info("HitDetector reset")
                }
                "t" -> {
                    // Toggle tracking mode
                    if (useMediaPipe) {
                        useMediaPipe = false
                        hitDetector.setVelocityProvider(depthHandTracker)
                        logger.info("Switched to DEPTH-ONLY tracking")
                    } else {
                        if (pythonHandTracker.isRunning) {
                            useMediaPipe = true
                            hitDetector.setVelocityProvider(pythonHandTracker)
                            logger.info("Switched to MEDIAPIPE tracking")
                        } else {
                            logger.warn("MediaPipe not available - starting...")
                            if (pythonHandTracker.start()) {
                                useMediaPipe = true
                                hitDetector.setVelocityProvider(pythonHandTracker)
                                logger.info("MediaPipe tracking ACTIVE")
                            } else {
                                logger.error("Failed to start MediaPipe")
                            }
                        }
                    }
                }
                // Calibration offset adjustment (arrow keys)
                "arrow-left" -> coordinateMapper.adjustOffset(-2.0, 0.0)
                "arrow-right" -> coordinateMapper.adjustOffset(2.0, 0.0)
                "arrow-up" -> coordinateMapper.adjustOffset(0.0, -2.0)
                "arrow-down" -> coordinateMapper.adjustOffset(0.0, 2.0)
                "0" -> coordinateMapper.resetCalibration()
            }
        }

        // ── Helper: convert 3D camera-space position to screen pixel ──
        fun toScreen(pos: org.openrndr.math.Vector3): Vector2 {
            val fovH = Math.toRadians(70.6)
            val halfW = depthW / 2.0
            val fx = halfW / Math.tan(fovH / 2.0)
            val screenX = (pos.x * fx / pos.z + halfW) * displayScale

            val fovV = Math.toRadians(60.0)
            val halfH = depthH / 2.0
            val fy = halfH / Math.tan(fovV / 2.0)
            val screenY = (-pos.y * fy / pos.z + halfH) * displayScale

            return Vector2(screenX, screenY)
        }

        // Project a radius (meters) at a given depth to screen pixels
        fun radiusToScreen(radiusM: Double, depthM: Double): Double {
            val fovH = Math.toRadians(70.6)
            val fx = (depthW / 2.0) / Math.tan(fovH / 2.0)
            return (radiusM * fx / depthM) * displayScale
        }

        extend {
            drawer.clear(ColorRGBa.fromHex(0x1a1a2e))

            // ── Background (depth or color) ──
            if (showDepthView) {
                drawer.image(
                    kinect.depthCamera.currentFrame,
                    0.0, 0.0,
                    width.toDouble(), height.toDouble()
                )
            } else if (kinect.enableColor) {
                // Show color camera (stretched to window)
                drawer.image(
                    kinect.colorCamera.currentFrame,
                    0.0, 0.0,
                    width.toDouble(), height.toDouble()
                )
            }

            // ── Hand tracking + hit detection ──
            val depthBuffer = kinect.depthCamera.getDepthMillimeters()

            if (useMediaPipe && pythonHandTracker.isRunning) {
                // MediaPipe path: use registered color buffer (512x424 aligned to depth)
                val registeredColor = kinect.getRegisteredColorBuffer()
                if (registeredColor != null) {
                    lastHands = pythonHandTracker.detectHands(
                        colorData = registeredColor,
                        width = depthW,
                        height = depthH,
                        depthData = depthBuffer,
                        depthWidth = depthW,
                        depthHeight = depthH
                    )
                }
            } else {
                // Depth-only fallback
                if (depthBuffer != null) {
                    lastHands = depthHandTracker.detectHands(depthBuffer, depthW, depthH)
                }
            }

            // Run hit detection on whatever hands we found
            val hits = hitDetector.update(lastHands)

            hits.forEach { hit ->
                midiController.playHit(hit)
                recentHits.add(Pair(hit, seconds))
                logger.info("HIT ${hit.zone.name}  vel=${hit.velocity}  speed=${"%.2f".format(hit.hitVelocity)} m/s")
            }

            // Clean up old flashes
            recentHits.removeIf { (_, ts) -> seconds - ts > hitFlashDuration }

            // ── Draw drum zones ──
            for (zone in drumKit.zones) {
                val center = toScreen(zone.position)
                val r = radiusToScreen(zone.radius, zone.position.z)

                // Check if recently hit
                val hitEntry = recentHits.firstOrNull { it.first.zone.id == zone.id }
                val flashProgress = if (hitEntry != null) {
                    (1.0 - (seconds - hitEntry.second) / hitFlashDuration).coerceIn(0.0, 1.0)
                } else 0.0

                drawer.isolated {
                    val baseColor = zone.color.opacify(0.15 + flashProgress * 0.5)
                    val flashColor = ColorRGBa.WHITE.opacify(flashProgress * 0.7)
                    drawer.fill = baseColor.mix(flashColor, flashProgress)
                    drawer.stroke = zone.color.mix(ColorRGBa.WHITE, flashProgress)
                    drawer.strokeWeight = if (flashProgress > 0) 3.0 else 1.5
                    drawer.circle(center, r)
                }

                // Zone label
                drawer.isolated {
                    drawer.fill = ColorRGBa.WHITE.opacify(0.9)
                    drawer.stroke = null
                    drawer.text(zone.name, center.x - zone.name.length * 3.5, center.y + 4.0)
                }
            }

            // ── Draw hand markers ──
            for (hand in lastHands) {
                val pos = toScreen(hand.position)
                val color = when (hand.handedness) {
                    Handedness.LEFT -> ColorRGBa.CYAN
                    Handedness.RIGHT -> ColorRGBa.MAGENTA
                    Handedness.UNKNOWN -> ColorRGBa.WHITE
                }

                // Outer glow
                drawer.isolated {
                    drawer.fill = color.opacify(0.2)
                    drawer.stroke = null
                    drawer.circle(pos, 24.0)
                }
                // Inner dot
                drawer.isolated {
                    drawer.fill = color
                    drawer.stroke = ColorRGBa.WHITE
                    drawer.strokeWeight = 2.0
                    drawer.circle(pos, 10.0)
                }
                // Label
                drawer.isolated {
                    drawer.fill = color
                    drawer.stroke = null
                    val label = if (hand.handedness == Handedness.LEFT) "L" else "R"
                    drawer.text(label, pos.x + 14.0, pos.y + 4.0)
                }
            }

            // ── Debug HUD ──
            if (showDebugInfo) {
                drawer.isolated {
                    drawer.fill = ColorRGBa.WHITE
                    drawer.stroke = null

                    val fps = if (seconds > 0.5) frameCount / seconds else 0.0
                    val trackingMode = if (useMediaPipe) "MediaPipe" else "Depth-only"
                    val mpStatus = when {
                        useMediaPipe && pythonHandTracker.isRunning -> "running"
                        useMediaPipe -> "ERROR"
                        else -> "off"
                    }

                    val lines = listOf(
                        "FPS: ${"%.0f".format(fps)}",
                        "MIDI: ${if (midiController.connected) midiController.currentDeviceName else "disconnected"}",
                        "Kit: ${drumKit.zones.size} zones",
                        "Hands: ${lastHands.size}",
                        "Tracking: $trackingMode ($mpStatus)",
                        "Offset: (${"%.0f".format(coordinateMapper.offsetX)}, ${"%.0f".format(coordinateMapper.offsetY)})",
                        "",
                        "1/2:kit  Space:bg  D:debug  T:tracker  R:reset",
                        "Arrows:calibrate  0:reset-cal  M:midi"
                    )

                    var y = height - 10.0
                    for (line in lines.reversed()) {
                        drawer.text(line, 10.0, y)
                        y -= 18.0
                    }
                }
            }
        }

        // Cleanup
        window.closed.listen {
            pythonHandTracker.stop()
            midiController.close()
            logger.info("Air Drums shutdown complete")
        }
    }
}
