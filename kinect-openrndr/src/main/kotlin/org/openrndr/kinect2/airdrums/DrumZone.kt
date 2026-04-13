package org.openrndr.kinect2.airdrums

import org.openrndr.color.ColorRGBa
import org.openrndr.math.Vector3

/**
 * Represents a single drum pad zone in 3D space.
 *
 * @property id Unique identifier for this drum zone
 * @property name Display name (e.g., "Snare", "Hi-Hat")
 * @property position Position in camera space (meters from camera origin)
 *                    x = left(-)/right(+), y = down(-)/up(+), z = depth(+)
 * @property radius Size of the hit zone in meters
 * @property midiNote General MIDI note number (0-127)
 * @property midiChannel MIDI channel (0-15, default 9 for drums)
 * @property color Visual color for rendering
 */
data class DrumZone(
    val id: String,
    val name: String,
    val position: Vector3,
    val radius: Double,
    val midiNote: Int,
    val midiChannel: Int = 9,  // Channel 10 (index 9) is standard MIDI drums
    val color: ColorRGBa
) {
    init {
        require(midiNote in 0..127) { "MIDI note must be in range 0-127, got $midiNote" }
        require(midiChannel in 0..15) { "MIDI channel must be in range 0-15, got $midiChannel" }
        require(radius > 0) { "Radius must be positive, got $radius" }
    }

    /**
     * Check if a 3D point is inside this drum zone.
     */
    fun contains(point: Vector3): Boolean {
        return position.distanceTo(point) <= radius
    }

    /**
     * Get distance from point to zone center (for proximity detection).
     */
    fun distanceTo(point: Vector3): Double {
        return position.distanceTo(point)
    }

    companion object {
        // General MIDI Standard Drum Mapping
        const val MIDI_KICK = 36
        const val MIDI_SNARE = 38
        const val MIDI_CLOSED_HI_HAT = 42
        const val MIDI_OPEN_HI_HAT = 46
        const val MIDI_LOW_TOM = 45
        const val MIDI_MID_TOM = 48
        const val MIDI_HIGH_TOM = 50
        const val MIDI_CRASH_CYMBAL = 49
        const val MIDI_RIDE_CYMBAL = 51
        const val MIDI_RIDE_BELL = 53
    }
}
