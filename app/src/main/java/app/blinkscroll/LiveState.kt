package app.blinkscroll

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class LiveSnapshot(
    val running: Boolean = false,
    /** False while detection is started but paused to save battery. */
    val listening: Boolean = false,
    val soundActive: Boolean = false,
    val proximityActive: Boolean = false,
    val eyesActive: Boolean = false,

    /** Microphone level, 0..1. */
    val soundLevel: Float = 0f,
    /** Last detected sound: resemblance with the learned sound and threshold, 0..1. */
    val lastSoundScore: Float = -1f,
    val lastSoundThreshold: Float = -1f,
    val lastSoundAccepted: Boolean = false,
    val lastSoundAt: Long = 0L,

    val proximityNear: Boolean? = null,

    val faceDetected: Boolean = false,
    /** Eye-open probabilities in 0..1, or -1 when unknown. */
    val left: Float = -1f,
    val right: Float = -1f,

    val count: Int = 0,
    val lastAction: String? = null,
    val error: String? = null,
)

/** In-process bridge between the detection service, the accessibility service and the screens. */
object LiveState {
    private val _state = MutableStateFlow(LiveSnapshot())
    val state: StateFlow<LiveSnapshot> = _state

    /** Called when something that decides whether detection is needed has changed. */
    @Volatile
    var contextChanged: (() -> Unit)? = null

    /** True while the settings screen is on screen (test mode: no real scrolling). */
    @Volatile
    var uiVisible: Boolean = false
        set(value) {
            field = value
            contextChanged?.invoke()
        }

    /** Set by the calibration screen when it had to stop detection; the main screen restarts it. */
    @Volatile
    var restartAfterCalibration: Boolean = false

    fun update(transform: (LiveSnapshot) -> LiveSnapshot) = _state.update(transform)
}
