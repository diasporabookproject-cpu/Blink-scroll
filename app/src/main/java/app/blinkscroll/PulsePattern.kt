package app.blinkscroll

/**
 * Turns discrete "pulses" (a recognised sound, a hand wave) into scroll actions:
 * one pulse → next video; two pulses close together → previous video (if enabled).
 *
 * Call [onPulse] for each pulse and [onTick] regularly so a lone pulse is
 * confirmed as "next" once the double-pulse window has passed.
 */
class PulsePattern(
    @Volatile var doubleForPrevious: Boolean = true,
    private val doubleWindowMs: Long = 450,
    /** Pulses closer than this are the same event (echo, bounce). */
    private val minGapMs: Long = 80,
    private val cooldownMs: Long = 500,
) {
    private var pendingAt: Long? = null
    private var lastActionAt = Long.MIN_VALUE / 4

    /** True while a single pulse is waiting to see if a second one follows. */
    val hasPending: Boolean get() = pendingAt != null

    /** Time (ms) at which [onTick] will turn the pending pulse into "next". */
    val pendingDeadline: Long? get() = pendingAt?.plus(doubleWindowMs + 1)

    /** Forgets everything (call it when the time base restarts). */
    fun reset() {
        pendingAt = null
        lastActionAt = Long.MIN_VALUE / 4
    }

    fun onPulse(nowMs: Long): ScrollAction? {
        if (nowMs - lastActionAt < cooldownMs) return null
        if (!doubleForPrevious) return fire(ScrollAction.NEXT, nowMs)
        val pending = pendingAt
        if (pending != null) {
            val gap = nowMs - pending
            if (gap < minGapMs) return null
            if (gap <= doubleWindowMs) {
                pendingAt = null
                return fire(ScrollAction.PREVIOUS, nowMs)
            }
        }
        pendingAt = nowMs
        return null
    }

    fun onTick(nowMs: Long): ScrollAction? {
        val pending = pendingAt ?: return null
        if (nowMs - pending <= doubleWindowMs) return null
        pendingAt = null
        return fire(ScrollAction.NEXT, nowMs)
    }

    private fun fire(action: ScrollAction, nowMs: Long): ScrollAction {
        lastActionAt = nowMs
        return action
    }
}

/**
 * Detects a hand passing over the proximity sensor: "near" then "far" again
 * within a short time. Staying near (phone in a pocket, face down) is ignored.
 */
class WaveDetector(
    private val minNearMs: Long = 30,
    private val maxNearMs: Long = 1500,
) {
    private var nearSince: Long? = null

    fun reset() {
        nearSince = null
    }

    /** @return true when a wave has just completed. */
    fun onSample(near: Boolean, nowMs: Long): Boolean {
        if (near) {
            if (nearSince == null) nearSince = nowMs
            return false
        }
        val since = nearSince ?: return false
        nearSince = null
        return nowMs - since in minNearMs..maxNearMs
    }
}
