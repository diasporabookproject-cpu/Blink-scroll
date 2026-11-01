package app.blinkscroll

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class LiveSnapshot(
    val running: Boolean = false,
    /** False while detection is started but the camera is paused to save battery. */
    val cameraOn: Boolean = false,
    val faceDetected: Boolean = false,
    /** Eye-open probabilities in 0..1, or -1 when unknown. */
    val left: Float = -1f,
    val right: Float = -1f,
    val count: Int = 0,
    val lastAction: String? = null,
    val error: String? = null,
)

/** In-process bridge between the camera service, the accessibility service and the screen. */
object LiveState {
    private val _state = MutableStateFlow(LiveSnapshot())
    val state: StateFlow<LiveSnapshot> = _state

    /** Called when something that decides whether the camera is needed has changed. */
    @Volatile
    var contextChanged: (() -> Unit)? = null

    /** True while the settings screen is on screen (test mode: no real scrolling). */
    @Volatile
    var uiVisible: Boolean = false
        set(value) {
            field = value
            contextChanged?.invoke()
        }

    fun update(transform: (LiveSnapshot) -> LiveSnapshot) = _state.update(transform)
}
