package org.openrndr.kinect2

import com.kinect.jni.Freenect
import com.kinect.jni.FreenectContext
import com.kinect.jni.KinectDevice
import com.kinect.jni.Frame
import com.kinect.jni.FrameType
import com.kinect.jni.PipelineType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openrndr.Extension
import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.ColorFormat
import org.openrndr.draw.ColorType
import org.openrndr.draw.colorBuffer
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * OPENRNDR Extension for Kinect V2 integration.
 *
 * Provides reactive frame streams and GPU-resident ColorBuffer integration.
 * Based on orx-kinect-v1 architecture adapted for Kinect V2 hardware.
 *
 * Usage:
 * ```kotlin
 * fun main() = application {
 *     program {
 *         val kinect = extend(Kinect2()) {
 *             deviceIndex = 0
 *             enableDepth = true
 *             enableColor = true
 *             enableInfrared = true
 *         }
 *
 *         extend {
 *             drawer.image(kinect.depthCamera.currentFrame)
 *         }
 *     }
 * }
 * ```
 */
class Kinect2 : Extension {
    override var enabled: Boolean = true

    private val logger = LoggerFactory.getLogger(Kinect2::class.java)

    // Configuration properties (set before setup)
    var deviceIndex: Int = 0
    var deviceSerial: String? = null
    var enableDepth: Boolean = true
    var enableColor: Boolean = true
    var enableInfrared: Boolean = true
    var pipelineType: PipelineType = PipelineType.CPU  // CPU pipeline for framework compatibility

    // Camera interfaces (available after setup)
    lateinit var depthCamera: Kinect2DepthCamera
        private set
    lateinit var colorCamera: Kinect2ColorCamera
        private set
    lateinit var irCamera: Kinect2IRCamera
        private set

    // Internal state
    private var context: FreenectContext? = null
    private var device: KinectDevice? = null
    private var program: Program? = null
    private var acquisitionThread: Thread? = null
    private var running = false

    // Coroutine scope for frame processing
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun setup(program: Program) {
        this.program = program
        logger.info("Kinect2 extension setup starting...")
        logger.info("Pipeline type: $pipelineType (CPU recommended for OPENRNDR)")

        try {
            // Check native library
            if (!Freenect.isLibraryLoaded()) {
                throw RuntimeException("Kinect native library not loaded")
            }
            logger.info("libfreenect2 version: ${Freenect.getVersion()}")

            // Create context and open device
            context = Freenect.createContext()
            val ctx = context ?: throw RuntimeException("Failed to create Freenect context")

            val deviceCount = ctx.getDeviceCount()
            logger.info("Found $deviceCount Kinect V2 device(s)")

            if (deviceCount == 0) {
                throw RuntimeException("No Kinect V2 devices found")
            }

            // Open device
            device = if (deviceSerial != null) {
                logger.info("Opening device with serial: $deviceSerial")
                ctx.openDevice(deviceSerial, pipelineType)
            } else {
                logger.info("Opening device at index: $deviceIndex")
                val serial = ctx.getDeviceSerial(deviceIndex)
                logger.info("Device serial: $serial")
                ctx.openDevice(serial, pipelineType)
            }

            val dev = device ?: throw RuntimeException("Failed to open Kinect device")
            logger.info("Device opened successfully")
            logger.info("Firmware version: ${dev.getFirmwareVersion()}")

            // Initialize camera interfaces
            depthCamera = Kinect2DepthCamera()
            colorCamera = Kinect2ColorCamera()
            irCamera = Kinect2IRCamera()

            // Initialize ColorBuffers on main thread (OpenGL requirement)
            if (enableDepth) depthCamera.initialize()
            if (enableColor) colorCamera.initialize()
            if (enableInfrared) irCamera.initialize()

            // Start streaming
            val frameTypes = mutableListOf<FrameType>()
            if (enableDepth) frameTypes.add(FrameType.DEPTH)
            if (enableColor) frameTypes.add(FrameType.COLOR)
            if (enableInfrared) frameTypes.add(FrameType.IR)

            dev.start(*frameTypes.toTypedArray())
            logger.info("Streaming started for: ${frameTypes.joinToString()}")

            // Start acquisition thread
            running = true
            acquisitionThread = thread(name = "Kinect2-Acquisition") {
                runAcquisitionLoop()
            }

            logger.info("Kinect2 extension setup complete")

        } catch (e: Exception) {
            logger.error("Failed to setup Kinect2 extension", e)
            cleanup()
            throw e
        }
    }

