package co.liebi.videoplayer.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.ui.internal.SeekZone
import co.liebi.videoplayer.ui.internal.seekZoneAt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Double-tap seek: the width is split into a back zone on the left, a forward zone on the right and a
 * centered [deadZone] (fraction of the width) where double taps do nothing.
 */
@Immutable
public data class DoubleTapSeek(
    val step: Duration = 10.seconds,
    val deadZone: Float = 0.2f,
) {
    init {
        require(step > Duration.ZERO) { "step must be positive" }
        require(deadZone in 0f..1f) { "deadZone must be in 0..1" }
    }
}

public enum class SeekDirection { Back, Forward }

/** The running double-tap seek, for feedback such as [SeekIndicator]. */
@Immutable
public data class SeekFeedback(
    val direction: SeekDirection,
    /** The total seeked by this run of taps, for example 30 s after three taps. */
    val amount: Duration,
)

/**
 * Gesture state for a video area (§11): tap to toggle controls, double-tap to seek and press-and-hold
 * to pause. Apply it with [videoGestures] to the surface or to any overlay above it.
 */
@Stable
public class VideoGestures internal constructor(
    internal val controller: PlayerController,
    internal val visibility: ControlsVisibility?,
    internal val holdToPause: Boolean,
    internal val doubleTapSeek: DoubleTapSeek?,
) {
    /** The running double-tap seek, or `null` when there is none to show. */
    public var seekFeedback: SeekFeedback? by mutableStateOf(null)
        internal set

    /** A press-and-hold is pausing the player. */
    public var isHolding: Boolean by mutableStateOf(false)
        internal set
}

/**
 * @param visibility Taps toggle these controls. Without it, single taps do nothing.
 * @param holdToPause Pause while a finger is held on the video.
 * @param doubleTapSeek Seek by double-tapping the sides, or `null` to turn it off.
 */
@Composable
public fun rememberVideoGestures(
    controller: PlayerController,
    visibility: ControlsVisibility? = null,
    holdToPause: Boolean = true,
    doubleTapSeek: DoubleTapSeek? = DoubleTapSeek(),
): VideoGestures = remember(controller, visibility, holdToPause, doubleTapSeek) {
    VideoGestures(controller, visibility, holdToPause, doubleTapSeek)
}

/** Handles [gestures] on this element. Touches that a child (such as a control) consumed are ignored. */
public fun Modifier.videoGestures(gestures: VideoGestures): Modifier =
    pointerInput(gestures) { detectVideoGestures(gestures) }

private sealed interface Press {
    data class Tap(val up: PointerInputChange) : Press
    data object Hold : Press
    data object Cancelled : Press
}

private class SeekRun(val direction: SeekDirection, val start: Duration) {
    var steps = 0
    var lastUpMillis = 0L
}

private suspend fun PointerInputScope.detectVideoGestures(gestures: VideoGestures) = coroutineScope {
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    var firstTapUpMillis: Long? = null
    var pendingSingleTap: Job? = null
    var seekRun: SeekRun? = null
    var feedbackJob: Job? = null

    fun showFeedback(run: SeekRun, step: Duration) {
        gestures.seekFeedback = SeekFeedback(run.direction, step * run.steps)
        feedbackJob?.cancel()
        feedbackJob = launch {
            delay(doubleTapTimeout.milliseconds + FeedbackLinger)
            gestures.seekFeedback = null
        }
    }

    fun seekStep(direction: SeekDirection, upMillis: Long, config: DoubleTapSeek) {
        val controller = gestures.controller
        val state = controller.state.value
        // Never seeks media that cannot seek, including live streams.
        if (!state.isSeekable || state.isLive) return
        val run = seekRun?.takeIf { it.direction == direction }
            ?: SeekRun(direction, controller.progress.value.position).also { seekRun = it }
        run.steps++
        run.lastUpMillis = upMillis
        val delta = config.step * run.steps
        // Clamped by the controller; rapid seeks coalesce there too. Play intent is unchanged.
        controller.seekTo(if (direction == SeekDirection.Forward) run.start + delta else run.start - delta)
        gestures.visibility?.onInteraction()
        showFeedback(run, config.step)
    }

    fun onTap(downMillis: Long, up: PointerInputChange) {
        val config = gestures.doubleTapSeek
        if (config == null) {
            gestures.visibility?.toggle()
            return
        }
        val direction = when (seekZoneAt(up.position.x, size.width.toFloat(), config.deadZone)) {
            SeekZone.Back -> SeekDirection.Back
            SeekZone.Forward -> SeekDirection.Forward
            SeekZone.Middle -> null
        }

        // Further taps within the timeout add one step each.
        val run = seekRun
        if (run != null && downMillis - run.lastUpMillis <= doubleTapTimeout) {
            if (direction != null) seekStep(direction, up.uptimeMillis, config) else seekRun = null
            return
        }
        seekRun = null

        val firstUp = firstTapUpMillis
        if (firstUp != null && downMillis - firstUp <= doubleTapTimeout) {
            pendingSingleTap?.cancel()
            firstTapUpMillis = null
            if (direction != null) seekStep(direction, up.uptimeMillis, config)
            return
        }

        // A first tap may start a double tap, so single taps act only after the timeout.
        firstTapUpMillis = up.uptimeMillis
        pendingSingleTap = launch {
            delay(doubleTapTimeout)
            firstTapUpMillis = null
            gestures.visibility?.toggle()
        }
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed) return@awaitEachGesture
        when (val press = awaitTapOrHold(down)) {
            is Press.Tap -> {
                press.up.consume()
                onTap(down.uptimeMillis, press.up)
            }
            Press.Hold -> holdUntilRelease(down.id, gestures)
            Press.Cancelled -> Unit
        }
    }
}

/** A tap ends before the long-press timeout without moving past touch slop; scrolling cancels both. */
private suspend fun AwaitPointerEventScope.awaitTapOrHold(down: PointerInputChange): Press = try {
    withTimeout(viewConfiguration.longPressTimeoutMillis) {
        var result: Press? = null
        while (result == null) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            result = when {
                change == null || event.changes.count { it.pressed } > 1 -> Press.Cancelled
                change.isConsumed -> Press.Cancelled
                change.changedToUp() -> Press.Tap(change)
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> Press.Cancelled
                else -> null
            }
        }
        result
    }
} catch (_: PointerEventTimeoutCancellationException) {
    Press.Hold
}

/**
 * Pauses with [co.liebi.videoplayer.core.PauseReason.Hold] until the finger lifts or a parent takes the
 * gesture over. It never shows or toggles controls, and on a paused player it does nothing.
 */
private suspend fun AwaitPointerEventScope.holdUntilRelease(pointer: PointerId, gestures: VideoGestures) {
    val controller = gestures.controller
    val isHolding = gestures.holdToPause && controller.state.value.playWhenReady
    if (isHolding) {
        controller.beginHold()
        gestures.isHolding = true
    }
    try {
        while (true) {
            val change = awaitPointerEvent().changes.firstOrNull { it.id == pointer } ?: break
            if (change.isConsumed) break
            if (change.changedToUp()) {
                change.consume()
                break
            }
        }
    } finally {
        if (isHolding) {
            gestures.isHolding = false
            controller.endHold()
        }
    }
}

private val FeedbackLinger = 400.milliseconds
