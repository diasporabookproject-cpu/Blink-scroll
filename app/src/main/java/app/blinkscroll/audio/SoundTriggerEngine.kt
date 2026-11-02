package app.blinkscroll.audio

import app.blinkscroll.PulsePattern
import app.blinkscroll.ScrollAction

/**
 * Streaming recogniser: listens to raw audio, recognises the learned sound and
 * turns it into scroll actions (1 sound = next, 2 sounds = previous).
 * Pure Kotlin, used from the audio thread.
 */
class SoundTriggerEngine(
    @Volatile var profile: SoundProfile,
    @Volatile var sensitivity: Float = 0f,
    doubleForPrevious: Boolean = true,
) {
    interface Listener {
        /** Every detected sound, recognised or not (for the live test display). */
        fun onSound(match: SoundMatch)
        fun onAction(action: ScrollAction)
    }

    private val pattern = PulsePattern(doubleForPrevious = doubleForPrevious)
    private var listener: Listener? = null

    var doubleForPrevious: Boolean
        get() = pattern.doubleForPrevious
        set(value) {
            pattern.doubleForPrevious = value
        }

    private val stream = OnsetFeatureStream(
        onFeature = { onset, feature ->
            val match = profile.match(feature, sensitivity)
            listener?.onSound(match)
            if (match.accepted) {
                pattern.onPulse(AudioConfig.samplesToMs(onset))?.let { listener?.onAction(it) }
            }
        },
        onHop = { position ->
            pattern.onTick(AudioConfig.samplesToMs(position))?.let { listener?.onAction(it) }
        },
    )

    val levelDb: Double get() = stream.levelDb

    fun reset() {
        stream.reset()
        pattern.reset()
    }

    fun process(samples: FloatArray, count: Int, listener: Listener) {
        this.listener = listener
        stream.process(samples, count)
    }
}
