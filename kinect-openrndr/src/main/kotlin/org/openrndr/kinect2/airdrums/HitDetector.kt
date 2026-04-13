package org.openrndr.kinect2.airdrums

import org.openrndr.math.Vector3
import org.slf4j.LoggerFactory
import kotlin.math.max
import kotlin.math.min

/**
 * Provides hand velocity information for hit detection.
 * Implemented by both HandTracker (depth-only) and PythonHandTracker (MediaPipe).
 */
interface VelocityProvider {
    fun getHandVelocityVector(currentPosition: Vector3): Vector3
    fun getHandVelocity(currentPosition: Vector3): Double
}

/**
 * Detects drum hits based on hand positions entering drum zones with velocity.
 * 
 * Features:
 * - Velocity-sensitive hit detection (faster hits = louder MIDI notes)
 * - Debouncing to prevent double-hits
 * - Directional filtering (optional - require downward motion)
 */
class HitDetector(
    private val drumKit: DrumKit,
    private var velocityProvider: VelocityProvider
) {
    private val logger = LoggerFactory.getLogger(HitDetector::class.java)
    
    // Configuration
    var minVelocity = 0.3  // Minimum velocity for hit detection (m/s)
    var maxVelocity = 3.0  // Maximum velocity for MIDI scaling (m/s)
    var debounceTimeMs = 150L  // Minimum time between hits on same zone (ms)
    var requireDownwardMotion = true   // Only detect hits with a downward component
    
    // Hit history for debouncing
    private val hitHistory = mutableMapOf<String, Long>()  // zoneId -> lastHitTimestamp
    
    // Previous hand positions for zone entry detection
    private val previousHandsInZones = mutableMapOf<String, MutableSet<String>>()  // zoneId -> set of hand IDs
    
    /**
     * Update hit detection with current hand positions.
     * 
     * @param hands Currently detected hands
     * @return List of drum hits that occurred this frame
     */
    fun update(hands: List<HandPosition>): List<DrumHit> {
        val currentTime = System.currentTimeMillis()
        val hits = mutableListOf<DrumHit>()
        
        // Track which hands are currently in each zone
        val currentHandsInZones = mutableMapOf<String, MutableSet<String>>()
        
        for ((handIdx, hand) in hands.withIndex()) {
            val handId = "${hand.handedness}_$handIdx"
            
            // Check all drum zones
            for (zone in drumKit.zones) {
                val inZone = zone.contains(hand.position)
                
                if (inZone) {
                    // Track that this hand is in this zone
                    currentHandsInZones.getOrPut(zone.id) { mutableSetOf() }.add(handId)
                    
                    // Check if this is a NEW entry (hand just entered zone)
                    val wasInZone = previousHandsInZones[zone.id]?.contains(handId) == true
                    
                    if (!wasInZone) {
                        // Hand just entered zone - check for hit
                        val hit = detectHit(hand, zone, currentTime)
                        if (hit != null) {
                            hits.add(hit)
                            logger.info("HIT! ${zone.name} - velocity ${hit.velocity}")
                        }
                    }
                }
            }
        }
        
        // Update previous state
        previousHandsInZones.clear()
        previousHandsInZones.putAll(currentHandsInZones)
        
        return hits
    }
    
    /**
     * Detect if a hand entry constitutes a hit.
     * 
     * Returns DrumHit if:
     * - Velocity is above minimum threshold
     * - Zone hasn't been hit recently (debouncing)
     * - Motion is downward (if requireDownwardMotion is enabled)
     */
    private fun detectHit(hand: HandPosition, zone: DrumZone, currentTime: Long): DrumHit? {
        // Check debouncing
        val lastHitTime = hitHistory[zone.id]
        if (lastHitTime != null && (currentTime - lastHitTime) < debounceTimeMs) {
            logger.trace("Debounced hit on ${zone.name} (too soon after last hit)")
            return null
        }
        
        // Calculate velocity
        val velocityVector = velocityProvider.getHandVelocityVector(hand.position)
        val velocityMagnitude = velocityVector.length
        
        // Check minimum velocity
        if (velocityMagnitude < minVelocity) {
            logger.trace("Velocity too low: $velocityMagnitude m/s < $minVelocity m/s")
            return null
        }
        
        // Check downward motion (optional)
        if (requireDownwardMotion && velocityVector.y >= 0) {
            logger.trace("Not downward motion (Y velocity: ${velocityVector.y})")
            return null
        }
        
        // Map velocity to MIDI velocity (1-127)
        val midiVelocity = velocityToMidiVelocity(velocityMagnitude)
        
        // Record hit
        hitHistory[zone.id] = currentTime
        
        return DrumHit(
            zone = zone,
            velocity = midiVelocity,
            timestamp = currentTime,
            handPosition = hand.position,
            hitVelocity = velocityMagnitude
        )
    }
    
    /**
     * Map physical velocity (m/s) to MIDI velocity (1-127).
     * 
     * Uses logarithmic scaling for more natural feel:
     * - Soft hits (0.3-0.8 m/s) -> 40-80
     * - Medium hits (0.8-1.5 m/s) -> 80-100
     * - Hard hits (1.5-3.0+ m/s) -> 100-127
     */
    private fun velocityToMidiVelocity(velocityMs: Double): Int {
        // Clamp to configured range
        val clamped = max(minVelocity, min(velocityMs, maxVelocity))
        
        // Normalize to 0-1
        val normalized = (clamped - minVelocity) / (maxVelocity - minVelocity)
        
        // Apply curve (slight power curve for more sensitive soft hits)
        val curved = Math.pow(normalized, 0.8)
        
        // Map to MIDI range (20-127, avoiding very quiet notes)
        val midiVelocity = (20 + curved * 107).toInt()
        
        return midiVelocity.coerceIn(1, 127)
    }
    
    /**
     * Clear hit history (useful for resetting state).
     */
    fun reset() {
        hitHistory.clear()
        previousHandsInZones.clear()
        logger.info("HitDetector reset")
    }

    /**
     * Switch the velocity provider (e.g., from HandTracker to PythonHandTracker).
     */
    fun setVelocityProvider(provider: VelocityProvider) {
        velocityProvider = provider
        reset()
    }
}

/**
 * Represents a detected drum hit.
 * 
 * @property zone The drum zone that was hit
 * @property velocity MIDI velocity (1-127)
 * @property timestamp Time of hit (milliseconds)
 * @property handPosition 3D position of hand at time of hit
 * @property hitVelocity Physical velocity of hit (m/s)
 */
data class DrumHit(
    val zone: DrumZone,
    val velocity: Int,
    val timestamp: Long,
    val handPosition: Vector3,
    val hitVelocity: Double
)
