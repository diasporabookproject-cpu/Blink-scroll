package app.blinkscroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Knows which app is in the foreground and performs the swipe gesture.
 * It never reads the content of the screen.
 */
class ScrollAccessibilityService : AccessibilityService() {

    @Volatile
    private var foregroundPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshForeground(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshForeground(event.packageName?.toString())
        }
    }

    private fun refreshForeground(fallback: String?) {
        val active = try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: RuntimeException) {
            null
        }
        val wasTarget = isTargetAppInForeground()
        foregroundPackage = active ?: fallback ?: foregroundPackage
        if (isTargetAppInForeground() != wasTarget) LiveState.contextChanged?.invoke()
    }

    fun isTargetAppInForeground(): Boolean = foregroundPackage in TARGET_PACKAGES

    /** Swipes up (next video) or down (previous video) in the middle of the screen. */
    fun scroll(action: ScrollAction): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val top = metrics.heightPixels * 0.30f
        val bottom = metrics.heightPixels * 0.70f
        val path = Path().apply {
            if (action == ScrollAction.NEXT) {
                moveTo(x, bottom); lineTo(x, top)
            } else {
                moveTo(x, top); lineTo(x, bottom)
            }
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val SWIPE_DURATION_MS = 200L

        val TARGET_PACKAGES = setOf(
            "com.zhiliaoapp.musically",     // TikTok
            "com.zhiliaoapp.musically.go",  // TikTok Lite
            "com.ss.android.ugc.trill",     // TikTok (some regions)
            "com.ss.android.ugc.aweme",     // Douyin
            "com.instagram.android",        // Instagram
            "com.instagram.lite",           // Instagram Lite
        )

        @Volatile
        var instance: ScrollAccessibilityService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, ScrollAccessibilityService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
