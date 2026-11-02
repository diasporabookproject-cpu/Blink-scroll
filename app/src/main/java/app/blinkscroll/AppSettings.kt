package app.blinkscroll

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.blinkscroll.audio.SoundProfile
import java.io.File

/** User preferences, stored in SharedPreferences (readable from any thread). */
class AppSettings(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("blink_settings", Context.MODE_PRIVATE)

    // --- Triggers -------------------------------------------------------------

    var soundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND, true)
        set(value) = prefs.edit { putBoolean(KEY_SOUND, value) }

    var proximityEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXIMITY, false)
        set(value) = prefs.edit { putBoolean(KEY_PROXIMITY, value) }

    var eyesEnabled: Boolean
        get() = prefs.getBoolean(KEY_EYES, false)
        set(value) = prefs.edit { putBoolean(KEY_EYES, value) }

    /** Sound and hand wave: two in a row = previous video. */
    var doubleForPrevious: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE, true)
        set(value) = prefs.edit { putBoolean(KEY_DOUBLE, value) }

    /** -0.10 (stricter) .. +0.10 (more sensitive). */
    var soundSensitivity: Float
        get() = prefs.getFloat(KEY_SOUND_SENSITIVITY, 0f)
        set(value) = prefs.edit { putFloat(KEY_SOUND_SENSITIVITY, value) }

    /** Quality of the learned sound (ProfileQuality name), empty if not learned. */
    var soundQuality: String
        get() = prefs.getString(KEY_SOUND_QUALITY, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SOUND_QUALITY, value) }

    val soundProfileFile: File get() = File(appContext.filesDir, "sound_profile.txt")

    val hasSoundProfile: Boolean get() = soundProfileFile.exists()

    fun loadSoundProfile(): SoundProfile? = SoundProfile.load(soundProfileFile)

    // --- Eyes (camera) --------------------------------------------------------

    var mode: TriggerMode
        get() = runCatching { TriggerMode.valueOf(prefs.getString(KEY_MODE, null).orEmpty()) }
            .getOrDefault(TriggerMode.WINK_ANY)
        set(value) = prefs.edit { putString(KEY_MODE, value.name) }

    var holdMs: Long
        get() = prefs.getLong(KEY_HOLD, 250L)
        set(value) = prefs.edit { putLong(KEY_HOLD, value) }

    var closedThreshold: Float
        get() = prefs.getFloat(KEY_THRESHOLD, 0.30f)
        set(value) = prefs.edit { putFloat(KEY_THRESHOLD, value) }

    var swapEyes: Boolean
        get() = prefs.getBoolean(KEY_SWAP, false)
        set(value) = prefs.edit { putBoolean(KEY_SWAP, value) }

    // --- General --------------------------------------------------------------

    var onlyTargetApps: Boolean
        get() = prefs.getBoolean(KEY_ONLY_TARGETS, true)
        set(value) = prefs.edit { putBoolean(KEY_ONLY_TARGETS, value) }

    var vibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit { putBoolean(KEY_VIBRATE, value) }

    var askedNotifications: Boolean
        get() = prefs.getBoolean(KEY_ASKED_NOTIF, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED_NOTIF, value) }

    fun detectorConfig() = DetectorConfig(
        mode = mode,
        holdMs = holdMs,
        closedThreshold = closedThreshold,
    )

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    private companion object {
        const val KEY_SOUND = "trigger_sound"
        const val KEY_PROXIMITY = "trigger_proximity"
        const val KEY_EYES = "trigger_eyes"
        const val KEY_DOUBLE = "double_for_previous"
        const val KEY_SOUND_SENSITIVITY = "sound_sensitivity"
        const val KEY_SOUND_QUALITY = "sound_quality"
        const val KEY_MODE = "mode"
        const val KEY_HOLD = "hold_ms"
        const val KEY_THRESHOLD = "closed_threshold"
        const val KEY_ONLY_TARGETS = "only_target_apps"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_SWAP = "swap_eyes"
        const val KEY_ASKED_NOTIF = "asked_notifications"
    }
}
