package app.blinkscroll

/** What the user does with their eyes to trigger a scroll. */
enum class TriggerMode {
    /** Close either eye (the other stays open) for [DetectorConfig.holdMs] → next video. */
    WINK_ANY,

    /** Right-eye wink → next video, left-eye wink → previous video. */
    WINK_LEFT_RIGHT,

    /** Two quick blinks in a row → next video. */
    DOUBLE_BLINK,

    /** Both eyes closed for a while → next video. */
    LONG_BLINK,
}

enum class ScrollAction { NEXT, PREVIOUS }

data class DetectorConfig(
    val mode: TriggerMode = TriggerMode.WINK_ANY,
    /** How long a wink (or a long blink) must be held before it counts. */
    val holdMs: Long = 250,
    /** An eye whose "open" probability is below this value is considered closed. */
    val closedThreshold: Float = 0.30f,
    /** Minimum delay between two scrolls. */
    val cooldownMs: Long = 600,
) {
    /** An eye whose "open" probability is above this value is considered open. */
    val openThreshold: Float get() = (closedThreshold + 0.15f).coerceAtMost(0.95f)

    /** Natural blinks last up to ~400 ms, so a "long blink" must be longer than that. */
    val effectiveHoldMs: Long
        get() = if (mode == TriggerMode.LONG_BLINK) maxOf(holdMs, LONG_BLINK_MIN_MS) else holdMs

    companion object {
        const val LONG_BLINK_MIN_MS = 500L
        const val BLINK_MAX_MS = 450L
        const val DOUBLE_BLINK_WINDOW_MS = 900L
        const val WINK_FLICKER_TOLERANCE_MS = 100L
    }
}

/**
 * Turns a stream of per-frame eye-open probabilities into scroll actions.
 *
 * Pure logic with no Android dependency, so it can be unit-tested.
 * Feed it one call per camera frame; it returns an action when a gesture completes.
 */
class BlinkDetector(var config: DetectorConfig = DetectorConfig()) {

    private enum class EyeState { OPEN, LEFT_CLOSED, RIGHT_CLOSED, BOTH_CLOSED, UNCERTAIN }

    private var state: EyeState? = null
    private var stateSince = 0L

    /** After a scroll, both eyes must reopen before the next one can fire. */
    private var armed = true
    private var lastFireAt = NEVER

    private var winkSide: EyeState? = null
    private var winkSince = 0L

    // A "closure episode" is any period where the eyes are not both open.
    private var episodeStart = NEVER
    private var episodeHadBoth = false
    private var lastBlinkAt = NEVER

    fun reset() {
        state = null
        armed = true
        winkSide = null
        episodeStart = NEVER
        episodeHadBoth = false
        lastBlinkAt = NEVER
    }

    /**
     * @param leftOpen probability (0..1) that the user's left eye is open, or null if no face.
     * @param rightOpen same for the right eye.
     * @param nowMs monotonic timestamp of the frame.
     */
    fun onFrame(leftOpen: Float?, rightOpen: Float?, nowMs: Long): ScrollAction? {
        if (leftOpen == null || rightOpen == null) {
            reset()
            return null
        }
        val current = classify(leftOpen, rightOpen)
        // Half-closed readings are ambiguous: ignore them and keep tracking the previous state.
        if (current == EyeState.UNCERTAIN) return null

        val previous = state
        if (current != previous) {
            state = current
            stateSince = nowMs
        }

        return when (current) {
            EyeState.OPEN -> onOpen(previous, nowMs)
            EyeState.LEFT_CLOSED, EyeState.RIGHT_CLOSED -> onWink(current, previous, nowMs)
            EyeState.BOTH_CLOSED -> onBothClosed(previous, nowMs)
            EyeState.UNCERTAIN -> null
        }
    }

    private fun classify(left: Float, right: Float): EyeState {
        val closed = config.closedThreshold
        val open = config.openThreshold
        val leftClosed = left < closed
        val rightClosed = right < closed
        val leftOpen = left > open
        val rightOpen = right > open
        return when {
            leftClosed && rightClosed -> EyeState.BOTH_CLOSED
            leftClosed && rightOpen -> EyeState.LEFT_CLOSED
            rightClosed && leftOpen -> EyeState.RIGHT_CLOSED
            leftOpen && rightOpen -> EyeState.OPEN
            else -> EyeState.UNCERTAIN
        }
    }

    private fun startEpisodeIfNeeded(previous: EyeState?, nowMs: Long) {
        if (previous == null || previous == EyeState.OPEN) {
            episodeStart = nowMs
            episodeHadBoth = false
        }
    }

    private fun onOpen(previous: EyeState?, nowMs: Long): ScrollAction? {
        armed = true
        winkSide = null
        if (previous == null || previous == EyeState.OPEN) return null

        // The eyes just reopened: was that a quick blink?
        val wasBlink = episodeHadBoth && nowMs - episodeStart <= DetectorConfig.BLINK_MAX_MS
        episodeHadBoth = false
        if (config.mode != TriggerMode.DOUBLE_BLINK || !wasBlink) return null

        return if (nowMs - lastBlinkAt <= DetectorConfig.DOUBLE_BLINK_WINDOW_MS) {
            lastBlinkAt = NEVER
            fire(ScrollAction.NEXT, nowMs)
        } else {
            lastBlinkAt = nowMs
            null
        }
    }

    private fun onWink(side: EyeState, previous: EyeState?, nowMs: Long): ScrollAction? {
        startEpisodeIfNeeded(previous, nowMs)
        if (winkSide != side) {
            winkSide = side
            winkSince = nowMs
        }
        if (!armed || nowMs - winkSince < config.holdMs) return null
        return when (config.mode) {
            TriggerMode.WINK_ANY -> fire(ScrollAction.NEXT, nowMs)
            TriggerMode.WINK_LEFT_RIGHT -> fire(
                if (side == EyeState.RIGHT_CLOSED) ScrollAction.NEXT else ScrollAction.PREVIOUS,
                nowMs,
            )
            else -> null
        }
    }

    private fun onBothClosed(previous: EyeState?, nowMs: Long): ScrollAction? {
        startEpisodeIfNeeded(previous, nowMs)
        episodeHadBoth = true
        // The open eye often squints during a wink: tolerate a brief "both closed" reading,
        // but a longer one is a real blink and cancels the wink.
        if (winkSide != null && nowMs - stateSince > DetectorConfig.WINK_FLICKER_TOLERANCE_MS) {
            winkSide = null
        }
        if (config.mode == TriggerMode.LONG_BLINK && armed &&
            nowMs - stateSince >= config.effectiveHoldMs
        ) {
            return fire(ScrollAction.NEXT, nowMs)
        }
        return null
    }

    private fun fire(action: ScrollAction, nowMs: Long): ScrollAction? {
        if (nowMs - lastFireAt < config.cooldownMs) return null
        lastFireAt = nowMs
        armed = false
        return action
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
    }
}
