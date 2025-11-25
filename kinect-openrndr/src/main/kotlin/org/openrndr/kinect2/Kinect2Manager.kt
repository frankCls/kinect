package org.openrndr.kinect2

import com.kinect.jni.Freenect
import com.kinect.jni.FreenectContext
import org.slf4j.LoggerFactory

/**
 * Device information for a Kinect V2 sensor.
 *
 * @property index Device index (0-based)
 * @property serial Device serial number
 */
data class Kinect2DeviceInfo(
    val index: Int,
    val serial: String
)

/**
 * Manager for Kinect V2 device discovery and enumeration.
 *
 * Provides utilities to list available devices and check library status.
 */
object Kinect2Manager {
    private val logger = LoggerFactory.getLogger(Kinect2Manager::class.java)

    /**
     * Check if the Kinect native library is loaded.
     *
     * @return true if library is available, false otherwise
     */
    fun isLibraryLoaded(): Boolean = runCatching { Freenect.isLibraryLoaded() }
        .onFailure { logger.error("Error checking library status", it) }
        .getOrDefault(false)

    /**
     * Get libfreenect2 version string.
     *
     * @return version string, or null if library not loaded
     */
    fun getLibraryVersion(): String? = runCatching {
        if (isLibraryLoaded()) Freenect.getVersion() else null
    }.onFailure { logger.error("Error getting library version", it) }.getOrNull()

    /**
     * Get list of available Kinect V2 devices.
     *
     * Uses the singleton FreenectContext for enumeration to avoid
     * rapid context creation/destruction issues on macOS.
     *
     * @return list of device information, empty if no devices or library not loaded
     */
    fun getKinectsV2(): List<Kinect2DeviceInfo> {
        if (!isLibraryLoaded()) {
            logger.warn("Kinect library not loaded, cannot enumerate devices")
            return emptyList()
        }
        return runCatching {
            val context = FreenectContextManager.getContext()
            val count = context.getDeviceCount()
            logger.debug("Found $count Kinect V2 device(s)")
            buildList {
                repeat(count) { index ->
                    add(Kinect2DeviceInfo(index, context.getDeviceSerial(index)))
                }
            }
        }.onFailure { logger.error("Error enumerating Kinect devices", it) }
            .getOrDefault(emptyList())
    }

    /**
     * Get the number of connected Kinect V2 devices.
     *
     * @return device count, or 0 if library not loaded or error occurs
     */
    fun getDeviceCount(): Int = getKinectsV2().size

    /**
     * Check if any Kinect V2 devices are connected.
     *
     * @return true if at least one device is available
     */
    fun hasDevices(): Boolean = getDeviceCount() > 0

    /**
     * Get default device information (first device).
     *
     * @return device info, or null if no devices available
     */
    fun getDefaultDevice(): Kinect2DeviceInfo? = getKinectsV2().firstOrNull()

    /**
     * Get device information by index.
     *
     * @param index device index (0-based)
     * @return device info, or null if index out of range
     */
    fun getDevice(index: Int): Kinect2DeviceInfo? = getKinectsV2().getOrNull(index)

    /**
     * Get device information by serial number.
     *
     * @param serial device serial number
     * @return device info, or null if not found
     */
    fun getDeviceBySerial(serial: String): Kinect2DeviceInfo? =
        getKinectsV2().firstOrNull { it.serial == serial }

    /**
     * Print device information to console for debugging.
     */
    fun printDeviceInfo() {
        println("=== Kinect V2 Device Information ===")
        println("Library loaded: ${isLibraryLoaded()}")
        println("Library version: ${getLibraryVersion() ?: "N/A"}")

        val devices = getKinectsV2()
        println("Devices found: ${devices.size}")

        devices.forEachIndexed { index, device ->
            println("  [$index] Serial: ${device.serial}")
        }
    }
}
