package app.blinkscroll

import android.content.Context
import androidx.core.content.edit

/** User preferences, stored in SharedPreferences (readable from any thread). */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("blink_settings", Context.MODE_PRIVATE)

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

    var onlyTargetApps: Boolean
        get() = prefs.getBoolean(KEY_ONLY_TARGETS, true)
        set(value) = prefs.edit { putBoolean(KEY_ONLY_TARGETS, value) }

    var vibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(value) = prefs.edit { putBoolean(KEY_VIBRATE, value) }

    var swapEyes: Boolean
        get() = prefs.getBoolean(KEY_SWAP, false)
        set(value) = prefs.edit { putBoolean(KEY_SWAP, value) }

    var askedNotifications: Boolean
        get() = prefs.getBoolean(KEY_ASKED_NOTIF, false)
        set(value) = prefs.edit { putBoolean(KEY_ASKED_NOTIF, value) }

    fun detectorConfig() = DetectorConfig(
        mode = mode,
        holdMs = holdMs,
        closedThreshold = closedThreshold,
    )

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_HOLD = "hold_ms"
        const val KEY_THRESHOLD = "closed_threshold"
        const val KEY_ONLY_TARGETS = "only_target_apps"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_SWAP = "swap_eyes"
        const val KEY_ASKED_NOTIF = "asked_notifications"
    }
}
