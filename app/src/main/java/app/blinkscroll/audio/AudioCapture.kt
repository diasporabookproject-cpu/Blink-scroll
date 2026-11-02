package app.blinkscroll.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads the microphone (16 kHz mono) on a background thread and hands the samples,
 * converted to -1..1, to [onSamples]. Nothing is stored.
 */
class AudioCapture(
    private val onSamples: (samples: FloatArray, count: Int) -> Unit,
    private val onStopped: (() -> Unit)? = null,
) {
    /** One flag per recording, so a slow-to-stop thread can never be revived by a new start(). */
    private var runningFlag: AtomicBoolean? = null
    private var thread: Thread? = null

    val isRunning: Boolean get() = runningFlag?.get() == true

    /** Requires the RECORD_AUDIO permission (checked by callers). */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isRunning) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord(
                // Voice recognition input: no automatic gain, so sounds keep their shape.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioConfig.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 4096) * 2,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Cannot create AudioRecord", e)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val running = AtomicBoolean(true)
        runningFlag = running
        thread = Thread({
            val shorts = ShortArray(CHUNK)
            val floats = FloatArray(CHUNK)
            try {
                record.startRecording()
                while (running.get()) {
                    val n = record.read(shorts, 0, CHUNK)
                    if (n < 0) {
                        Log.w(TAG, "AudioRecord.read error $n")
                        break
                    }
                    for (i in 0 until n) floats[i] = shorts[i] / 32768f
                    if (n > 0) onSamples(floats, n)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture failed", e)
            } finally {
                try {
                    record.stop()
                } catch (e: Exception) {
                    // already stopped
                }
                record.release()
                if (running.getAndSet(false)) onStopped?.invoke()
            }
        }, "BlinkScroll-audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    fun stop() {
        runningFlag?.set(false)
        runningFlag = null
        try {
            thread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
    }

    private companion object {
        const val TAG = "BlinkScroll"
        const val CHUNK = 1024
    }
}
