package org.openrndr.kinect2.airdrums

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.openrndr.math.Vector3
import org.slf4j.LoggerFactory
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Hand tracker that delegates to a Python MediaPipe subprocess.
 *
 * Protocol:
 *   - Sends BGRX frames to Python stdin (header: width/height as LE uint32, then pixel data)
 *   - Reads JSON landmark results from Python stdout (one line per frame)
 *
 * The Python service uses MediaPipe Hands which provides:
 *   - 21 hand landmarks per hand (we use INDEX_FINGER_TIP = landmark 8)
 *   - Persistent hand IDs with Left/Right classification
 *   - GPU-accelerated detection on Apple Silicon
 *   - 30-60 FPS performance
 *
 * Lifecycle: call [start] before use, [stop] when done.
 */
class PythonHandTracker(
    private val coordinateMapper: CoordinateMapper
) : VelocityProvider {
    private val logger = LoggerFactory.getLogger(PythonHandTracker::class.java)
    private val gson = Gson()

    // Python process
    private var process: Process? = null
    private var processWriter: OutputStream? = null
    private var processReader: BufferedReader? = null
    private var ready = false

    // Hand position history for velocity calculation (mirrors HandTracker API)
    private val handHistory = mutableListOf<List<HandPosition>>()
    private val maxHistoryFrames = 5

    // Tracking index finger tip (MediaPipe landmark index 8)
    private val FINGER_TIP_INDEX = 8

    /**
     * Start the Python MediaPipe subprocess.
     *
     * Looks for the virtual environment in scripts/.venv first,
     * falls back to system python3.
     */
    fun start(): Boolean {
        if (process != null) {
            logger.warn("Python tracker already running")
            return true
        }

        try {
            val scriptDir = findScriptsDir()
            val scriptPath = File(scriptDir, "hand_tracker_service.py").absolutePath
            val venvPython = File(scriptDir, ".venv/bin/python3").absolutePath

            // Prefer venv python, fall back to system python3
            val python = if (File(venvPython).exists()) {
                logger.info("Using venv Python: $venvPython")
                venvPython
            } else {
                logger.warn("Venv not found at $venvPython, using system python3")
                "python3"
            }

            logger.info("Starting Python hand tracker: $python $scriptPath")

            val pb = ProcessBuilder(python, scriptPath)
            pb.redirectErrorStream(false)  // Keep stderr separate for debugging
            pb.environment()["PYTHONUNBUFFERED"] = "1"

            val proc = pb.start()
            process = proc
            processWriter = proc.outputStream
            processReader = BufferedReader(InputStreamReader(proc.inputStream))

            // Start error stream reader (log Python errors)
            Thread({
                try {
                    BufferedReader(InputStreamReader(proc.errorStream)).use { errReader ->
                        errReader.lineSequence().forEach { line ->
                            logger.debug("[Python] $line")
                        }
                    }
                } catch (_: IOException) {
                    // Process closed
                }
            }, "python-stderr").apply { isDaemon = true; start() }

            // Wait for ready signal (with timeout)
            val readyLine = processReader?.readLine()
            if (readyLine != null) {
                val json = JsonParser.parseString(readyLine).asJsonObject
                if (json.has("status") && json.get("status").asString == "ready") {
                    ready = true
                    logger.info("Python hand tracker ready (version: ${json.get("version")?.asString})")
                    return true
                } else if (json.has("error")) {
                    logger.error("Python tracker error: ${json.get("error").asString}")
                    stop()
                    return false
                }
            }

            logger.error("Python tracker did not send ready signal")
            stop()
            return false

        } catch (e: Exception) {
            logger.error("Failed to start Python hand tracker", e)
            stop()
            return false
        }
    }

    /**
     * Send a color frame to the Python service and get hand detections.
     *
     * @param colorData Raw BGRX pixel data (from Kinect color camera or registered buffer)
     * @param width Frame width
     * @param height Frame height
     * @param depthData Raw depth data for 3D position lookup (optional)
     * @param depthWidth Depth frame width (512)
     * @param depthHeight Depth frame height (424)
     * @return Detected hand positions in 3D camera space
     */
    fun detectHands(
        colorData: ByteBuffer,
        width: Int,
        height: Int,
        depthData: ByteBuffer?,
        depthWidth: Int = 512,
        depthHeight: Int = 424
    ): List<HandPosition> {
        if (!ready || processWriter == null || processReader == null) {
            return emptyList()
        }

        try {
            val writer = processWriter!!
            val reader = processReader!!

            // Write frame header: width + height as LE uint32
            val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(width)
            header.putInt(height)
            writer.write(header.array())

            // Write BGRX pixel data
            val pixelCount = width * height * 4
            val pixelBytes = ByteArray(pixelCount)
            colorData.rewind()
            colorData.get(pixelBytes)
            writer.write(pixelBytes)
            writer.flush()

            // Read JSON response
            val responseLine = reader.readLine() ?: return emptyList()
            val response = JsonParser.parseString(responseLine).asJsonObject

            if (response.has("error")) {
                logger.warn("Python tracker error: ${response.get("error").asString}")
                return emptyList()
            }

            // Parse hands
            val handsArray = response.getAsJsonArray("hands") ?: return emptyList()
            val hands = mutableListOf<HandPosition>()

            for (handElement in handsArray) {
                val handObj = handElement.asJsonObject
                val handedness = when (handObj.get("handedness").asString) {
                    "Left" -> Handedness.LEFT
                    "Right" -> Handedness.RIGHT
                    else -> Handedness.UNKNOWN
                }
                val score = handObj.get("score").asFloat
                val landmarks = handObj.getAsJsonArray("landmarks")

                // Use index finger tip (landmark 8) for drumming position
                if (landmarks.size() > FINGER_TIP_INDEX) {
                    val tipLandmark = landmarks[FINGER_TIP_INDEX].asJsonArray
                    val normalizedX = tipLandmark[0].asDouble
                    val normalizedY = tipLandmark[1].asDouble

                    // Convert normalized RGB coordinates to 3D camera space
                    val position = coordinateMapper.rgbNormalizedTo3D(
                        normalizedX, normalizedY,
                        width, height,
                        depthData, depthWidth, depthHeight
                    )

                    if (position != null) {
                        hands.add(HandPosition(position, handedness, score))
                    }
                }
            }

            // Update history for velocity tracking
            updateHistory(hands)

            return hands

        } catch (e: Exception) {
            logger.error("Error communicating with Python tracker", e)
            return emptyList()
        }
    }

    /**
     * Calculate hand velocity (m/s) based on recent history.
     * Compatible with HitDetector's expected API.
     */
    override fun getHandVelocityVector(currentPosition: Vector3): Vector3 {
        if (handHistory.size < 2) return Vector3.ZERO

        val prevFrame = handHistory[handHistory.size - 2]
        val prevHand = prevFrame.minByOrNull { it.position.distanceTo(currentPosition) }
            ?: return Vector3.ZERO

        val displacement = currentPosition - prevHand.position
        val deltaTime = 1.0 / 30.0  // Assume 30 FPS

        return displacement / deltaTime
    }

    /**
     * Get scalar velocity for a position.
     */
    override fun getHandVelocity(currentPosition: Vector3): Double {
        return getHandVelocityVector(currentPosition).length
    }

    private fun updateHistory(hands: List<HandPosition>) {
        handHistory.add(hands)
        if (handHistory.size > maxHistoryFrames) {
            handHistory.removeAt(0)
        }
    }

    /**
     * Stop the Python subprocess gracefully.
     */
    fun stop() {
        ready = false

        try {
            processWriter?.close()
        } catch (_: IOException) {}

        process?.let { proc ->
            try {
                // Give it a moment to shut down gracefully
                if (proc.isAlive) {
                    proc.destroy()
                    if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        proc.destroyForcibly()
                        logger.warn("Force-killed Python tracker process")
                    }
                }
                logger.info("Python hand tracker stopped (exit code: ${proc.exitValue()})")
            } catch (e: Exception) {
                logger.warn("Error stopping Python tracker", e)
            }
        }

        processWriter = null
        processReader = null
        process = null
        handHistory.clear()
    }

    /**
     * Check if the Python process is running and responsive.
     */
    val isRunning: Boolean
        get() = ready && process?.isAlive == true

    /**
     * Find the scripts directory relative to the project.
     * Searches up from CWD and common locations.
     */
    private fun findScriptsDir(): File {
        // Try relative to CWD (common when running from project root)
        val candidates = listOf(
            File("kinect-openrndr/scripts"),
            File("scripts"),
            File("../scripts"),
            // Absolute fallback using class resource
            File(System.getProperty("user.dir"), "kinect-openrndr/scripts")
        )

        for (candidate in candidates) {
            if (candidate.exists() && File(candidate, "hand_tracker_service.py").exists()) {
                return candidate.canonicalFile
            }
        }

        throw FileNotFoundException(
            "Cannot find scripts/hand_tracker_service.py. " +
            "Searched: ${candidates.map { it.absolutePath }}"
        )
    }
}
