package org.openrndr.kinect2.airdrums

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector3
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Manages a collection of drum zones and provides serialization.
 */
class DrumKit {
    private val logger = LoggerFactory.getLogger(DrumKit::class.java)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    val zones: MutableList<DrumZone> = mutableListOf()

    /**
     * Add a drum zone to the kit.
     */
    fun addZone(zone: DrumZone) {
        zones.add(zone)
        logger.info("Added drum zone: ${zone.name} (MIDI ${zone.midiNote}) at ${zone.position}")
    }

    /**
     * Remove a drum zone by ID.
     */
    fun removeZone(id: String): Boolean {
        val removed = zones.removeIf { it.id == id }
        if (removed) {
            logger.info("Removed drum zone: $id")
        }
        return removed
    }

    /**
     * Find drum zone at a given 3D position (first match).
     */
    fun findZoneAt(position: Vector3): DrumZone? {
        return zones.firstOrNull { it.contains(position) }
    }

    /**
     * Find all zones that contain the given position.
     */
    fun findZonesAt(position: Vector3): List<DrumZone> {
        return zones.filter { it.contains(position) }
    }

    /**
     * Get zone by ID.
     */
    fun getZone(id: String): DrumZone? {
        return zones.firstOrNull { it.id == id }
    }

    /**
     * Clear all zones.
     */
    fun clear() {
        zones.clear()
        logger.info("Cleared all drum zones")
    }

    /**
     * Load a preset drum kit configuration.
     */
    fun loadPreset(preset: DrumKitPreset) {
        clear()
        when (preset) {
            DrumKitPreset.STANDARD -> loadStandardKit()
            DrumKitPreset.MINIMAL -> loadMinimalKit()
        }
        logger.info("Loaded preset: $preset (${zones.size} zones)")
    }

    /**
     * Save drum kit configuration to JSON file.
     */
    fun saveToFile(path: String) {
        val config = DrumKitConfig(
            name = File(path).nameWithoutExtension,
            zones = zones.map { it.toSerializable() }
        )
        File(path).writeText(gson.toJson(config))
        logger.info("Saved drum kit to: $path")
    }

    /**
     * Load drum kit configuration from JSON file.
     */
    fun loadFromFile(path: String) {
        val json = File(path).readText()
        val config = gson.fromJson(json, DrumKitConfig::class.java)
        clear()
        config.zones.forEach { zones.add(it.toDrumZone()) }
        logger.info("Loaded drum kit from: $path (${zones.size} zones)")
    }

    /**
     * Standard right-handed drum kit (6 pieces).
     * Layout mimics typical acoustic drum setup:
     * - Hi-Hat: Upper left
     * - Snare: Center
     * - High Tom: Upper center-left
     * - Mid Tom: Upper center-right  
     * - Low Tom: Lower right (floor tom)
     * - Crash: Upper right
     * - Ride: Far upper right
     */
    private fun loadStandardKit() {
        addZone(DrumZone(
            id = "snare",
            name = "Snare",
            position = Vector3(0.0, -0.2, 0.7),  // Center, waist height
            radius = 0.15,
            midiNote = DrumZone.MIDI_SNARE,
            color = ColorRGBa.fromHex(0xFF6B6B)
        ))
        
        addZone(DrumZone(
            id = "hihat",
            name = "Hi-Hat",
            position = Vector3(-0.35, 0.1, 0.6),  // Left, higher
            radius = 0.12,
            midiNote = DrumZone.MIDI_CLOSED_HI_HAT,
            color = ColorRGBa.fromHex(0x4ECDC4)
        ))

        addZone(DrumZone(
            id = "high-tom",
            name = "High Tom",
            position = Vector3(-0.25, 0.0, 0.65),  // Center-left, elevated
            radius = 0.14,
            midiNote = DrumZone.MIDI_HIGH_TOM,
            color = ColorRGBa.fromHex(0x95E1D3)
        ))

        addZone(DrumZone(
            id = "mid-tom",
            name = "Mid Tom",
            position = Vector3(0.25, 0.0, 0.65),  // Center-right, elevated
            radius = 0.14,
            midiNote = DrumZone.MIDI_MID_TOM,
            color = ColorRGBa.fromHex(0xF38181)
        ))

        addZone(DrumZone(
            id = "crash",
            name = "Crash",
            position = Vector3(0.45, 0.2, 0.75),  // Right, high
            radius = 0.18,
            midiNote = DrumZone.MIDI_CRASH_CYMBAL,
            color = ColorRGBa.fromHex(0xFFD93D)
        ))

        addZone(DrumZone(
            id = "ride",
            name = "Ride",
            position = Vector3(0.55, 0.15, 0.85),  // Far right, high
            radius = 0.20,
            midiNote = DrumZone.MIDI_RIDE_CYMBAL,
            color = ColorRGBa.fromHex(0xAA96DA)
        ))
    }

