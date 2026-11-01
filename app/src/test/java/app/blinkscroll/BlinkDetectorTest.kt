package app.blinkscroll

import app.blinkscroll.ScrollAction.NEXT
import app.blinkscroll.ScrollAction.PREVIOUS
import org.junit.Assert.assertEquals
import org.junit.Test

class BlinkDetectorTest {

    // (left eye open probability, right eye open probability)
    private val open = 0.95f to 0.95f
    private val leftClosed = 0.05f to 0.95f
    private val rightClosed = 0.95f to 0.05f
    private val both = 0.05f to 0.05f
    private val squint = 0.35f to 0.35f
    private val noFace: Pair<Float?, Float?> = null to null

    /** Feeds segments of (eyes, duration in ms) at one frame every [step] ms. */
    private fun feed(
        detector: BlinkDetector,
        vararg segments: Pair<Pair<Float?, Float?>, Long>,
        step: Long = 50,
    ): List<ScrollAction> {
        val out = mutableListOf<ScrollAction>()
        var t = 1_000L
        for ((eyes, duration) in segments) {
            var elapsed = 0L
            while (elapsed < duration) {
                detector.onFrame(eyes.first, eyes.second, t)?.let { out += it }
                t += step
                elapsed += step
            }
        }
        return out
    }

    private fun detector(mode: TriggerMode, holdMs: Long = 250) =
        BlinkDetector(DetectorConfig(mode = mode, holdMs = holdMs))

    @Test
    fun naturalBlinkDoesNotTriggerWink() {
        assertEquals(
            emptyList<ScrollAction>(),
            feed(detector(TriggerMode.WINK_ANY), open to 500, both to 150, open to 500),
        )
    }

    @Test
    fun staggeredSlowBlinkDoesNotTriggerWink() {
        assertEquals(
            emptyList<ScrollAction>(),
            feed(
                detector(TriggerMode.WINK_ANY),
                open to 300, rightClosed to 50, both to 200, rightClosed to 50, open to 300,
            ),
        )
    }

    @Test
    fun heldWinkTriggersExactlyOnce() {
        assertEquals(
            listOf(NEXT),
            feed(detector(TriggerMode.WINK_ANY), open to 500, rightClosed to 1500, open to 500),
        )
    }

    @Test
    fun shortWinkIsIgnored() {
        assertEquals(
            emptyList<ScrollAction>(),
            feed(detector(TriggerMode.WINK_ANY), open to 500, rightClosed to 150, open to 500),
        )
    }

    @Test
    fun twoSeparateWinksTriggerTwice() {
        assertEquals(
            listOf(NEXT, NEXT),
            feed(
                detector(TriggerMode.WINK_ANY),
                open to 300, rightClosed to 300, open to 700, leftClosed to 300, open to 300,
            ),
        )
    }

    @Test
    fun cooldownPreventsRapidRepeat() {
        assertEquals(
            listOf(NEXT),
            feed(
                detector(TriggerMode.WINK_ANY),
                open to 300, rightClosed to 300, open to 100, rightClosed to 300, open to 300,
            ),
        )
    }

    @Test
    fun briefBothClosedFlickerDuringWinkIsTolerated() {
        assertEquals(
            listOf(NEXT),
            feed(
                detector(TriggerMode.WINK_ANY),
                open to 300, rightClosed to 150, both to 50, rightClosed to 150, open to 300,
            ),
        )
    }

    @Test
    fun uncertainFramesDoNotBreakAWink() {
        assertEquals(
            listOf(NEXT),
            feed(
                detector(TriggerMode.WINK_ANY),
                open to 300, rightClosed to 100, squint to 100, rightClosed to 150, open to 300,
            ),
        )
    }

    @Test
    fun leftRightModeMapsEachEye() {
        assertEquals(
            listOf(NEXT, PREVIOUS),
            feed(
                detector(TriggerMode.WINK_LEFT_RIGHT),
                open to 300, rightClosed to 300, open to 700, leftClosed to 300, open to 300,
            ),
        )
    }

    @Test
    fun losingTheFaceResetsTheGesture() {
        assertEquals(
            emptyList<ScrollAction>(),
            feed(
                detector(TriggerMode.WINK_ANY),
                rightClosed to 200, noFace to 100, rightClosed to 200, open to 300,
            ),
        )
    }

    @Test
    fun longBlinkTriggers() {
        assertEquals(
            listOf(NEXT),
            feed(detector(TriggerMode.LONG_BLINK), open to 300, both to 800, open to 300),
        )
    }

    @Test
    fun longBlinkModeIgnoresNaturalBlinksAndWinks() {
        assertEquals(
            emptyList<ScrollAction>(),
            feed(
                detector(TriggerMode.LONG_BLINK),
                open to 300, both to 200, open to 500, rightClosed to 1000, open to 300,
            ),
        )
    }

    @Test
    fun doubleBlinkTriggers() {
        assertEquals(
            listOf(NEXT),
            feed(
                detector(TriggerMode.DOUBLE_BLINK),
                open to 300, both to 150, open to 200, both to 150, open to 300,
            ),
        )
    }

    @Test
    fun singleBlinkOrSpacedBlinksDoNotTriggerDoubleBlink() {
        assertEquals(
            emptyList<ScrollAction>(),
            feed(
                detector(TriggerMode.DOUBLE_BLINK),
                open to 300, both to 150, open to 1500, both to 150, open to 1500,
            ),
        )
    }

    @Test
    fun doubleBlinkModeIgnoresLongClosure() {
        assertEquals(
            emptyList<ScrollAction>(),
            feed(
                detector(TriggerMode.DOUBLE_BLINK),
                open to 300, both to 1000, open to 200, both to 150, open to 300,
            ),
        )
    }
}
