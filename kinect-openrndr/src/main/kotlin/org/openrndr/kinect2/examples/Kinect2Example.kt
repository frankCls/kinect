package org.openrndr.kinect2.examples

import org.openrndr.application
import org.openrndr.color.ColorRGBa
import org.openrndr.kinect2.Kinect2
import org.openrndr.kinect2.Kinect2Manager
import com.kinect.jni.PipelineType
import org.slf4j.LoggerFactory

/**
 * Complete example demonstrating all Kinect V2 streams with OPENRNDR.
 *
 * This example displays depth, color, and infrared streams side-by-side from a Kinect V2 device.
 * The three streams are arranged horizontally: DEPTH | COLOR | INFRARED
 *
 * ## What You Should See:
 *
 * ### LEFT: Depth Camera (512x424)
 * - **Grayscale depth map** where brightness = distance from camera
 * - Black: Very close (< 0.5m) or no data
 * - Dark gray: Close range (0.5-1.5m)
 * - Medium gray: Mid-range (1.5-3m)
 * - Light gray/White: Far range (3-4.5m)
 *
 * ### CENTER: Color Camera (1920x1080, scaled to fit)
 * - **Full RGB color video** from the Kinect's 1080p camera
 * - Natural color reproduction
 * - Should show your environment in full color
 * - Wider field of view than depth camera
 *
 * ### RIGHT: Infrared Camera (512x424)
 * - **Grayscale IR intensity** from the active IR illuminator
 * - Shows the raw infrared pattern projected by Kinect
 * - Brighter areas = stronger IR reflection
 * - Works in complete darkness
 * - Shows texture and patterns invisible to RGB camera
 *
 * ### Bottom Statistics:
 * - Frame counts for each stream (should all increase steadily)
 * - All three streams run at ~30 FPS independently
 *
 * ### Expected Behavior:
 * - All three streams update smoothly at 30 FPS
 * - Depth and IR are synchronized (same camera, different data)
 * - Color stream has slight offset (different physical camera)
 * - No frame drops or stuttering under normal conditions
 *
 * ### Troubleshooting:
 * - **Black windows**: Check Kinect connection and USB 3.0
 * - **Missing color**: Ensure good lighting for RGB camera
 * - **Low FPS**: USB 3.0 bandwidth issue - close other USB devices
 * - **Misaligned streams**: Normal - depth and color cameras are physically offset
 *
 * Requirements:
 * - Kinect V2 hardware connected via USB 3.0
 * - libfreenect2 installed at ~/freenect2
 * - Native library path configured: -Djava.library.path=kinect-jni/target:$HOME/freenect2/lib
 *
 * Run with:
 * ```
 * ./run-example.sh all
 * ```
 * or
 * ```
 * mvn exec:java -Dexec.mainClass="org.openrndr.kinect2.examples.Kinect2ExampleKt" \
 *     -Dexec.classpathScope=compile \
 *     -Djava.library.path=kinect-jni/target:$HOME/freenect2/lib
 * ```
 */
fun main() {
    val logger = LoggerFactory.getLogger("Kinect2Example")

    application {
    configure {
        width = 1920
        height = 424
        title = "Kinect V2 - Depth, Color, and IR Streams"
    }

    program {
        logger.info("=== Kinect V2 Device Information ===")
        logger.info("Library version: ${Kinect2Manager.getLibraryVersion()}")
        logger.info("Devices found: ${Kinect2Manager.getDeviceCount()}")

        val devices = Kinect2Manager.getKinectsV2()
        devices.forEachIndexed { index, device ->
            logger.info("  [$index] Serial: ${device.serial}")
        }

        if (!Kinect2Manager.hasDevices()) {
            logger.error("No Kinect V2 devices found!")
            logger.error("Please check:")
            logger.error("  - Kinect is connected to USB 3.0 port")
            logger.error("  - Kinect power supply is connected")
            logger.error("  - libfreenect2 is installed correctly")
            application.exit()
            return@program
        }

        // Create and configure Kinect2 extension
        val kinect = extend(Kinect2()) {
            deviceIndex = 0                     // Use first device
            enableDepth = true                  // Enable depth stream
            enableColor = true                  // Enable color stream
            enableInfrared = true               // Enable infrared stream
            pipelineType = PipelineType.CPU     // Use CPU pipeline (safe for OPENRNDR)
        }

        logger.info("=== Starting Kinect2 Extension ===")
        logger.info("Pipeline: ${kinect.pipelineType}")
        logger.info("Streams enabled: Depth, Color, IR")

        extend {
            // Clear background
            drawer.clear(ColorRGBa.BLACK)

            // Draw depth camera (left third)
            drawer.image(kinect.depthCamera.currentFrame, 0.0, 0.0, 512.0, 424.0)

            // Draw color camera (center - scaled down to fit)
            val colorScale = 424.0 / 1080.0  // Scale to fit height
            val colorWidth = 1920.0 * colorScale
            drawer.image(kinect.colorCamera.currentFrame, 512.0, 0.0, colorWidth, 424.0)

            // Draw IR camera (right third)
            drawer.image(kinect.irCamera.currentFrame, 512.0 + colorWidth, 0.0, 512.0, 424.0)

            // Draw labels
            drawer.fill = ColorRGBa.WHITE
            drawer.text("DEPTH", 10.0, 20.0)
            drawer.text("COLOR", 522.0, 20.0)
            drawer.text("INFRARED", 522.0 + colorWidth, 20.0)

            // Draw statistics
            drawer.text("Depth frames: ${kinect.depthCamera.framesReceived}", 10.0, 410.0)
            drawer.text("Color frames: ${kinect.colorCamera.framesReceived}", 522.0, 410.0)
            drawer.text("IR frames: ${kinect.irCamera.framesReceived}", 522.0 + colorWidth, 410.0)
        }
    }
    }
}