    /**
     * Frame acquisition loop running on dedicated thread.
     * Fetches frames from libfreenect2 and publishes to StateFlow.
     */
    private fun runAcquisitionLoop() {
        logger.info("Acquisition thread started")
        val dev = device ?: return

        try {
            while (running) {
                try {
                    // Fetch depth frame
                    if (enableDepth) {
                        val depthFrame = dev.getNextFrame(FrameType.DEPTH, 100)
                        if (depthFrame != null) {
                            depthCamera.publishFrame(depthFrame)
                            depthFrame.close()
                        }
                    }

                    // Fetch color frame
                    if (enableColor) {
                        val colorFrame = dev.getNextFrame(FrameType.COLOR, 100)
                        if (colorFrame != null) {
                            colorCamera.publishFrame(colorFrame)
                            colorFrame.close()
                        }
                    }

                    // Fetch IR frame
                    if (enableInfrared) {
                        val irFrame = dev.getNextFrame(FrameType.IR, 100)
                        if (irFrame != null) {
                            irCamera.publishFrame(irFrame)
                            irFrame.close()
                        }
                    }

                } catch (e: Exception) {
                    if (running) {
                        logger.error("Error in acquisition loop", e)
                    }
                }
            }
        } finally {
            logger.info("Acquisition thread stopped")
        }
    }

    override fun beforeDraw(drawer: org.openrndr.draw.Drawer, program: Program) {
        // Update ColorBuffers from CPU data (must be called on render thread)
        if (::depthCamera.isInitialized && depthCamera.initialized) {
            depthCamera.update()
        }
        if (::colorCamera.isInitialized && colorCamera.initialized) {
            colorCamera.update()
        }
        if (::irCamera.isInitialized && irCamera.initialized) {
            irCamera.update()
        }
    }

    override fun shutdown(program: Program) {
        logger.info("Kinect2 extension shutting down...")
        cleanup()
    }

    private fun cleanup() {
        running = false

        // Stop acquisition thread
        acquisitionThread?.let {
            it.interrupt()
            it.join(1000)
        }

        // Cancel coroutine scope
        scope.cancel()

        // Close device
        device?.let {
            try {
                it.close()
                logger.info("Device closed")
            } catch (e: Exception) {
                logger.error("Error closing device", e)
            }
        }

        // Close context
        context?.let {
            try {
                it.close()
                logger.info("Context closed")
            } catch (e: Exception) {
                logger.error("Error closing context", e)
            }
        }

        // Clean up camera resources
        if (::depthCamera.isInitialized) depthCamera.dispose()
        if (::colorCamera.isInitialized) colorCamera.dispose()
        if (::irCamera.isInitialized) irCamera.dispose()

        device = null
        context = null
        acquisitionThread = null
    }
}

/**
 * Base class for Kinect2 camera interfaces.
 * Manages frame streaming and ColorBuffer updates.
 */
