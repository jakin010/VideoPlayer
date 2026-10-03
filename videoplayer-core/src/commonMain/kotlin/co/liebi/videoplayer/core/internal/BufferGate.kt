package co.liebi.videoplayer.core.internal

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Start gating for engines without a native equivalent of Media3's load control (§5). */
internal object BufferGate {

    /** Absorbs rounding in reported buffered ranges and durations. */
    private val Tolerance = 100.milliseconds

    /**
     * @param bufferedAhead End of the contiguous buffered range containing the position, minus the position.
     * @param remaining Media left after the position, or `null` when unknown.
     * @param threshold `minBufferToStart` or `minBufferAfterRebuffer`.
     */
    fun isSatisfied(bufferedAhead: Duration, remaining: Duration?, threshold: Duration): Boolean {
        val required = if (remaining != null && remaining < threshold) remaining else threshold
        return bufferedAhead >= required - Tolerance
    }
}
