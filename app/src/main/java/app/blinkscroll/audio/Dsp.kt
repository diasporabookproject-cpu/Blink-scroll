package app.blinkscroll.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Fixed audio parameters shared by capture, calibration and detection. */
object AudioConfig {
    const val SAMPLE_RATE = 16_000

    /** Onset detection resolution: 128 samples = 8 ms. */
    const val HOP = 128

    /** Analysis frame: 512 samples = 32 ms. */
    const val FFT_SIZE = 512

    /** A sound fingerprint is made of 3 frames spaced by half a frame = 64 ms in total. */
    const val FEATURE_FRAMES = 3
    const val FRAME_STEP = FFT_SIZE / 2

    /** Samples kept before the detected onset so the attack is fully inside the first frame. */
    const val PRE_ONSET = 128

    /** Samples needed to compute one fingerprint. */
    const val SEGMENT = FRAME_STEP * (FEATURE_FRAMES - 1) + FFT_SIZE

    const val MEL_BANDS = 32
    const val FEATURE_SIZE = MEL_BANDS * FEATURE_FRAMES

    fun samplesToMs(samples: Long): Long = samples * 1000L / SAMPLE_RATE
}

/** In-place iterative radix-2 complex FFT. */
class Fft(private val n: Int) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray
    private val reversed: IntArray

    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
        cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
        sinTable = DoubleArray(n / 2) { -sin(2.0 * PI * it / n) }
        val bits = Integer.numberOfTrailingZeros(n)
        reversed = IntArray(n) { Integer.reverse(it) ushr (32 - bits) }
    }

    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val l = j + half
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val tr = re[l] * wr - im[l] * wi
                    val ti = re[l] * wi + im[l] * wr
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}

/** Triangular mel filters mapping a power spectrum to [bands] energies. */
class MelFilterbank(
    nFft: Int,
    sampleRate: Int,
    private val bands: Int,
    fMin: Double = 100.0,
    fMax: Double = 7600.0,
) {
    private val nBins = nFft / 2 + 1
    private val firstBin = IntArray(bands)
    private val weights: Array<DoubleArray>

    init {
        fun hzToMel(f: Double) = 2595.0 * log10(1.0 + f / 700.0)
        fun melToHz(m: Double) = 700.0 * (10.0.pow(m / 2595.0) - 1.0)
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val points = DoubleArray(bands + 2) { melToHz(melMin + (melMax - melMin) * it / (bands + 1)) }
        val binHz = sampleRate.toDouble() / nFft
        weights = Array(bands) { b ->
            val lo = points[b]
            val mid = points[b + 1]
            val hi = points[b + 2]
            val full = DoubleArray(nBins) { k ->
                val f = k * binHz
                when {
                    f <= lo || f >= hi -> 0.0
                    f <= mid -> (f - lo) / (mid - lo)
                    else -> (hi - f) / (hi - mid)
                }
            }
            val first = full.indexOfFirst { it > 0.0 }.let { if (it < 0) 0 else it }
            val last = full.indexOfLast { it > 0.0 }.let { if (it < first) first else it }
            firstBin[b] = first
            full.copyOfRange(first, last + 1)
        }
    }

    fun apply(power: DoubleArray, out: DoubleArray, offset: Int) {
        for (b in 0 until bands) {
            val w = weights[b]
            val first = firstBin[b]
            var sum = 0.0
            for (i in w.indices) sum += w[i] * power[first + i]
            out[offset + b] = sum
        }
    }
}

/**
 * Turns 64 ms of audio starting just before an onset into a normalised spectral
 * fingerprint (log-mel energies of 3 frames, level-independent, unit length).
 * Two fingerprints are compared with [dot]: 1 = identical, lower = more different.
 */
class FeatureExtractor {
    private val fft = Fft(AudioConfig.FFT_SIZE)
    private val mel = MelFilterbank(AudioConfig.FFT_SIZE, AudioConfig.SAMPLE_RATE, AudioConfig.MEL_BANDS)
    private val window = DoubleArray(AudioConfig.FFT_SIZE) {
        0.5 - 0.5 * cos(2.0 * PI * it / (AudioConfig.FFT_SIZE - 1))
    }
    private val re = DoubleArray(AudioConfig.FFT_SIZE)
    private val im = DoubleArray(AudioConfig.FFT_SIZE)
    private val power = DoubleArray(AudioConfig.FFT_SIZE / 2 + 1)
    private val raw = DoubleArray(AudioConfig.FEATURE_SIZE)

    /** @param segment at least [AudioConfig.SEGMENT] samples in -1..1. */
    fun extract(segment: FloatArray): FloatArray {
        require(segment.size >= AudioConfig.SEGMENT)
        for (f in 0 until AudioConfig.FEATURE_FRAMES) {
            val start = f * AudioConfig.FRAME_STEP
            for (i in 0 until AudioConfig.FFT_SIZE) {
                re[i] = segment[start + i] * window[i]
                im[i] = 0.0
            }
            fft.transform(re, im)
            for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
            mel.apply(power, raw, f * AudioConfig.MEL_BANDS)
        }
        var mean = 0.0
        for (i in raw.indices) {
            raw[i] = ln(raw[i] + 1e-10)
            mean += raw[i]
        }
        mean /= raw.size
        var norm = 0.0
        for (i in raw.indices) {
            raw[i] -= mean
            norm += raw[i] * raw[i]
        }
        norm = sqrt(norm).coerceAtLeast(1e-9)
        return FloatArray(raw.size) { (raw[it] / norm).toFloat() }
    }
}

fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in 0 until min(a.size, b.size)) s += a[i] * b[i]
    return s
}

/**
 * Streaming detector of sharp sound attacks (clicks, snaps, tongue clicks, whistle starts).
 * Feed it one hop of [AudioConfig.HOP] samples at a time.
 */
class OnsetDetector(
    /** How far above the background level (dB) an attack must be. */
    private val riseDb: Double = 10.0,
    /** How much louder than the two previous hops (dB): keeps only sudden sounds. */
    private val jumpDb: Double = 6.0,
    /** Absolute floor (dBFS) below which nothing counts. */
    private val minLevelDb: Double = -62.0,
    /** Hops ignored after an onset (~96 ms) so one sound gives one onset. */
    private val refractoryHops: Int = 12,
) {
    private var background = Double.NaN
    private var prev1 = -120.0
    private var prev2 = -120.0
    private var cooldown = 0

    /** Hops of real signal seen since reset; no onsets during the warm-up. */
    private var warmHops = 0

    /** Level of the last hop in dBFS. */
    var levelDb = -120.0
        private set

    fun reset() {
        background = Double.NaN
        prev1 = -120.0
        prev2 = -120.0
        cooldown = 0
        warmHops = 0
    }

    fun process(hop: FloatArray, offset: Int = 0, length: Int = AudioConfig.HOP): Boolean {
        var sum = 0.0
        for (i in offset until offset + length) sum += hop[i] * hop[i]
        val db = 10.0 * log10(sum / length + 1e-12)
        levelDb = db
        // Digital silence (the first buffers of a recording, a muted mic): no information.
        if (db < SILENCE_DB) return false
        if (background.isNaN()) background = db

        val warmingUp = warmHops < WARM_UP_HOPS
        if (warmingUp) warmHops++
        val onset = !warmingUp &&
            cooldown == 0 &&
            db > minLevelDb &&
            db - background >= riseDb &&
            db - minOf(prev1, prev2) >= jumpDb
        if (cooldown > 0) cooldown--
        if (onset) cooldown = refractoryHops

        // Background follows quiet passages quickly and loud bursts slowly (fast both ways while warming up).
        background += when {
            warmingUp -> 0.3 * (db - background)
            db < background -> 0.2 * (db - background)
            else -> 0.01 * (db - background)
        }
        prev2 = prev1
        prev1 = db
        return onset
    }

    private companion object {
        const val SILENCE_DB = -100.0
        const val WARM_UP_HOPS = 25
    }
}

/**
 * Keeps a short history of the signal, detects onsets and computes the fingerprint
 * of each one as soon as enough samples have arrived after it.
 */
class OnsetFeatureStream(
    private val onFeature: (onsetPosition: Long, feature: FloatArray) -> Unit,
    private val onHop: ((position: Long) -> Unit)? = null,
) {
    private val detector = OnsetDetector()
    private val extractor = FeatureExtractor()
    private val ring = FloatArray(RING_SIZE)
    private val hop = FloatArray(AudioConfig.HOP)
    private val segment = FloatArray(AudioConfig.SEGMENT)
    private val pendingOnsets = ArrayDeque<Long>()
    private var hopFill = 0

    /** Total number of samples received. */
    var position = 0L
        private set

    val levelDb: Double get() = detector.levelDb

    fun reset() {
        detector.reset()
        pendingOnsets.clear()
        hopFill = 0
        position = 0L
        ring.fill(0f)
    }

    fun process(samples: FloatArray, count: Int = samples.size) {
        for (i in 0 until count) {
            val x = samples[i]
            ring[(position and RING_MASK).toInt()] = x
            position++
            hop[hopFill++] = x
            if (hopFill < AudioConfig.HOP) continue
            hopFill = 0

            if (detector.process(hop)) pendingOnsets.addLast(position - AudioConfig.HOP)
            while (pendingOnsets.isNotEmpty() &&
                position >= pendingOnsets.first() - AudioConfig.PRE_ONSET + AudioConfig.SEGMENT
            ) {
                val onset = pendingOnsets.removeFirst()
                val start = onset - AudioConfig.PRE_ONSET
                for (j in 0 until AudioConfig.SEGMENT) {
                    val p = start + j
                    segment[j] = if (p < 0) 0f else ring[(p and RING_MASK).toInt()]
                }
                onFeature(onset, extractor.extract(segment))
            }
            onHop?.invoke(position)
        }
    }

    private companion object {
        const val RING_SIZE = 8192
        const val RING_MASK = RING_SIZE - 1L
    }
}
