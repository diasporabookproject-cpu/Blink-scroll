package app.blinkscroll.audio

import app.blinkscroll.ScrollAction
import app.blinkscroll.ScrollAction.NEXT
import app.blinkscroll.ScrollAction.PREVIOUS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * End-to-end tests of the sound pipeline on synthetic audio:
 * a "tongue click" (short resonant pop) versus "keyboard" strokes (broadband clack + thump).
 */
class SoundEngineTest {

    private val sr = AudioConfig.SAMPLE_RATE

    /** Builds a mono signal of [seconds] with low background noise, then lets callers add sounds. */
    private class Track(seconds: Double, seed: Int) {
        val rnd = Random(seed)
        val data = FloatArray((seconds * AudioConfig.SAMPLE_RATE).toInt()) { (rnd.nextFloat() - 0.5f) * 0.004f }

        fun add(atSeconds: Double, sound: FloatArray) {
            val start = (atSeconds * AudioConfig.SAMPLE_RATE).toInt()
            for (i in sound.indices) if (start + i < data.size) data[start + i] += sound[i]
        }
    }

    /** Short resonant pop around [freq] Hz, like a tongue click. */
    private fun click(freq: Double = 1800.0, amp: Double = 0.3, rnd: Random = Random(1)): FloatArray {
        val f = freq * (1 + (rnd.nextDouble() - 0.5) * 0.04)
        val n = (0.04 * sr).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            (amp * exp(-t / 0.006) * (sin(2 * PI * f * t) + 0.35 * sin(2 * PI * 2.1 * f * t))).toFloat()
        }
    }

    /** Keyboard stroke: high-passed noise burst plus a low thump. */
    private fun keystroke(rnd: Random, amp: Double = 0.25): FloatArray {
        val n = (0.04 * sr).toInt()
        var prev = 0.0
        val a = amp * (0.7 + 0.6 * rnd.nextDouble())
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            val noise = rnd.nextDouble() * 2 - 1
            val highPassed = noise - prev
            prev = noise
            (a * (0.6 * exp(-t / 0.004) * highPassed + 0.5 * exp(-t / 0.010) * sin(2 * PI * 160 * t))).toFloat()
        }
    }

    /** Collects fingerprints of every onset in a track. */
    private fun fingerprints(track: FloatArray): List<FloatArray> {
        val out = mutableListOf<FloatArray>()
        val stream = OnsetFeatureStream(onFeature = { _, f -> out += f })
        stream.process(track, track.size)
        return out
    }

    private fun calibratedProfile(): SoundProfile {
        val rnd = Random(7)
        val positives = Track(6.0, 11).apply {
            for (k in 0 until 5) add(0.5 + k * 1.0, click(amp = 0.15 + 0.06 * k, rnd = rnd))
        }
        val negatives = Track(10.0, 12).apply {
            var t = 0.3
            while (t < 9.7) {
                add(t, keystroke(rnd))
                t += 0.12 + rnd.nextDouble() * 0.2
            }
        }
        val pos = fingerprints(positives.data)
        val neg = fingerprints(negatives.data)
        assertEquals("all 5 clicks should be detected", 5, pos.size)
        assertTrue("keyboard strokes should be detected (${neg.size})", neg.size >= 30)
        return SoundCalibrator.build(pos, neg)
    }

    private fun run(profile: SoundProfile, track: FloatArray, doubleForPrevious: Boolean = true): Pair<List<ScrollAction>, List<SoundMatch>> {
        val actions = mutableListOf<ScrollAction>()
        val matches = mutableListOf<SoundMatch>()
        val engine = SoundTriggerEngine(profile, doubleForPrevious = doubleForPrevious)
        val listener = object : SoundTriggerEngine.Listener {
            override fun onSound(match: SoundMatch) { matches += match }
            override fun onAction(action: ScrollAction) { actions += action }
        }
        // Feed in chunks like AudioRecord does.
        var i = 0
        while (i < track.size) {
            val n = minOf(1024, track.size - i)
            engine.process(track.copyOfRange(i, i + n), n, listener)
            i += n
        }
        return actions to matches
    }

    @Test
    fun calibrationSeparatesClickFromKeyboard() {
        val profile = calibratedProfile()
        println("threshold=${profile.threshold} separation=${profile.separation} quality=${profile.quality}")
        assertEquals(ProfileQuality.GOOD, profile.quality)
    }

    @Test
    fun singleClickScrollsToNext() {
        val profile = calibratedProfile()
        val track = Track(3.0, 21).apply { add(1.0, click(amp = 0.25, rnd = Random(3))) }
        val (actions, matches) = run(profile, track.data)
        println("single: " + matches.joinToString { "%.3f/%.3f".format(it.score, it.threshold) })
        assertEquals(listOf(NEXT), actions)
    }

    @Test
    fun doubleClickScrollsToPrevious() {
        val profile = calibratedProfile()
        val rnd = Random(4)
        val track = Track(3.0, 22).apply {
            add(1.0, click(amp = 0.25, rnd = rnd))
            add(1.25, click(amp = 0.22, rnd = rnd))
        }
        assertEquals(listOf(PREVIOUS), run(profile, track.data).first)
    }

    @Test
    fun doubleClickIsTwoNextsWhenPreviousDisabled() {
        val profile = calibratedProfile()
        val rnd = Random(5)
        val track = Track(3.0, 23).apply {
            add(1.0, click(amp = 0.25, rnd = rnd))
            add(1.7, click(amp = 0.25, rnd = rnd))
        }
        assertEquals(listOf(NEXT, NEXT), run(profile, track.data, doubleForPrevious = false).first)
    }

    @Test
    fun typingIsIgnored() {
        val profile = calibratedProfile()
        val rnd = Random(99)
        val track = Track(6.0, 24).apply {
            var t = 0.2
            while (t < 5.8) {
                add(t, keystroke(rnd))
                t += 0.1 + rnd.nextDouble() * 0.25
            }
        }
        val (actions, matches) = run(profile, track.data)
        println("typing: max score " + matches.maxOfOrNull { it.score } + " over ${matches.size} strokes")
        assertTrue("typing should give many onsets", matches.size >= 15)
        assertEquals(emptyList<ScrollAction>(), actions)
    }

    @Test
    fun clickIsRecognisedWhileTyping() {
        val profile = calibratedProfile()
        val rnd = Random(55)
        val track = Track(4.0, 25).apply {
            for (t in listOf(0.3, 0.55, 0.8, 1.05, 1.9, 2.15, 2.4, 2.7, 3.0)) add(t, keystroke(rnd))
            add(1.45, click(amp = 0.2, rnd = rnd))
        }
        assertEquals(listOf(NEXT), run(profile, track.data).first)
    }

    @Test
    fun differentSoundIsRejected() {
        val profile = calibratedProfile()
        // A much lower pop (e.g. a finger tap on the desk) should not count.
        val track = Track(3.0, 26).apply { add(1.0, click(freq = 450.0, amp = 0.3)) }
        val (actions, matches) = run(profile, track.data)
        println("other sound: " + matches.joinToString { "%.3f".format(it.score) })
        assertEquals(emptyList<ScrollAction>(), actions)
    }

    @Test
    fun soundMadeDuringTypingStepDoesNotBlockIt() {
        val rnd = Random(8)
        val positives = Track(6.0, 31).apply {
            for (k in 0 until 5) add(0.5 + k * 1.0, click(amp = 0.2, rnd = rnd))
        }
        val negatives = Track(10.0, 32).apply {
            var t = 0.3
            while (t < 9.7) {
                if (t !in 4.8..5.3) add(t, keystroke(rnd))
                t += 0.12 + rnd.nextDouble() * 0.2
            }
            add(5.05, click(amp = 0.25, rnd = rnd)) // the user clicked once while typing
        }
        val profile = SoundCalibrator.build(fingerprints(positives.data), fingerprints(negatives.data))
        val track = Track(3.0, 33).apply { add(1.0, click(amp = 0.25, rnd = Random(9))) }
        assertEquals(listOf(NEXT), run(profile, track.data).first)
    }

    @Test
    fun silentStartDoesNotCreateFalseSounds() {
        val track = FloatArray(sr * 2)
        // 100 ms of digital silence (first AudioRecord buffers), then normal room noise.
        val rnd = Random(10)
        for (i in sr / 10 until track.size) track[i] = (rnd.nextFloat() - 0.5f) * 0.01f
        assertEquals(0, fingerprints(track).size)
    }

    @Test
    fun profileSurvivesSaveAndLoad() {
        val profile = calibratedProfile()
        val file = File.createTempFile("profile", ".txt")
        try {
            profile.save(file)
            val loaded = SoundProfile.load(file)
            assertNotNull(loaded)
            assertEquals(profile.positives.size, loaded!!.positives.size)
            assertEquals(profile.negatives.size, loaded.negatives.size)
            assertEquals(profile.threshold, loaded.threshold, 1e-4f)
            assertEquals(dot(profile.positives[0], profile.positives[0]), dot(loaded.positives[0], loaded.positives[0]), 1e-3f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun fftFindsAPureTone() {
        val n = 512
        val fft = Fft(n)
        val bin = 37
        val re = DoubleArray(n) { sin(2 * PI * bin * it / n) }
        val im = DoubleArray(n)
        fft.transform(re, im)
        val mags = (0..n / 2).map { re[it] * re[it] + im[it] * im[it] }
        assertEquals(bin, mags.indices.maxBy { mags[it] })
    }
}
