package org.openrndr.kinect2.airdrums

import com.kinect.jni.PipelineType
import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.isolated
import org.openrndr.kinect2.Kinect2
import org.openrndr.kinect2.Kinect2Manager
import org.openrndr.math.Matrix44
import org.openrndr.math.Vector3
import org.openrndr.math.transforms.lookAt
import org.openrndr.math.transforms.scale
import org.slf4j.LoggerFactory
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Air Drums - Interactive drum kit using Kinect V2 + MediaPipe hand tracking.
 *
 * Renders the Kinect camera feed (depth or color) as a mirrored background,
 * with 3D drum zones and hand markers overlaid at their correct spatial
 * positions. The view is mirrored so it feels like looking in a mirror:
 * moving your right hand to the right moves the marker right on screen.
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
    configure {
        width = 1280
        height = 720
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

        // ── Kinect ──
        val kinect = extend(Kinect2()) {
            enableDepth = true
            enableColor = true
            enableInfrared = false
            pipelineType = PipelineType.CPU
        }

        // ── Air Drums components ──
        val drumKit = DrumKit()
        drumKit.loadPreset(DrumKitPreset.STANDARD)

        val coordinateMapper = CoordinateMapper()

        val depthHandTracker = HandTracker()
        val pythonHandTracker = PythonHandTracker(coordinateMapper)

        var useMediaPipe = false
        val hitDetector = HitDetector(drumKit, depthHandTracker)

        val midiController = MidiController()
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

        // ── Visual state ──
        var showDepthView = true
        var showDebugInfo = true
        val recentHits = mutableListOf<Pair<DrumHit, Double>>()
        val hitFlashDuration = 0.5
        var lastHands = emptyList<HandPosition>()

        // 3D rendering params
        val circleSegments = 48
        val crossSize = 0.04  // hand crosshair arm length in meters
        val depthW = 512
        val depthH = 424

        // ── Shutdown handling ──
        // Shared cleanup that only runs once
        var cleanedUp = false
        fun cleanup() {
            if (cleanedUp) return
            cleanedUp = true
            logger.info("Air Drums shutting down...")
            pythonHandTracker.stop()
            midiController.close()
            logger.info("Air Drums shutdown complete")
        }

        // Ctrl+C or kill from terminal: clean up and force-exit the JVM.
        // (mvn exec:exec spawns a child JVM that outlives Maven otherwise)
        Runtime.getRuntime().addShutdownHook(Thread {
            cleanup()
        })

        // Window close button
        window.closed.listen {
            cleanup()
            application.exit()
        }

        // ── Keyboard controls ──
        keyboard.keyDown.listen { event ->
            when (event.name) {
                "escape" -> {
                    cleanup()
                    application.exit()
                }
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
                "arrow-left" -> coordinateMapper.adjustOffset(-2.0, 0.0)
                "arrow-right" -> coordinateMapper.adjustOffset(2.0, 0.0)
                "arrow-up" -> coordinateMapper.adjustOffset(0.0, -2.0)
                "arrow-down" -> coordinateMapper.adjustOffset(0.0, 2.0)
                "0" -> coordinateMapper.resetCalibration()
            }
        }

        // ── Draw loop ──
        extend {
            // === 1. Hand tracking + hit detection (logic, no drawing) ===
            val depthBuffer = kinect.depthCamera.getDepthMillimeters()

            if (useMediaPipe && pythonHandTracker.isRunning) {
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
                if (depthBuffer != null) {
                    lastHands = depthHandTracker.detectHands(depthBuffer, depthW, depthH)
                }
            }

            val hits = hitDetector.update(lastHands)
            hits.forEach { hit ->
                midiController.playHit(hit)
                recentHits.add(Pair(hit, seconds))
                logger.info("HIT ${hit.zone.name}  vel=${hit.velocity}  speed=${"%.2f".format(hit.hitVelocity)} m/s")
            }
            recentHits.removeIf { (_, ts) -> seconds - ts > hitFlashDuration }

            // === 2. Background: Kinect camera feed (2D, default ortho) ===
            // Kinect front-facing camera is naturally mirrored (like a webcam),
            // so no additional flip needed - it already looks like a mirror.
            drawer.clear(ColorRGBa.fromHex(0x1a1a2e))

            // Letterbox the Kinect feed to preserve its native aspect ratio
            val srcAspect = depthW.toDouble() / depthH  // 512/424 ≈ 1.208
            val dstAspect = width.toDouble() / height    // 1280/720 ≈ 1.778
            val drawW: Double
            val drawH: Double
            if (srcAspect < dstAspect) {
                // Kinect is taller proportionally -> fit to height, pillarbox sides
                drawH = height.toDouble()
                drawW = drawH * srcAspect
            } else {
                // Kinect is wider proportionally -> fit to width, letterbox top/bottom
                drawW = width.toDouble()
                drawH = drawW / srcAspect
            }
            val drawX = (width - drawW) / 2.0
            val drawY = (height - drawH) / 2.0

            if (showDepthView) {
                drawer.image(kinect.depthCamera.currentFrame, drawX, drawY, drawW, drawH)
            } else if (kinect.enableColor) {
                drawer.image(kinect.colorCamera.currentFrame, drawX, drawY, drawW, drawH)
            }

            // === 3. 3D overlay: drum zones + hands ===
            // Perspective projection matching the Kinect depth camera FOV.
            // Kinect 3D coordinates are already in the same mirrored space as
            // Kinect 3D coordinates have X positive = camera's right = your left.
            // The background image is already mirrored by the camera, but the
            // 3D coordinates are not - flip X to match.
            drawer.pushProjection()
            drawer.pushView()

            drawer.perspective(70.0, width.toDouble() / height, 0.01, 10.0)

            // lookAt along +Z, then mirror X to match the mirrored camera image
            val viewLookAt = lookAt(
                eye = Vector3.ZERO,
                target = Vector3(0.0, 0.0, 1.0),
                up = Vector3(0.0, 1.0, 0.0)
            )
            val mirrorX = Matrix44.scale(Vector3(-1.0, 1.0, 1.0))
            drawer.view = mirrorX * viewLookAt

            // Reference grid on XZ plane at Y=0
            drawer.isolated {
                drawer.stroke = ColorRGBa.GRAY.opacify(0.12)
                drawer.strokeWeight = 1.0
                val gridExtent = 1.0
                val gridStep = 0.2
                var g = -gridExtent
                while (g <= gridExtent) {
                    drawer.lineSegment(
                        Vector3(g, 0.0, 0.0),
                        Vector3(g, 0.0, gridExtent * 2)
                    )
                    drawer.lineSegment(
                        Vector3(-gridExtent, 0.0, g + gridExtent),
                        Vector3(gridExtent, 0.0, g + gridExtent)
                    )
                    g += gridStep
                }
            }

            // Draw drum zones as 3D rings
            for (zone in drumKit.zones) {
                val hitEntry = recentHits.firstOrNull { it.first.zone.id == zone.id }
                val flashProgress = if (hitEntry != null) {
                    (1.0 - (seconds - hitEntry.second) / hitFlashDuration).coerceIn(0.0, 1.0)
                } else 0.0

                val strokeColor = zone.color.mix(ColorRGBa.WHITE, flashProgress)
                val fillAlpha = 0.08 + flashProgress * 0.35
                val pos = zone.position
                val r = zone.radius

                // Radial fill lines (translucent disc)
                drawer.isolated {
                    drawer.fill = null
                    drawer.stroke = zone.color.opacify(fillAlpha)
                    drawer.strokeWeight = 1.0
                    for (i in 0 until 16) {
                        val angle = 2.0 * PI * i / 16
                        drawer.lineSegment(
                            Vector3(pos.x, pos.y, pos.z),
                            Vector3(pos.x + cos(angle) * r, pos.y, pos.z + sin(angle) * r)
                        )
                    }
                }

                // Circle outline (main ring)
                drawer.isolated {
                    drawer.fill = null
                    drawer.stroke = strokeColor
                    drawer.strokeWeight = if (flashProgress > 0) 3.0 else 1.5
                    for (i in 0 until circleSegments) {
                        val a0 = 2.0 * PI * i / circleSegments
                        val a1 = 2.0 * PI * (i + 1) / circleSegments
                        drawer.lineSegment(
                            Vector3(pos.x + cos(a0) * r, pos.y, pos.z + sin(a0) * r),
                            Vector3(pos.x + cos(a1) * r, pos.y, pos.z + sin(a1) * r)
                        )
                    }
                }

                // Elevated rim ring + vertical struts
                drawer.isolated {
                    drawer.fill = null
                    drawer.stroke = strokeColor.opacify(0.3)
                    drawer.strokeWeight = 1.0
                    val rimH = 0.02
                    for (i in 0 until circleSegments) {
                        val a0 = 2.0 * PI * i / circleSegments
                        val a1 = 2.0 * PI * (i + 1) / circleSegments
                        drawer.lineSegment(
                            Vector3(pos.x + cos(a0) * r, pos.y + rimH, pos.z + sin(a0) * r),
                            Vector3(pos.x + cos(a1) * r, pos.y + rimH, pos.z + sin(a1) * r)
                        )
                    }
                    for (i in 0 until 4) {
                        val a = 2.0 * PI * i / 4
                        val cx = pos.x + cos(a) * r
                        val cz = pos.z + sin(a) * r
                        drawer.lineSegment(
                            Vector3(cx, pos.y, cz),
                            Vector3(cx, pos.y + rimH, cz)
                        )
                    }
                }
            }

            // Draw hand markers as 3D crosshairs
            for (hand in lastHands) {
                val p = hand.position
                val color = when (hand.handedness) {
                    Handedness.LEFT -> ColorRGBa.CYAN
                    Handedness.RIGHT -> ColorRGBa.MAGENTA
                    Handedness.UNKNOWN -> ColorRGBa.WHITE
                }

                // 3-axis crosshair
                drawer.isolated {
                    drawer.fill = null
                    drawer.stroke = color
                    drawer.strokeWeight = 2.5
                    drawer.lineSegment(
                        Vector3(p.x - crossSize, p.y, p.z),
                        Vector3(p.x + crossSize, p.y, p.z)
                    )
                    drawer.lineSegment(
                        Vector3(p.x, p.y - crossSize, p.z),
                        Vector3(p.x, p.y + crossSize, p.z)
                    )
                    drawer.lineSegment(
                        Vector3(p.x, p.y, p.z - crossSize),
                        Vector3(p.x, p.y, p.z + crossSize)
                    )
                }

                // Glow ring around hand
                drawer.isolated {
                    drawer.fill = null
                    drawer.stroke = color.opacify(0.3)
                    drawer.strokeWeight = 1.5
                    val gr = crossSize * 1.5
                    for (i in 0 until 16) {
                        val a0 = 2.0 * PI * i / 16
                        val a1 = 2.0 * PI * (i + 1) / 16
                        drawer.lineSegment(
                            Vector3(p.x + cos(a0) * gr, p.y, p.z + sin(a0) * gr),
                            Vector3(p.x + cos(a1) * gr, p.y, p.z + sin(a1) * gr)
                        )
                    }
                }
            }

            // Save the 3D matrices before restoring, for projecting labels
            val viewMatrix3D = drawer.view
            val projMatrix3D = drawer.projection

            // Restore default ortho projection for 2D overlay
            drawer.popView()
            drawer.popProjection()

            // === 4. 2D overlay: labels + HUD ===

            // Zone labels (projected from 3D to screen)
            for (zone in drumKit.zones) {
                val hitEntry = recentHits.firstOrNull { it.first.zone.id == zone.id }
                val flashProgress = if (hitEntry != null) {
                    (1.0 - (seconds - hitEntry.second) / hitFlashDuration).coerceIn(0.0, 1.0)
                } else 0.0

                val projected = projectToScreen(zone.position, viewMatrix3D, projMatrix3D, width, height)
                if (projected != null) {
                    drawer.isolated {
                        drawer.fill = zone.color.mix(ColorRGBa.WHITE, flashProgress).opacify(0.9)
                        drawer.stroke = null
                        drawer.text(zone.name, projected.x - zone.name.length * 3.5, projected.y - 8.0)
                    }
                }
            }

            // Hand labels
            for (hand in lastHands) {
                val color = when (hand.handedness) {
                    Handedness.LEFT -> ColorRGBa.CYAN
                    Handedness.RIGHT -> ColorRGBa.MAGENTA
                    Handedness.UNKNOWN -> ColorRGBa.WHITE
                }
                val projected = projectToScreen(hand.position, viewMatrix3D, projMatrix3D, width, height)
                if (projected != null) {
                    drawer.isolated {
                        drawer.fill = color
                        drawer.stroke = null
                        val label = if (hand.handedness == Handedness.LEFT) "L" else "R"
                        drawer.text(label, projected.x + 14.0, projected.y + 4.0)
                    }
                }
            }

            // Debug HUD
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
    }
}

/**
 * Project a 3D world-space point to 2D screen coordinates using the given
 * view and projection matrices. Returns null if the point is behind the camera.
 */
private fun projectToScreen(
    worldPos: Vector3,
    view: Matrix44,
    projection: Matrix44,
    screenWidth: Int,
    screenHeight: Int
): org.openrndr.math.Vector2? {
    val viewPos = view * org.openrndr.math.Vector4(worldPos.x, worldPos.y, worldPos.z, 1.0)
    val clipPos = projection * viewPos

    if (clipPos.w <= 0.0) return null

    val ndcX = clipPos.x / clipPos.w
    val ndcY = clipPos.y / clipPos.w

    // NDC to screen pixels (Y flipped: NDC +1 = top, screen 0 = top)
    val screenX = (ndcX + 1.0) * 0.5 * screenWidth
    val screenY = (1.0 - ndcY) * 0.5 * screenHeight

    return org.openrndr.math.Vector2(screenX, screenY)
}
