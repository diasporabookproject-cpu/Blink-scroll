package app.blinkscroll

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Hand passing over the top of the phone (proximity sensor): one pass = next video,
 * two passes = previous video. No camera, almost no battery.
 */
class ProximityTrigger(
    context: Context,
    private val onAction: (ScrollAction) -> Unit,
    private val onNearChanged: (Boolean) -> Unit,
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val handler = Handler(Looper.getMainLooper())
    private val waves = WaveDetector()
    private val pattern = PulsePattern(doubleWindowMs = 800, minGapMs = 120, cooldownMs = 600)
    private var listening = false

    private val tick = object : Runnable {
        override fun run() {
            pattern.onTick(SystemClock.elapsedRealtime())?.let(onAction)
            if (pattern.hasPending) handler.postDelayed(this, 50)
        }
    }

    var doubleForPrevious: Boolean
        get() = pattern.doubleForPrevious
        set(value) {
            pattern.doubleForPrevious = value
        }

    val isAvailable: Boolean get() = sensor != null

    fun start(): Boolean {
        val s = sensor ?: return false
        if (listening) return true
        listening = sensorManager?.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME, handler) == true
        return listening
    }

    fun stop() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        handler.removeCallbacks(tick)
        listening = false
        waves.reset()
        pattern.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val s = sensor ?: return
        // Binary sensors report 0 or their max range; others report a distance in cm.
        val near = event.values[0] < minOf(s.maximumRange, 5f)
        onNearChanged(near)
        val now = SystemClock.elapsedRealtime()
        if (waves.onSample(near, now)) {
            pattern.onPulse(now)?.let(onAction)
            if (pattern.hasPending) {
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, 50)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
