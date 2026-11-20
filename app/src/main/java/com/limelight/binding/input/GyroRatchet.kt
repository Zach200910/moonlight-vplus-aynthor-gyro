package com.limelight.binding.input

/**
 * Gyro ratchet: while [held] is true, no gyro motion reaches the PC (or the gyro-to-mouse and
 * gyro-to-stick mappings), so the handheld can be re-centred without moving the camera.
 * Written by the on-screen ratchet button, read by the gyro sensor listener.
 */
object GyroRatchet {
    @Volatile
    var held: Boolean = false
}
