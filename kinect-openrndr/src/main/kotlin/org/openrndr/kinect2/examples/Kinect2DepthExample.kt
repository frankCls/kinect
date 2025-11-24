package org.openrndr.kinect2.examples

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.kinect2.Kinect2
import org.openrndr.kinect2.Kinect2Manager
import com.kinect.jni.PipelineType

/**
 * Simple example showing only the depth camera from Kinect V2.
 *
 * This is the minimal example to get started with Kinect V2 in OPENRNDR.
 * Displays a 512x424 grayscale depth image in real-time.
 *
 * ## What You Should See:
 *
 * A **grayscale depth map** where pixel brightness represents distance from the camera:
 *
 * - **Black pixels**: Objects very close to camera (< 0.5m) or invalid/no depth data
 * - **Dark gray**: Objects close to camera (0.5m - 1.5m range)
 * - **Medium gray**: Objects at mid-range (1.5m - 3m)
 * - **Light gray to white**: Objects far from camera (3m - 4.5m)
 * - **Black background**: Areas beyond 4.5m or infrared-absorbing surfaces
 *
 * ### Example Visualization:
 * - Hold your hand 0.5m from Kinect → appears **very dark gray**
 * - Stand 2m from Kinect → torso appears **medium gray**
 * - Wall 4m away → appears **light gray/white**
 *
 * ### Frame Rate Information:
 * - Top-left shows frame count and FPS (should be ~30 FPS)
 * - Expect smooth motion with no lag at 30 FPS
 *
 * ### Troubleshooting:
 * - **Red dots instead of grayscale**: Color format issue (fixed in latest version)
 * - **All black**: Kinect not connected or USB 3.0 issue
 * - **Very dark image**: All objects in view are close (< 1m) - step back
 * - **Choppy/low FPS**: USB 3.0 bandwidth issue - disconnect other devices
 *
 * Requirements:
 * - Kinect V2 hardware connected via USB 3.0
 * - libfreenect2 installed at ~/freenect2
 * - Native library path configured
 *
 * Run with:
 * ```
 * ./run-example.sh depth
 * ```
 */
fun main() = application {
    configure {
        width = 512
        height = 424
        title = "Kinect V2 - Depth Camera"
    }

    program {
        // Check for devices
        if (!Kinect2Manager.hasDevices()) {
            println("ERROR: No Kinect V2 devices found!")
            application.exit()
            return@program
        }

        println("Found ${Kinect2Manager.getDeviceCount()} Kinect V2 device(s)")
        println("Using CPU pipeline (safe for OPENRNDR)")

        // Create Kinect2 extension with minimal configuration
        val kinect = extend(Kinect2()) {
            enableDepth = true
            enableColor = false
            enableInfrared = false
            pipelineType = PipelineType.CPU
        }

        // Track time for console output
        var lastPrintTime = 0.0

        extend {
            drawer.clear(ColorRGBa.BLACK)

            // Draw depth image
            drawer.image(kinect.depthCamera.currentFrame)

            // Show frame count and FPS (requires default font, may not display if font missing)
            try {
                drawer.fill = ColorRGBa.WHITE
                drawer.text("Frames: ${kinect.depthCamera.framesReceived}", 10.0, 20.0)
                drawer.text("FPS: ${"%.1f".format(frameCount / seconds)}", 10.0, 40.0)
            } catch (@Suppress("SwallowedException") _: Exception) {
                // Font not available, skip text rendering silently
            }

            // Print stats to console every second
            if (seconds - lastPrintTime >= 1.0) {
                lastPrintTime = seconds
                val fps = if (seconds > 0) frameCount / seconds else 0.0
                println("[${seconds.toInt()}s] Frames: ${kinect.depthCamera.framesReceived}, FPS: ${"%.1f".format(fps)}")
            }
        }
    }
}
