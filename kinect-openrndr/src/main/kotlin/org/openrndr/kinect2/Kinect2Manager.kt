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
    fun isLibraryLoaded(): Boolean {
        return try {
            Freenect.isLibraryLoaded()
        } catch (e: Exception) {
            logger.error("Error checking library status", e)
            false
        }
    }

    /**
     * Get libfreenect2 version string.
     *
     * @return version string, or null if library not loaded
     */
    fun getLibraryVersion(): String? {
        return try {
            if (isLibraryLoaded()) {
                Freenect.getVersion()
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Error getting library version", e)
            null
        }
    }

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

        return try {
            val context = FreenectContextManager.getContext()
            val count = context.getDeviceCount()
            logger.debug("Found $count Kinect V2 device(s)")

            (0 until count).map { index ->
                val serial = context.getDeviceSerial(index)
                Kinect2DeviceInfo(index, serial)
            }
        } catch (e: Exception) {
            logger.error("Error enumerating Kinect devices", e)
            emptyList()
        }
    }

    /**
     * Get the number of connected Kinect V2 devices.
     *
     * @return device count, or 0 if library not loaded or error occurs
     */
    fun getDeviceCount(): Int {
        return getKinectsV2().size
    }

    /**
     * Check if any Kinect V2 devices are connected.
     *
     * @return true if at least one device is available
     */
    fun hasDevices(): Boolean {
        return getDeviceCount() > 0
    }

    /**
     * Get default device information (first device).
     *
     * @return device info, or null if no devices available
     */
    fun getDefaultDevice(): Kinect2DeviceInfo? {
        val devices = getKinectsV2()
        return devices.firstOrNull()
    }

    /**
     * Get device information by index.
     *
     * @param index device index (0-based)
     * @return device info, or null if index out of range
     */
    fun getDevice(index: Int): Kinect2DeviceInfo? {
        val devices = getKinectsV2()
        return devices.getOrNull(index)
    }

    /**
     * Get device information by serial number.
     *
     * @param serial device serial number
     * @return device info, or null if not found
     */
    fun getDeviceBySerial(serial: String): Kinect2DeviceInfo? {
        val devices = getKinectsV2()
        return devices.firstOrNull { it.serial == serial }
    }

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
