package co.liebi.videoplayer.core

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.internal.SeekZone
import co.liebi.videoplayer.core.internal.seekZoneAt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The built-in gestures of a video surface (§11). Each one can be turned off:
 *
 * ```
 * VideoGestures(holdToPause = false, doubleTapSeek = null)
 * ```
 *
 * @param tap Single taps call the surface's `onTap`, which `VideoPlayer` uses to show and hide its controls.
 * @param doubleTapSeek Seek by double-tapping the sides, or `null` to turn it off.
 * @param holdToPause Pause while a finger is held on the video, resume on release.
 * @param swipeToFullscreen Swipe up and release to enter fullscreen, and down in fullscreen to leave it.
 *   Entering needs [PlayerConfiguration.fullscreenEnabled]. A swipe in that direction that starts on the video
 *   belongs to the player, so turn this off in scrolling feeds, where swiping up on a video should scroll.
 */
@Immutable
public data class VideoGestures(
    val tap: Boolean = true,
    val doubleTapSeek: DoubleTapSeek? = DoubleTapSeek(),
    val holdToPause: Boolean = true,
    val swipeToFullscreen: Boolean = true,
) {
    public companion object {
        /** No built-in gestures; the app's own gestures still work. */
        public val None: VideoGestures = VideoGestures(tap = false, doubleTapSeek = null, holdToPause = false, swipeToFullscreen = false)
    }
}

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

/** The running double-tap seek, for feedback such as `SeekIndicator`. */
@Immutable
public data class SeekFeedback(
    val direction: SeekDirection,
    /** The total seeked by this run of taps, for example 30 s after three taps. */
    val amount: Duration,
)

/** [Up] enters fullscreen, [Down] leaves it. */
public enum class SwipeDirection { Up, Down }

/** The running swipe into or out of fullscreen, for feedback such as `SwipeIndicator`. */
@Immutable
public data class SwipeFeedback(
    val direction: SwipeDirection,
    /** How far the swipe has come, from 0 to 1. At 1, releasing enters or leaves fullscreen. */
    val progress: Float,
)

/**
 * What the built-in gestures are doing, for feedback drawn over the video. Pass the same state to the
 * surface and to the feedback, such as `SeekIndicator` and `SwipeIndicator`.
 */
@Stable
public class VideoGestureState {
    /** The running double-tap seek, or `null` when there is none to show. */
    public var seekFeedback: SeekFeedback? by mutableStateOf(null)
        internal set

    /** The running swipe into or out of fullscreen, or `null` when there is none. */
    public var swipeFeedback: SwipeFeedback? by mutableStateOf(null)
        internal set

    /** A press-and-hold is pausing the player. */
    public var isHolding: Boolean by mutableStateOf(false)
        internal set
}

@Composable
public fun rememberVideoGestureState(): VideoGestureState = remember { VideoGestureState() }

/**
 * Handles [gestures] for [controller], with feedback in [state]. Touches that something else (such as the
 * app's own gestures, which see them first) consumed are ignored.
 */
internal fun Modifier.videoGestures(
    controller: PlayerController,
    gestures: VideoGestures,
    state: VideoGestureState,
    onTap: () -> Unit,
): Modifier = pointerInput(controller, gestures, state) { detectVideoGestures(controller, gestures, state, onTap) }

private sealed interface Press {
    data class Tap(val up: PointerInputChange) : Press
    data object Hold : Press
    data class Swipe(val direction: SwipeDirection, val claimed: PointerInputChange) : Press
    data object Cancelled : Press
}

private class SeekRun(val direction: SeekDirection, val start: Duration) {
    var steps = 0
    var lastUpMillis = 0L
}