abstract class Kinect2Camera(
    val width: Int,
    val height: Int,
    val colorFormat: ColorFormat,
    val colorType: ColorType,
    private val bytesPerPixel: Int
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Front buffer (GPU-resident, accessed by render thread)
    lateinit var currentFrame: ColorBuffer
        private set

    // CPU-side data buffers for thread-safe frame delivery
    private var frontDataBuffer: ByteBuffer? = null
    private var backDataBuffer: ByteBuffer? = null
    private val bufferLock = Any()
    private var pendingUpdate = false

    // State flow for reactive frame access
    private val _frameFlow = MutableStateFlow<Frame?>(null)
    val frameFlow: StateFlow<Frame?> = _frameFlow

    // Frame statistics
    var framesReceived: Long = 0
        private set
    var lastTimestamp: Long = 0
        private set

    var initialized = false
        private set

    /**
     * Initialize ColorBuffers and CPU buffers.
     * Must be called from render thread (OpenGL context).
     */
    fun initialize() {
        if (!initialized) {
            currentFrame = colorBuffer(width, height, format = colorFormat, type = colorType)
            currentFrame.fill(ColorRGBa.BLACK)

            // Allocate CPU-side buffers for thread-safe data transfer
            val bufferSize = width * height * bytesPerPixel
            frontDataBuffer = ByteBuffer.allocateDirect(bufferSize)
            backDataBuffer = ByteBuffer.allocateDirect(bufferSize)

            initialized = true
        }
    }

    /**
     * Publish new frame from acquisition thread.
     * Processes frame data into CPU buffer (thread-safe).
     */
    fun publishFrame(frame: Frame) {
        // Skip if not initialized
        if (!initialized) {
            logger.warn("publishFrame called before initialization, skipping")
            return
        }

        framesReceived++
        lastTimestamp = frame.timestamp

        // Process frame data into back buffer (CPU-side, thread-safe)
        val buffer = backDataBuffer ?: return
        buffer.rewind()
        processFrameData(frame, buffer)

        // Swap buffers atomically
        synchronized(bufferLock) {
            val temp = frontDataBuffer
            frontDataBuffer = backDataBuffer
            backDataBuffer = temp
            pendingUpdate = true
        }

        // Publish to flow
        _frameFlow.value = frame
    }

    /**
     * Update GPU ColorBuffer from CPU data.
     * Must be called from render thread (OpenGL context).
     */
    fun update() {
        if (!initialized) return

        // Check if there's a pending update
        val shouldUpdate = synchronized(bufferLock) {
            if (pendingUpdate) {
                pendingUpdate = false
                true
            } else {
                false
            }
        }

        if (shouldUpdate) {
            val buffer = frontDataBuffer ?: return
            buffer.rewind()
            currentFrame.write(buffer)
        }
    }

    /**
     * Process frame data and write to CPU buffer.
     * Subclasses implement format-specific conversion.
     * Called from acquisition thread.
     */
    protected abstract fun processFrameData(frame: Frame, buffer: ByteBuffer)

    /**
     * Dispose ColorBuffer resources.
     */
    fun dispose() {
        if (initialized) {
            currentFrame.destroy()
            frontDataBuffer = null
            backDataBuffer = null
            initialized = false
        }
    }
}

/**
 * Depth camera (512x424, 16-bit depth values in millimeters).
 *
 * **What You Should See (Standard "X-Ray" Visualization):**
 * - **Grayscale depth map** where brightness is INVERTED: close=bright, far=dark
 * - **White/Bright pixels**: Objects very close to camera (< 500mm / 0.5m)
 * - **Light gray**: Objects close to camera (around 500-1500mm / 0.5-1.5m)
 * - **Medium gray**: Objects at mid-range (around 1500-3000mm / 1.5-3m)
 * - **Dark gray**: Objects far from camera (3000-4500mm / 3-4.5m)
 * - **Black**: Background/objects beyond 4500mm (4.5m) or areas with no depth data (invalid readings)
 *
 * **Kinect V2 Depth Specifications:**
 * - Resolution: 512x424 pixels
 * - Depth range: 500mm (0.5m) to 4500mm (4.5m)
 * - Accuracy: ±0.5% at 500mm, ±2% at 4500mm
 * - Field of view: 70.6° horizontal, 60° vertical
 * - Frame rate: 30 FPS
 *
 * **Common Visualization Issues:**
 * - If you see **red dots** instead of grayscale: This may indicate the ColorBuffer format
 *   is being interpreted incorrectly. The depth data is single-channel (R) grayscale.
 * - If **everything appears very dark**: Most objects in view are close to the camera (< 1m).
 *   Try moving objects or the camera to see the full depth range.
 * - If you see **black regions**: These are areas with no valid depth reading (too close,
 *   too far, or infrared-absorbing surfaces like black velvet).
 *
 * **Technical Details:**
 * - Converts 16-bit depth values (mm) to normalized 0-1 range for visualization
 * - Applies vertical flip to correct libfreenect2's upside-down image orientation
 * - Uses FLOAT16 ColorBuffer format for efficient GPU storage
 */
