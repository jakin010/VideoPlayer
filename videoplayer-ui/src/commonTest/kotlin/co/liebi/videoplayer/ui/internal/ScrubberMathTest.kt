package co.liebi.videoplayer.ui.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ScrubberMathTest {

    // 200 px of thumb travel between x = 10 and x = 210.
    private val width = 220f
    private val radius = 10f
    private val range = Duration.ZERO..100.seconds

    @Test
    fun fractionIsClampedToTheRange() {
        assertEquals(0.25f, ScrubberMath.fraction(25.seconds, range))
        assertEquals(0f, ScrubberMath.fraction((-5).seconds, range))
        assertEquals(1f, ScrubberMath.fraction(150.seconds, range))
    }

    @Test
    fun thumbStaysInsideTheTrack() {
        assertEquals(10f, ScrubberMath.thumbCenterX(0f, width, radius))
        assertEquals(210f, ScrubberMath.thumbCenterX(1f, width, radius))
    }

    @Test
    fun barsReachTheEndOfTheTrackAndStayUnderTheThumb() {
        assertEquals(width, ScrubberMath.barEndX(1f, width, radius))
        val fraction = 0.3f
        val thumbCenter = ScrubberMath.thumbCenterX(fraction, width, radius)
        val barEnd = ScrubberMath.barEndX(fraction, width, radius)
        assertTrue(barEnd >= thumbCenter && barEnd <= thumbCenter + radius)
    }

    @Test
    fun absoluteDragFollowsTheFingerAndClamps() {
        assertEquals(50.seconds, ScrubberMath.absoluteTarget(110f, width, radius, range))
        assertEquals(Duration.ZERO, ScrubberMath.absoluteTarget(-40f, width, radius, range))
        assertEquals(100.seconds, ScrubberMath.absoluteTarget(500f, width, radius, range))
    }

    @Test
    fun relativeDragMovesByTheDistanceAndClamps() {
        assertEquals(40.seconds, ScrubberMath.relativeTarget(30.seconds, dx = 20f, width, radius, range))
        assertEquals(20.seconds, ScrubberMath.relativeTarget(30.seconds, dx = -20f, width, radius, range))
        assertEquals(100.seconds, ScrubberMath.relativeTarget(90.seconds, dx = 100f, width, radius, range))
    }
}
