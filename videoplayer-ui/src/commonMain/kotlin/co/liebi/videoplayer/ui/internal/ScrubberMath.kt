package co.liebi.videoplayer.ui.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Geometry of the scrubber (§11). The thumb travels between `thumbRadius` and `width - thumbRadius`
 * so it never overhangs the track; that span is the "track width" the spec's formulas use.
 */
internal object ScrubberMath {

    /** Where [position] sits in [range], from 0 to 1. */
    fun fraction(position: Duration, range: ClosedRange<Duration>): Float {
        val length = range.endInclusive - range.start
        if (length <= Duration.ZERO) return 0f
        return ((position - range.start) / length).toFloat().coerceIn(0f, 1f)
    }

    fun thumbCenterX(fraction: Float, width: Float, thumbRadius: Float): Float =
        thumbRadius + fraction * travel(width, thumbRadius)

    /**
     * End of a played or buffered bar. It reaches the end of the track at 1, and for the current
     * position it always ends under the thumb (within one radius of its center).
     */
    fun barEndX(fraction: Float, width: Float, thumbRadius: Float): Float =
        thumbRadius + fraction * (width - thumbRadius)

    /** A drag that started on the handle follows the finger: target = start + (x / track width) × length. */
    fun absoluteTarget(x: Float, width: Float, thumbRadius: Float, range: ClosedRange<Duration>): Duration {
        val fraction = ((x - thumbRadius).toDouble() / travel(width, thumbRadius)).coerceIn(0.0, 1.0)
        return (range.start + (range.endInclusive - range.start) * fraction).toWholeMilliseconds()
    }

    /**
     * A drag that started elsewhere moves the position by the drag distance, so the touch point never
     * becomes the position: target = position at drag start + (Δx / track width) × length.
     */
    fun relativeTarget(
        startPosition: Duration,
        dx: Float,
        width: Float,
        thumbRadius: Float,
        range: ClosedRange<Duration>,
    ): Duration {
        val delta = (range.endInclusive - range.start) * (dx.toDouble() / travel(width, thumbRadius))
        return (startPosition + delta).coerceIn(range).toWholeMilliseconds()
    }

    private fun travel(width: Float, thumbRadius: Float): Float = (width - 2 * thumbRadius).coerceAtLeast(1f)

    /** Both engines seek with millisecond precision; finer values are pixel noise. */
    private fun Duration.toWholeMilliseconds(): Duration = inWholeMilliseconds.milliseconds
}