class Kinect2DepthCamera : Kinect2Camera(
    width = 512,
    height = 424,
    colorFormat = ColorFormat.RGBa,  // Using RGBa for proper grayscale display
    colorType = ColorType.UINT8,
    bytesPerPixel = 4  // RGBa = 4 bytes (R, G, B, A)
) {
    companion object {
        // Gamma correction lookup table for depth visualization
        // Pre-calculated for all possible 16-bit depth values (0-65535mm)
        // Formula: Output = 255 * ((depthMm - MIN_DEPTH) / (MAX_DEPTH - MIN_DEPTH))^(1/GAMMA)
        // Then inverted so close objects are BRIGHT and far objects are DARK
        private val depthGammaTable = IntArray(65536).apply {
            val GAMMA = 2.2
            val MAX_DEPTH = 4000.0  // Reduced from 8000 for better indoor contrast
            val MIN_DEPTH = 500.0

            for (i in 0 until 65536) {
                when {
                    i == 0 -> this[i] = 0  // Invalid depth (0mm) = black
                    i < MIN_DEPTH.toInt() -> this[i] = 255  // Very close (<500mm) = white
                    i > MAX_DEPTH.toInt() -> this[i] = 0   // Very far (>4000mm) = black
                    else -> {
                        // Normalize to 0.0-1.0 range
                        val normalized = (i - MIN_DEPTH) / (MAX_DEPTH - MIN_DEPTH)
                        // Apply gamma correction
                        val gammaCorrected = Math.pow(normalized, 1.0 / GAMMA)
                        // INVERT: close (normalized=0) should be bright (255), far (normalized=1) should be dark (0)
                        this[i] = ((1.0 - gammaCorrected) * 255).toInt().coerceIn(0, 255)
                    }
                }
            }
        }
    }

    private val logger = LoggerFactory.getLogger(Kinect2DepthCamera::class.java)

    override fun processFrameData(frame: Frame, buffer: ByteBuffer) {
        val data = frame.data
        data.position(0)

        // Debug: sample center pixel and find closest pixel every 30 frames
        val shouldLog = (frame.sequence % 30L == 0L)
        var minDepth = Int.MAX_VALUE
        var minX = -1
        var minY = -1
        var validPixels = 0

        // Process depth frame: convert to grayscale with INVERTED mapping
        // Standard "X-Ray" visualization: close=bright (255), far=dark (0)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val srcIdx = y * width + x
                data.position(srcIdx * 4)

                // Read as 32-bit little-endian float (millimeters)
                // libfreenect2 provides depth data as 32-bit IEEE 754 floats, not 16-bit integers
                val byte0 = data.get().toInt() and 0xFF
                val byte1 = data.get().toInt() and 0xFF
                val byte2 = data.get().toInt() and 0xFF
                val byte3 = data.get().toInt() and 0xFF
                val bits = (byte3 shl 24) or (byte2 shl 16) or (byte1 shl 8) or byte0
                val depthFloat = Float.fromBits(bits)

                // Convert to int for gamma table lookup, handle invalid values
                val depthMm = when {
                    depthFloat <= 0f || depthFloat.isNaN() || depthFloat.isInfinite() -> 0
                    else -> depthFloat.toInt()
                }

                val dstIdx = (height - 1 - y) * width + x

//                // Track closest pixel for diagnostics (ignore 1-2mm as sensor noise/damage)
//                if (shouldLog && depthMm > 0 && depthMm < 65535) {
//                    validPixels++
//                    if (depthMm > 100 && depthMm < minDepth) {  // Ignore 1-2mm readings
//                        minDepth = depthMm
//                        minX = x
//                        minY = y
//                    }
//                }

                // Depth visualization with gamma correction:
                // - Close objects (500-1500mm): BRIGHT (200-255 gray) - user should appear white/bright
                // - Mid-range objects (1500-4000mm): GREY (100-200 gray)
                // - Far objects (4000-8000mm): DARK (0-100 gray) - background should be dark grey
                // - Invalid/no data (0mm): BLACK (0)
                // - Very far (>8000mm): BLACK (0)
                //
                // Uses pre-calculated gamma LUT for performance
                val gray = depthGammaTable[depthMm.coerceIn(0, 65535)]
                val grayValue = gray.toByte()

                // Debug center pixel
                if (shouldLog && x == width/2 && y == height/2) {
                    logger.info("DEPTH Center pixel: depthMm=$depthMm, gray=$gray (${gray*100/255}%)")
                }

                buffer.position(dstIdx * 4)
                buffer.put(grayValue)  // R
                buffer.put(grayValue)  // G
                buffer.put(grayValue)  // B
                buffer.put(255.toByte())  // A
            }
        }

        // Log closest pixel found
        if (shouldLog && minX >= 0) {
            val closestGray = depthGammaTable[minDepth.coerceIn(0, 65535)]
            val totalPixels = width * height
            val validPercent = (validPixels * 100.0) / totalPixels
            logger.info("DEPTH Closest pixel at ($minX, $minY): depthMm=${minDepth}mm, gray=$closestGray (${closestGray*100/255}%) | Valid pixels: $validPixels/$totalPixels (${String.format("%.1f", validPercent)}%)")
        }
    }
}

