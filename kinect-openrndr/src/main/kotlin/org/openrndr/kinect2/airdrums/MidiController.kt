package org.openrndr.kinect2.airdrums

import org.slf4j.LoggerFactory
import javax.sound.midi.*

/**
 * MIDI output controller for air drums.
 * 
 * Wraps javax.sound.midi for clean Kotlin API.
 * Handles device connection, note triggering, and cleanup.
 */
class MidiController {
    private val logger = LoggerFactory.getLogger(MidiController::class.java)
    
    private var midiDevice: MidiDevice? = null
    private var receiver: Receiver? = null
    
    var connected: Boolean = false
        private set
    
    var currentDeviceName: String? = null
        private set
    
    /**
     * List all available MIDI output devices.
     */
    fun listDevices(): List<MidiDeviceInfo> {
        val devices = mutableListOf<MidiDeviceInfo>()
        
        for (info in MidiSystem.getMidiDeviceInfo()) {
            val device = MidiSystem.getMidiDevice(info)
            
            // Filter for output devices (receivers)
            if (device.maxReceivers != 0) {
                devices.add(MidiDeviceInfo(
                    name = info.name,
                    description = info.description,
                    vendor = info.vendor,
                    version = info.version
                ))
            }
        }
        
        return devices
    }
    
    /**
     * Connect to MIDI device by name (partial match).
     * 
     * @param deviceName Device name or partial name (e.g., "IAC" matches "IAC Driver")
     * @return true if connected successfully
     */
    fun connect(deviceName: String): Boolean {
        disconnect()
        
        logger.info("Searching for MIDI device: $deviceName")
        
        for (info in MidiSystem.getMidiDeviceInfo()) {
            if (!info.name.contains(deviceName, ignoreCase = true)) continue
            
            try {
                val device = MidiSystem.getMidiDevice(info)
                
                // Check if device can receive MIDI
                if (device.maxReceivers == 0) {
                    logger.warn("Device ${info.name} cannot receive MIDI")
                    continue
                }
                
                device.open()
                val recv = device.receiver
                
                midiDevice = device
                receiver = recv
                connected = true
                currentDeviceName = info.name
                
                logger.info("Connected to MIDI device: ${info.name}")
                return true
                
            } catch (e: MidiUnavailableException) {
                logger.error("Failed to open MIDI device: ${info.name}", e)
            }
        }
        
        logger.error("MIDI device not found: $deviceName")
        return false
    }
    
    /**
     * Connect to first available MIDI device.
     * Prefers devices with "IAC" in name (macOS virtual MIDI bus).
     */
    fun connectToFirstAvailable(): Boolean {
        val devices = listDevices()
        
        if (devices.isEmpty()) {
            logger.error("No MIDI devices available")
            return false
        }
        
        // Try IAC Driver first (macOS virtual MIDI)
        val iacDevice = devices.firstOrNull {
            it.name.contains("IAC", ignoreCase = true) ||
            it.description.contains("IAC", ignoreCase = true)
        }
        if (iacDevice != null) {
            return connect(iacDevice.name)
        }
        
        // Fall back to first available device
        return connect(devices.first().name)
    }
    
    /**
     * Send MIDI Note On message.
     * 
     * @param note MIDI note number (0-127)
     * @param velocity Note velocity (1-127, 0 is treated as Note Off)
     * @param channel MIDI channel (0-15, default 9 for drums)
     */
    fun sendNoteOn(note: Int, velocity: Int, channel: Int = 9) {
        if (!connected) {
            logger.warn("Cannot send MIDI: not connected")
            return
        }
        
        require(note in 0..127) { "Note must be 0-127, got $note" }
        require(velocity in 0..127) { "Velocity must be 0-127, got $velocity" }
        require(channel in 0..15) { "Channel must be 0-15, got $channel" }
        
        try {
            val message = ShortMessage()
            message.setMessage(ShortMessage.NOTE_ON, channel, note, velocity)
            receiver?.send(message, -1)  // -1 = send immediately
            
            logger.trace("NOTE_ON: note=$note, velocity=$velocity, channel=$channel")
            
        } catch (e: InvalidMidiDataException) {
            logger.error("Invalid MIDI message", e)
        }
    }
    
    /**
     * Send MIDI Note Off message.
     * 
     * @param note MIDI note number (0-127)
     * @param channel MIDI channel (0-15, default 9 for drums)
     */
    fun sendNoteOff(note: Int, channel: Int = 9) {
        if (!connected) {
            logger.warn("Cannot send MIDI: not connected")
            return
        }
        
        require(note in 0..127) { "Note must be 0-127, got $note" }
        require(channel in 0..15) { "Channel must be 0-15, got $channel" }
        
        try {
            val message = ShortMessage()
            message.setMessage(ShortMessage.NOTE_OFF, channel, note, 0)
            receiver?.send(message, -1)
            
            logger.trace("NOTE_OFF: note=$note, channel=$channel")
            
        } catch (e: InvalidMidiDataException) {
            logger.error("Invalid MIDI message", e)
        }
    }
    
    /**
     * Play a drum hit (Note On followed by Note Off after duration).
     * 
     * @param hit DrumHit containing zone and velocity information
     * @param durationMs Note duration in milliseconds (default 100ms)
     */
    fun playHit(hit: DrumHit, durationMs: Long = 100) {
        sendNoteOn(hit.zone.midiNote, hit.velocity, hit.zone.midiChannel)
        
        // Schedule Note Off
        Thread {
            Thread.sleep(durationMs)
            sendNoteOff(hit.zone.midiNote, hit.zone.midiChannel)
        }.start()
    }
    
    /**
     * Disconnect from MIDI device.
     */
    fun disconnect() {
        receiver?.close()
        midiDevice?.close()
        
        receiver = null
        midiDevice = null
        connected = false
        currentDeviceName = null
        
        logger.info("Disconnected from MIDI device")
    }
    
    /**
     * Clean up resources.
     */
    fun close() {
        disconnect()
    }
}

/**
 * MIDI device information.
 */
data class MidiDeviceInfo(
    val name: String,
    val description: String,
    val vendor: String,
    val version: String
) {
    override fun toString(): String {
        return "$name ($vendor) - $description"
    }
}
