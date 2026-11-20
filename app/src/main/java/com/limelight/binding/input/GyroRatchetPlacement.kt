package com.limelight.binding.input

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * Where the gyro ratchet button is shown, from Settings → Gamepad → Controls.
 * [BOTTOM] falls back to [TOP] whenever no bottom-screen touchpad is showing.
 */
enum class GyroRatchetPlacement(val preferenceValue: String) {
    TOP("top"),
    BOTTOM("bottom"),
    OFF("off");

    companion object {
        const val PREF_KEY = "list_gyro_ratchet_placement"

        fun read(context: Context): GyroRatchetPlacement {
            val value = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF_KEY, TOP.preferenceValue)
            return entries.firstOrNull { it.preferenceValue == value } ?: TOP
        }
    }
}
