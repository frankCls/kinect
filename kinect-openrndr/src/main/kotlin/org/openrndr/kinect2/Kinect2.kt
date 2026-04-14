package org.openrndr.kinect2

import com.kinect.jni.Freenect
import com.kinect.jni.FreenectContext
import com.kinect.jni.KinectDevice
import com.kinect.jni.Frame
import com.kinect.jni.FrameType
import com.kinect.jni.PipelineType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import org.openrndr.Extension
import org.openrndr.Program
import org.openrndr.color.ColorRGBa
import org.openrndr.draw.ColorBuffer
import org.openrndr.draw.ColorFormat
import org.openrndr.draw.ColorType
import org.openrndr.draw.colorBuffer
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

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

    private companion object { val logger = LoggerFactory.getLogger(Kinect2::class.java) }

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

    // Registered color buffer (512x424 BGRX pixels aligned to depth space)
    private val registeredColorBuffer = ByteBuffer.allocateDirect(512 * 424 * 4)

    /**
     * Get the registered color buffer (512x424 BGRX) aligned to depth coordinate space.
     * This buffer is automatically populated during frame acquisition via getSynchronizedFrames.
     * Returns a read-only view of the buffer for safe concurrent access.
     * @return ByteBuffer containing registered color data, or null if not yet available
     */
    fun getRegisteredColorBuffer(): ByteBuffer? {
        return registeredColorBuffer.asReadOnlyBuffer()
    }

    // Internal state
    // Note: FreenectContext is managed by FreenectContextManager singleton
    private var device: KinectDevice? = null
    private var program: Program? = null
    private var acquisitionScope: CoroutineScope? = null
    private var acquisitionJob: Job? = null

    override fun setup(program: Program) {
        this.program = program
        logger.info("Kinect2 extension setup starting...")
        logger.info("Pipeline type: $pipelineType (CPU recommended for OPENRNDR)")

        try {
            if (!Freenect.isLibraryLoaded()) {
                throw RuntimeException("Kinect native library not loaded")
            }
            logger.info("libfreenect2 version: ${Freenect.getVersion()}")

            val ctx = FreenectContextManager.getContext()

            val deviceCount = ctx.getDeviceCount()
            logger.info("Found $deviceCount Kinect V2 device(s)")

            if (deviceCount == 0) {
                throw RuntimeException("No Kinect V2 devices found")
            }

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

            depthCamera = Kinect2DepthCamera()
            colorCamera = Kinect2ColorCamera()
            irCamera = Kinect2IRCamera()

            // Initialize ColorBuffers on main thread (OpenGL requirement)
            if (enableDepth) depthCamera.initialize()
            if (enableColor) colorCamera.initialize()
            if (enableInfrared) irCamera.initialize()

            // Start streaming
            val frameTypes = buildList {
                if (enableDepth) add(FrameType.DEPTH)
                if (enableColor) add(FrameType.COLOR)
                if (enableInfrared) add(FrameType.IR)
            }

            dev.start(*frameTypes.toTypedArray())
            logger.info("Streaming started for: ${frameTypes.joinToString()}")

            // Start acquisition coroutine on IO dispatcher (blocking JNI calls)
            acquisitionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            acquisitionJob = acquisitionScope!!.launch {
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
    private suspend fun runAcquisitionLoop() {
        logger.info("Acquisition coroutine started")
        val dev = device ?: return

        try {
            while (coroutineContext.isActive) {
                try {
                    // Use synchronized frame acquisition when both depth and color are enabled
                    if (enableDepth && enableColor) {
                        // Get synchronized frames (depth + color) with automatic registration
                        val frames = dev.getSynchronizedFrames(100)

                        if (frames != null && frames.size >= 2) {
                            // frames[0] = depth, frames[1] = color
                            depthCamera.publishFrame(frames[0])
                            colorCamera.publishFrame(frames[1])

                            // Get registered color buffer aligned to depth space
                            registeredColorBuffer.rewind()
                            dev.getRegisteredBuffer(registeredColorBuffer)

                            // Close frames
                            frames[0].close()
                            frames[1].close()
                        }

                        // Handle IR separately if enabled
                        if (enableInfrared) {
                            val irFrame = dev.getNextFrame(FrameType.IR, 100)
                            if (irFrame != null) {
                                irCamera.publishFrame(irFrame)
                                irFrame.close()
                            }
                        }
                    } else {
                        // Fall back to individual frame fetching when not using both depth+color
                        if (enableDepth) {
                            val depthFrame = dev.getNextFrame(FrameType.DEPTH, 100)
                            if (depthFrame != null) {
                                depthCamera.publishFrame(depthFrame)
                                depthFrame.close()
                            }
                        }

                        if (enableColor) {
                            val colorFrame = dev.getNextFrame(FrameType.COLOR, 100)
                            if (colorFrame != null) {
                                colorCamera.publishFrame(colorFrame)
                                colorFrame.close()
                            }
                        }

                        if (enableInfrared) {
                            val irFrame = dev.getNextFrame(FrameType.IR, 100)
                            if (irFrame != null) {
                                irCamera.publishFrame(irFrame)
                                irFrame.close()
                            }
                        }
                    }

                } catch (e: CancellationException) {
                    throw e  // Re-throw to allow proper cancellation
                } catch (e: Exception) {
                    if (coroutineContext.isActive) {
                        logger.error("Error in acquisition loop", e)
                    }
                }
            }
        } finally {
            logger.info("Acquisition coroutine stopped")
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
        // Cancel acquisition coroutine
        acquisitionJob?.cancel()
        runCatching {
            runBlocking {
                withTimeoutOrNull(500) { acquisitionJob?.join() }
                    ?: logger.warn("Acquisition coroutine did not stop within 500ms, proceeding with cleanup")
            }
        }
        acquisitionScope?.cancel()

        // Close device
        device?.let {
            runCatching { it.close() }
                .onSuccess { logger.info("Device closed") }
                .onFailure { e -> logger.error("Error closing device", e) }
        }

        // Context is managed by FreenectContextManager singleton and will be automatically cleaned up on JVM shutdown

        // Clean up camera resources
        runCatching { if (::depthCamera.isInitialized) depthCamera.dispose() }
        runCatching { if (::colorCamera.isInitialized) colorCamera.dispose() }
        runCatching { if (::irCamera.isInitialized) irCamera.dispose() }

        device = null
        acquisitionJob = null
        acquisitionScope = null
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
    private companion object { val baseLogger = LoggerFactory.getLogger(Kinect2Camera::class.java) }
    private val logger = baseLogger

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
    @Volatile var framesReceived: Long = 0
        private set
    @Volatile var lastTimestamp: Long = 0
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
     * Get a snapshot of the current front buffer data.
     * Returns a read-only view of the buffer for safe access.
     * Caller must not modify the buffer contents.
     */
    fun getDataBuffer(): ByteBuffer? {
        return synchronized(bufferLock) {
            frontDataBuffer?.asReadOnlyBuffer()
        }
    }

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
                        val gammaCorrected = normalized.pow(1.0 / GAMMA)
                        // INVERT: close (normalized=0) should be bright (255), far (normalized=1) should be dark (0)
                        this[i] = ((1.0 - gammaCorrected) * 255).toInt().coerceIn(0, 255)
                    }
                }
            }
        }
    }

    private val logger = LoggerFactory.getLogger(Kinect2DepthCamera::class.java)

    // Raw depth (millimeters) double-buffered storage for point cloud consumers
    private val depthBufferLock = Any()
    private var frontDepthBuffer: ByteBuffer? = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)
    private var backDepthBuffer: ByteBuffer? = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN)

    /**
     * Expose a read-only view of the latest raw depth millimeter values as a LITTLE_ENDIAN Float buffer.
     * Each pixel is a 32-bit float containing millimeters (e.g., 750.0f for 0.75m). May return null before first frame.
     */
    fun getDepthMillimeters(): ByteBuffer? = synchronized(depthBufferLock) {
        frontDepthBuffer?.let { buf ->
            val ro = buf.asReadOnlyBuffer()
            ro.order(ByteOrder.LITTLE_ENDIAN)
            ro
        }
    }

    override fun processFrameData(frame: Frame, buffer: ByteBuffer) {
        val data = frame.data.order(ByteOrder.LITTLE_ENDIAN)
        data.position(0)

        val shouldLog = (frame.sequence % 30L == 0L)

        // Prepare raw depth back buffer for mm values
        val rawBack = backDepthBuffer
        rawBack?.clear()

        // Process depth frame: convert to grayscale with INVERTED mapping
        // Standard "X-Ray" visualization: close=bright (255), far=dark (0)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val srcIdx = y * width + x
                val depthFloat = data.getFloat(srcIdx * 4)

                // Convert to int for gamma table lookup, handle invalid values
                val depthMm = when {
                    depthFloat <= 0f || depthFloat.isNaN() || depthFloat.isInfinite() -> 0
                    else -> depthFloat.toInt()
                }

                // Write raw mm as float to raw back buffer if available
                rawBack?.putFloat(srcIdx * 4, if (depthMm == 0) 0f else depthFloat)

                val dstIdx = (height - 1 - y) * width + x

                // Apply depth visualization with gamma correction
                val gray = depthGammaTable[depthMm.coerceIn(0, 65535)]
                val grayValue = gray.toByte()

                // Debug center pixel
                if (shouldLog && x == width/2 && y == height/2) {
                    logger.debug("DEPTH Center pixel: depthMm=$depthMm, gray=$gray (${gray*100/255}%)")
                }

                buffer.position(dstIdx * 4)
                buffer.put(grayValue)  // R
                buffer.put(grayValue)  // G
                buffer.put(grayValue)  // B
                buffer.put(255.toByte())  // A
            }
        }

        // Swap raw depth buffers so readers see the latest mm values
        synchronized(depthBufferLock) {
            val tmp = frontDepthBuffer
            frontDepthBuffer = backDepthBuffer
            backDepthBuffer = tmp
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
    private companion object { val logger = LoggerFactory.getLogger(Kinect2ColorCamera::class.java) }

    // Raw color (BGRX) double-buffered storage for registration consumers
    private val colorBufferLock = Any()
    private var frontColorBuffer: ByteBuffer? = ByteBuffer.allocateDirect(width * height * 4)
    private var backColorBuffer: ByteBuffer? = ByteBuffer.allocateDirect(width * height * 4)

    /**
     * Expose a read-only view of the latest raw color BGRX data.
     * Each pixel is 4 bytes (B, G, R, X). May return null before first frame.
     */
    fun getRawColorData(): ByteBuffer? = synchronized(colorBufferLock) {
        frontColorBuffer?.asReadOnlyBuffer()
    }

    override fun processFrameData(frame: Frame, buffer: ByteBuffer) {
        val data = frame.data

        // Store raw BGRX data in back buffer for registration
        val rawBack = backColorBuffer
        rawBack?.clear()
        if (rawBack != null) {
            data.position(0)
            rawBack.put(data)
        }

        // Convert BGRX to RGB with vertical flip for display
        data.position(0)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val srcIdx = y * width + x
                data.position(srcIdx * 4)  // 4 bytes per pixel (BGRX)

                val b = data.get()
                val g = data.get()
                val r = data.get()
                data.get() // Skip X byte

                // Flip vertically
                val dstIdx = (height - 1 - y) * width + x
                buffer.position(dstIdx * 3)  // 3 bytes per pixel (RGB)
                buffer.put(r)
                buffer.put(g)
                buffer.put(b)
            }
        }

        // Swap raw color buffers so readers see the latest BGRX data
        synchronized(colorBufferLock) {
            val tmp = frontColorBuffer
            frontColorBuffer = backColorBuffer
            backColorBuffer = tmp
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
    private companion object { val logger = LoggerFactory.getLogger(Kinect2IRCamera::class.java) }

    override fun processFrameData(frame: Frame, buffer: ByteBuffer) {
        val data = frame.data.order(ByteOrder.LITTLE_ENDIAN)

        // Convert float IR to grayscale with vertical flip
        // Use realistic max of 20000 instead of 65535 for better visibility
        // Most indoor IR values are 0-10000, so 20000 gives good contrast
        data.position(0)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val srcIdx = y * width + x
                val irFloat = data.getFloat(srcIdx * 4)

                // Convert to int for normalization, handle invalid values
                val ir = when {
                    irFloat <= 0f || irFloat.isNaN() || irFloat.isInfinite() -> 0
                    else -> irFloat.toInt()
                }

                // Normalize with realistic max for better visibility
                val gray = minOf(255, (ir * 255) / 20000)
                val grayValue = gray.toByte()

                // Flip vertically
                val dstIdx = (height - 1 - y) * width + x
                buffer.position(dstIdx * 4)  // 4 bytes per pixel (RGBa)

                // Write as RGBa (replicate gray value to R, G, B channels)
                buffer.put(grayValue)  // R
                buffer.put(grayValue)  // G
                buffer.put(grayValue)  // B
                buffer.put(255.toByte())  // A (fully opaque)
            }
        }
    }
}