    /**
     * Minimal 4-piece drum kit for quick testing.
     */
    private fun loadMinimalKit() {
        addZone(DrumZone(
            id = "snare",
            name = "Snare",
            position = Vector3(0.0, -0.2, 0.7),
            radius = 0.15,
            midiNote = DrumZone.MIDI_SNARE,
            color = ColorRGBa.fromHex(0xFF6B6B)
        ))

        addZone(DrumZone(
            id = "hihat",
            name = "Hi-Hat",
            position = Vector3(-0.35, 0.05, 0.6),
            radius = 0.12,
            midiNote = DrumZone.MIDI_CLOSED_HI_HAT,
            color = ColorRGBa.fromHex(0x4ECDC4)
        ))

        addZone(DrumZone(
            id = "crash",
            name = "Crash",
            position = Vector3(0.4, 0.15, 0.75),
            radius = 0.18,
            midiNote = DrumZone.MIDI_CRASH_CYMBAL,
            color = ColorRGBa.fromHex(0xFFD93D)
        ))

        addZone(DrumZone(
            id = "kick",
            name = "Kick",
            position = Vector3(0.0, -0.6, 0.5),  // Low, center (foot position)
            radius = 0.20,
            midiNote = DrumZone.MIDI_KICK,
            color = ColorRGBa.fromHex(0x6C5CE7)
        ))
    }
}

/**
 * Drum kit preset options.
 */
enum class DrumKitPreset {
    STANDARD,  // 6-piece right-handed kit
    MINIMAL    // 4-piece simple kit
}

/**
 * Serializable drum kit configuration (for JSON).
 */
private data class DrumKitConfig(
    val name: String,
    val zones: List<SerializableDrumZone>
)

/**
 * Serializable drum zone (flat structure for JSON).
 */
private data class SerializableDrumZone(
    val id: String,
    val name: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double,
    val midiNote: Int,
    val midiChannel: Int,
    val colorHex: String
) {
    fun toDrumZone(): DrumZone {
        return DrumZone(
            id = id,
            name = name,
            position = Vector3(x, y, z),
            radius = radius,
            midiNote = midiNote,
            midiChannel = midiChannel,
            color = ColorRGBa.fromHex(colorHex)
        )
    }
}

/**
 * Extension to convert DrumZone to serializable format.
 */
private fun DrumZone.toSerializable(): SerializableDrumZone {
    return SerializableDrumZone(
        id = id,
        name = name,
        x = position.x,
        y = position.y,
        z = position.z,
        radius = radius,
        midiNote = midiNote,
        midiChannel = midiChannel,
        colorHex = colorToHexString(color)
    )
}

/**
 * Convert ColorRGBa to hex string (e.g., "#ff6b6b").
 */
private fun colorToHexString(color: ColorRGBa): String {
    val r = (color.r * 255).toInt().coerceIn(0, 255)
    val g = (color.g * 255).toInt().coerceIn(0, 255)
    val b = (color.b * 255).toInt().coerceIn(0, 255)
    return "#%02x%02x%02x".format(r, g, b)
}