/**
 * Color camera (1920x1080, BGRX format).
 * Converts to RGB ColorBuffer.
 */
class Kinect2ColorCamera : Kinect2Camera(
    width = 1920,
    height = 1080,
    colorFormat = ColorFormat.RGB,
    colorType = ColorType.UINT8,
    bytesPerPixel = 3  // RGB = 3 bytes
) {
    private val logger = LoggerFactory.getLogger(Kinect2ColorCamera::class.java)

    override fun processFrameData(frame: Frame, buffer: ByteBuffer) {
        val data = frame.data

        // Convert BGRX to RGB
        data.position(0)
        for (i in 0 until width * height) {
            val b = data.get()
            val g = data.get()
            val r = data.get()
            data.get() // Skip X byte

            buffer.put(r)
            buffer.put(g)
            buffer.put(b)
        }
    }
}

/**
 * Infrared camera (512x424, 16-bit IR intensity).
 * Converts to grayscale ColorBuffer.
 */
class Kinect2IRCamera : Kinect2Camera(
    width = 512,
    height = 424,
    colorFormat = ColorFormat.RGBa,  // Using RGBa for proper grayscale display
    colorType = ColorType.UINT8,
    bytesPerPixel = 4  // RGBa = 4 bytes
) {
    private val logger = LoggerFactory.getLogger(Kinect2IRCamera::class.java)

    override fun processFrameData(frame: Frame, buffer: ByteBuffer) {
        val data = frame.data

        // Convert 16-bit IR to grayscale
        // Use realistic max of 20000 instead of 65535 for better visibility
        // Most indoor IR values are 0-10000, so 20000 gives good contrast
        data.position(0)
        for (i in 0 until width * height) {
            // Read as 32-bit little-endian float
            // libfreenect2 provides IR data as 32-bit IEEE 754 floats, not 16-bit integers
            val byte0 = data.get().toInt() and 0xFF
            val byte1 = data.get().toInt() and 0xFF
            val byte2 = data.get().toInt() and 0xFF
            val byte3 = data.get().toInt() and 0xFF
            val bits = (byte3 shl 24) or (byte2 shl 16) or (byte1 shl 8) or byte0
            val irFloat = Float.fromBits(bits)

            // Convert to int for normalization, handle invalid values
            val ir = when {
                irFloat <= 0f || irFloat.isNaN() || irFloat.isInfinite() -> 0
                else -> irFloat.toInt()
            }

            // Normalize with realistic max for better visibility
            val gray = minOf(255, (ir * 255) / 20000)
            val grayValue = gray.toByte()

            // Write as RGBa (replicate gray value to R, G, B channels)
            buffer.put(grayValue)  // R
            buffer.put(grayValue)  // G
            buffer.put(grayValue)  // B
            buffer.put(255.toByte())  // A (fully opaque)
        }
    }
}