private suspend fun PointerInputScope.detectVideoGestures(
    controller: PlayerController,
    gestures: VideoGestures,
    state: VideoGestureState,
    onTap: () -> Unit,
) = coroutineScope {
    val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
    var firstTapUpMillis: Long? = null
    var pendingSingleTap: Job? = null
    var seekRun: SeekRun? = null
    var feedbackJob: Job? = null

    fun singleTap() {
        if (gestures.tap) onTap()
    }

    fun showFeedback(run: SeekRun, step: Duration) {
        state.seekFeedback = SeekFeedback(run.direction, step * run.steps)
        feedbackJob?.cancel()
        feedbackJob = launch {
            delay(doubleTapTimeout.milliseconds + FeedbackLinger)
            state.seekFeedback = null
        }
    }

    fun seekStep(direction: SeekDirection, upMillis: Long, config: DoubleTapSeek) {
        val current = controller.state.value
        // Never seeks media that cannot seek, including live streams.
        if (!current.isSeekable || current.isLive) return
        val run = seekRun?.takeIf { it.direction == direction }
            ?: SeekRun(direction, controller.progress.value.position).also { seekRun = it }
        run.steps++
        run.lastUpMillis = upMillis
        val delta = config.step * run.steps
        // Clamped by the controller; rapid seeks coalesce there too. Play intent is unchanged.
        controller.seekTo(if (direction == SeekDirection.Forward) run.start + delta else run.start - delta)
        showFeedback(run, config.step)
    }

    fun onTapUp(downMillis: Long, up: PointerInputChange) {
        val config = gestures.doubleTapSeek
        if (config == null) {
            singleTap()
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
            singleTap()
        }
    }

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.isConsumed) return@awaitEachGesture
        when (val press = awaitTapOrHold(down, allowedSwipe(controller, gestures))) {
            // Taps nothing handles are left to the app's own handlers.
            is Press.Tap -> if (gestures.tap || gestures.doubleTapSeek != null) {
                press.up.consume()
                onTapUp(down.uptimeMillis, press.up)
            }
            // Holding a paused player does nothing.
            Press.Hold -> if (gestures.holdToPause && controller.state.value.playWhenReady) {
                holdUntilRelease(down.id, controller, state)
            }
            is Press.Swipe -> {
                val completed = try {
                    completeSwipe(down, press.claimed, press.direction, SwipeDistance.toPx()) { progress ->
                        state.swipeFeedback = SwipeFeedback(press.direction, progress)
                    }
                } finally {
                    state.swipeFeedback = null
                }
                if (completed) {
                    when (press.direction) {
                        SwipeDirection.Up -> controller.enterFullscreen()
                        SwipeDirection.Down -> controller.exitFullscreen()
                    }
                }
            }
            Press.Cancelled -> Unit
        }
    }
}

/** Up enters fullscreen when it is enabled; down leaves it. */
private fun allowedSwipe(controller: PlayerController, gestures: VideoGestures): SwipeDirection? {
    if (!gestures.swipeToFullscreen) return null
    val state = controller.state.value
    return when {
        state.presentation == Presentation.Fullscreen -> SwipeDirection.Down
        state.isFullscreenAvailable -> SwipeDirection.Up
        else -> null
    }
}

/**
 * A tap ends before the long-press timeout without moving past touch slop. Moving past it cancels both:
 * the drag is left to a scrolling parent, unless it is mostly vertical in the [swipe] direction, which the
 * player then claims.
 */
private suspend fun AwaitPointerEventScope.awaitTapOrHold(down: PointerInputChange, swipe: SwipeDirection?): Press = try {
    withTimeout(viewConfiguration.longPressTimeoutMillis) {
        var result: Press? = null
        while (result == null) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            result = when {
                change == null || event.changes.count { it.pressed } > 1 -> Press.Cancelled
                change.isConsumed -> Press.Cancelled
                change.changedToUp() -> Press.Tap(change)
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> {
                    val delta = change.position - down.position
                    val isSwipe = swipe != null && abs(delta.y) > abs(delta.x) && (delta.y < 0) == (swipe == SwipeDirection.Up)
                    if (isSwipe) {
                        // Consumed before a scrolling parent sees it, so the parent doesn't scroll.
                        change.consume()
                        Press.Swipe(swipe, change)
                    } else {
                        Press.Cancelled
                    }
                }
                else -> null
            }
        }
        result
    }
} catch (_: PointerEventTimeoutCancellationException) {
    Press.Hold
}

/**
 * Follows a swipe [claimed] at touch slop until release, reporting its progress toward [distance] from 0 to 1
 * on each move. True if it ended at least [distance] away in its [direction].
 */
private suspend fun AwaitPointerEventScope.completeSwipe(
    down: PointerInputChange,
    claimed: PointerInputChange,
    direction: SwipeDirection,
    distance: Float,
    onProgress: (Float) -> Unit,
): Boolean {
    fun progressAt(position: Offset): Float {
        val travelled = position.y - down.position.y
        return (if (direction == SwipeDirection.Up) -travelled else travelled) / distance
    }
    onProgress(progressAt(claimed.position).coerceIn(0f, 1f))
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return false
        if (change.isConsumed || event.changes.count { it.pressed } > 1) return false
        // Checked before consuming: a consumed change no longer reports the release.
        val released = change.changedToUp()
        change.consume()
        val progress = progressAt(change.position)
        if (released) return progress >= 1f
        onProgress(progress.coerceIn(0f, 1f))
    }
}

/** Pauses with [PauseReason.Hold] until the finger lifts or something else takes the gesture over. */
private suspend fun AwaitPointerEventScope.holdUntilRelease(pointer: PointerId, controller: PlayerController, state: VideoGestureState) {
    controller.beginHold()
    state.isHolding = true
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
        state.isHolding = false
        controller.endHold()
    }
}

private val FeedbackLinger = 400.milliseconds

/** How far a swipe must travel before release to enter or leave fullscreen. */
private val SwipeDistance = 48.dp
