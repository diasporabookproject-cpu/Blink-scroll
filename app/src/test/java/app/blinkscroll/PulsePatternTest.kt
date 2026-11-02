package app.blinkscroll

import app.blinkscroll.ScrollAction.NEXT
import app.blinkscroll.ScrollAction.PREVIOUS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PulsePatternTest {

    /** Feeds pulses at the given times and ticks every 10 ms until [endMs]. */
    private fun run(pattern: PulsePattern, pulses: List<Long>, endMs: Long = 3000): List<ScrollAction> {
        val out = mutableListOf<ScrollAction>()
        var t = 0L
        val queue = ArrayDeque(pulses.sorted())
        while (t <= endMs) {
            while (queue.isNotEmpty() && queue.first() <= t) pattern.onPulse(queue.removeFirst())?.let { out += it }
            pattern.onTick(t)?.let { out += it }
            t += 10
        }
        return out
    }

    @Test
    fun singlePulseIsNextAfterTheWindow() {
        val p = PulsePattern()
        assertNull(p.onPulse(1000))
        assertNull(p.onTick(1300))
        assertEquals(NEXT, p.onTick(1460))
    }

    @Test
    fun twoQuickPulsesArePrevious() {
        assertEquals(listOf(PREVIOUS), run(PulsePattern(), listOf(1000, 1250)))
    }

    @Test
    fun echoWithinMinGapIsIgnored() {
        assertEquals(listOf(NEXT), run(PulsePattern(), listOf(1000, 1030)))
    }

    @Test
    fun spacedPulsesAreTwoNexts() {
        assertEquals(listOf(NEXT, NEXT), run(PulsePattern(), listOf(500, 1600)))
    }

    @Test
    fun withoutDoubleModeEveryPulseIsImmediateNext() {
        val p = PulsePattern(doubleForPrevious = false)
        assertEquals(NEXT, p.onPulse(1000))
        assertNull("cooldown", p.onPulse(1200))
        assertEquals(NEXT, p.onPulse(1600))
    }

    @Test
    fun resetRestoresTimeBase() {
        val p = PulsePattern(doubleForPrevious = false)
        assertEquals(NEXT, p.onPulse(100_000))
        p.reset()
        assertEquals(NEXT, p.onPulse(50))
    }

    @Test
    fun waveIsNearThenFar() {
        val w = WaveDetector()
        assertFalse(w.onSample(near = true, nowMs = 1000))
        assertTrue(w.onSample(near = false, nowMs = 1200))
    }

    @Test
    fun longNearIsNotAWave() {
        val w = WaveDetector()
        w.onSample(near = true, nowMs = 1000)
        assertFalse(w.onSample(near = false, nowMs = 4000))
    }

    @Test
    fun farWithoutNearIsNothing() {
        assertFalse(WaveDetector().onSample(near = false, nowMs = 1000))
    }
}
