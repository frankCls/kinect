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
 * Displays a grayscale depth image where closer objects are brighter.
 *
 * Requirements:
 * - Kinect V2 hardware connected via USB 3.0
 * - libfreenect2 installed at ~/freenect2
 * - Native library path configured
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

        extend {
            drawer.clear(ColorRGBa.BLACK)

            // Draw depth image
            drawer.image(kinect.depthCamera.currentFrame)

            // Show frame count
            drawer.fill = ColorRGBa.WHITE
            drawer.text("Frames: ${kinect.depthCamera.framesReceived}", 10.0, 20.0)
            drawer.text("FPS: ${"%.1f".format(frameCount / seconds)}", 10.0, 40.0)
        }
    }
}
