package co.liebi.videoplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.DpTouchBoundsExpansion
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.PlaybackProgress
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.ui.generated.resources.Res
import co.liebi.videoplayer.ui.generated.resources.videoplayer_hours
import co.liebi.videoplayer.ui.generated.resources.videoplayer_minutes
import co.liebi.videoplayer.ui.generated.resources.videoplayer_position
import co.liebi.videoplayer.ui.generated.resources.videoplayer_position_value
import co.liebi.videoplayer.ui.generated.resources.videoplayer_seconds
import co.liebi.videoplayer.ui.internal.ScrubberMath
import co.liebi.videoplayer.ui.internal.expandedPointerInput
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Shows played, buffered and remaining media, and seeks by dragging (§11):
 * - A drag that starts on the handle follows the finger. A drag that starts elsewhere moves the
 *   position by the drag distance. A tap without a drag never seeks.
 * - While dragging, the drag target is shown and position updates are ignored; playback continues.
 * - On release one `seekTo` runs, and the target stays on screen until the seek completes.
 * - Disabled when the media is not seekable, including live streams.
 *
 * The scrubber is only as tall as its thumb, but its touch target is at least 48 dp.
 */
@Composable
public fun PlayerScrubber(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility? = null,
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val state by controller.state.collectAsState()
        val progress = controller.progress.collectAsState()
        val range = state.seekableRange?.takeIf { state.isSeekable }

        var dragTarget by remember(controller) { mutableStateOf<Duration?>(null) }
        var releasedTarget by remember(controller) { mutableStateOf<Duration?>(null) }
        var width by remember { mutableIntStateOf(0) }

        val density = LocalDensity.current
        val thumbRadius = with(density) { PlayerControlsDefaults.ThumbSize.toPx() / 2 }

        val isAdvancing = state.isPlaying && !state.isSeeking && dragTarget == null && releasedTarget == null
        val smoothPosition = rememberSmoothPosition(
            progress = progress,
            isAdvancing = isAdvancing,
            speed = state.playbackSpeed,
            end = range?.endInclusive,
            // The duration of half a pixel of thumb travel: smaller changes are not drawn.
            minStep = {
                val travel = width - 2 * thumbRadius
                if (range == null || travel <= 0f) Duration.ZERO else (range.endInclusive - range.start) * (0.5 / travel)
            },
        )

        // Read lazily (in layout, draw and input) so position updates do not recompose the scrubber.
        val displayedPosition: () -> Duration = { dragTarget ?: releasedTarget ?: smoothPosition.value }

        LaunchedEffect(controller, releasedTarget) {
            if (releasedTarget != null) {
                controller.state.first { !it.isSeeking }
                releasedTarget = null
            }
        }

        val handleHalfWidth = with(density) { PlayerControlsDefaults.ScrubberTouchTarget.toPx() / 2 }
        val currentRange by rememberUpdatedState(range)
        val currentVisibility by rememberUpdatedState(visibility)
        val touchExpansion = (PlayerControlsDefaults.ScrubberTouchTarget - PlayerControlsDefaults.ThumbSize) / 2

        val gestureModifier = Modifier.expandedPointerInput(
            key = controller,
            expansion = DpTouchBoundsExpansion(top = touchExpansion, bottom = touchExpansion),
        ) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val seekRange = currentRange ?: return@awaitEachGesture
                // Claimed, so tap handlers around the scrubber don't also act on it.
                down.consume()
                val trackWidth = size.width.toFloat()
                val startPosition = displayedPosition()
                val thumbX = ScrubberMath.thumbCenterX(ScrubberMath.fraction(startPosition, seekRange), trackWidth, thumbRadius)
                val startedOnHandle = abs(down.position.x - thumbX) <= handleHalfWidth

                fun targetAt(x: Float): Duration =
                    if (startedOnHandle) {
                        ScrubberMath.absoluteTarget(x, trackWidth, thumbRadius, seekRange)
                    } else {
                        ScrubberMath.relativeTarget(startPosition, x - down.position.x, trackWidth, thumbRadius, seekRange)
                    }

                // A tap, or a gesture a parent takes over (such as a vertical scroll), never seeks.
                val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                if (slop == null) {
                    currentVisibility?.onInteraction()
                    return@awaitEachGesture
                }
                try {
                    // Keeps the controls visible for the whole drag.
                    currentVisibility?.isScrubbing = true
                    dragTarget = targetAt(slop.position.x)
                    val completed = horizontalDrag(slop.id) { change ->
                        change.consume()
                        dragTarget = targetAt(change.position.x)
                    }
                    val target = dragTarget
                    if (completed && target != null) {
                        releasedTarget = target
                        controller.seekTo(target)
                    }
                } finally {
                    dragTarget = null
                    currentVisibility?.let {
                        it.isScrubbing = false
                        it.onInteraction()
                    }
                }
            }
        }

        val label = stringResource(Res.string.videoplayer_position)
        val positionSeconds by remember(progress) { derivedStateOf { displayedPosition().inWholeSeconds } }
        val spokenState = stringResource(
            Res.string.videoplayer_position_value,
            spokenDuration(positionSeconds.seconds),
            spokenDuration(range?.endInclusive ?: state.duration ?: Duration.ZERO),
        )

        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(PlayerControlsDefaults.ThumbSize)
                .onSizeChanged { width = it.width }
                .semantics {
                    contentDescription = label
                    stateDescription = spokenState
                    if (range != null) {
                        val start = range.start.toSeconds()
                        val end = range.endInclusive.toSeconds()
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = positionSeconds.toFloat().coerceIn(start, end),
                            range = start..end,
                            // Accessibility adjustments move by about 10 s per step.
                            steps = ((end - start) / AccessibilityStepSeconds).toInt().minus(1).coerceAtLeast(0),
                        )
                        setProgress { value ->
                            controller.seekTo(value.toDouble().seconds.coerceIn(range))
                            true
                        }
                    } else {
                        disabled()
                    }
                }
                .then(if (range != null) gestureModifier else Modifier)
                .drawBehind {
                    drawTrack(
                        colors = colors,
                        range = range,
                        position = displayedPosition(),
                        buffered = progress.value.bufferedPosition,
                        thumbRadius = thumbRadius,
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            if (range != null) {
                Box(
                    Modifier
                        .offset {
                            val fraction = ScrubberMath.fraction(displayedPosition(), range)
                            val center = ScrubberMath.thumbCenterX(fraction, width.toFloat(), thumbRadius)
                            IntOffset((center - thumbRadius).roundToInt(), 0)
                        }
                        .size(PlayerControlsDefaults.ThumbSize)
                        .shadow(elevation = 2.dp, shape = CircleShape)
                        .background(colors.thumbColor, CircleShape),
                )
            }
        }
    }
}

/**
 * The playback position advanced every display frame. Progress updates arrive about every 250 ms, which
 * makes the thumb visibly step on short videos. While [isAdvancing], the position is extrapolated from the
 * latest update at [speed]; each update re-anchors it. Otherwise it follows [progress] exactly.
 * Changes smaller than [minStep] (half a pixel) are skipped, so long videos do not redraw every frame.
 */
@Composable
private fun rememberSmoothPosition(
    progress: State<PlaybackProgress>,
    isAdvancing: Boolean,
    speed: Float,
    end: Duration?,
    minStep: () -> Duration,
): State<Duration> {
    val smooth = remember(progress) { mutableStateOf(progress.value.position) }
    LaunchedEffect(progress, isAdvancing, speed, end) {
        if (!isAdvancing) {
            snapshotFlow { progress.value.position }.collect { smooth.value = it }
            return@LaunchedEffect
        }
        var anchor = progress.value.position
        var anchorNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { frameNanos ->
                val reported = progress.value.position
                if (reported != anchor) {
                    anchor = reported
                    anchorNanos = frameNanos
                }
                // Never run far ahead of the last update, for example while a stall is being reported.
                val elapsed = ((frameNanos - anchorNanos).nanoseconds * speed.toDouble()).coerceAtMost(MaxExtrapolation)
                val position = (anchor + elapsed).let { if (end != null) it.coerceAtMost(end) else it }
                if ((position - smooth.value).absoluteValue >= minStep()) smooth.value = position
            }
        }
    }
    return smooth
}

private fun DrawScope.drawTrack(
    colors: PlayerControlsColors,
    range: ClosedRange<Duration>?,
    position: Duration,
    buffered: Duration,
    thumbRadius: Float,
) {
    val trackHeight = PlayerControlsDefaults.TrackHeight.toPx()
    val top = (size.height - trackHeight) / 2
    val corner = CornerRadius(trackHeight / 2)

    fun bar(color: Color, endX: Float) {
        if (endX <= 0f || color.alpha == 0f) return
        drawRoundRect(color, topLeft = Offset(0f, top), size = Size(endX.coerceAtMost(size.width), trackHeight), cornerRadius = corner)
    }

    bar(colors.remainingTrackColor, size.width)
    if (range == null) return
    // Engines can report a buffered end slightly short of the duration once everything is loaded.
    val bufferedFraction = if (range.endInclusive - buffered <= FullyBufferedTolerance) 1f else ScrubberMath.fraction(buffered, range)
    bar(colors.bufferedTrackColor, ScrubberMath.barEndX(bufferedFraction, size.width, thumbRadius))
    bar(colors.playedTrackColor, ScrubberMath.barEndX(ScrubberMath.fraction(position, range), size.width, thumbRadius))
}

/** "1 minute 24 seconds", for screen readers. */
@Composable
private fun spokenDuration(duration: Duration): String {
    val total = duration.inWholeSeconds.coerceAtLeast(0)
    val hours = (total / 3600).toInt()
    val minutes = (total % 3600 / 60).toInt()
    val seconds = (total % 60).toInt()
    val parts = mutableListOf<String>()
    if (hours > 0) parts += pluralStringResource(Res.plurals.videoplayer_hours, hours, hours)
    if (minutes > 0) parts += pluralStringResource(Res.plurals.videoplayer_minutes, minutes, minutes)
    if (seconds > 0 || parts.isEmpty()) parts += pluralStringResource(Res.plurals.videoplayer_seconds, seconds, seconds)
    return parts.joinToString(" ")
}

private fun Duration.toSeconds(): Float = inWholeMilliseconds / 1000f

private const val AccessibilityStepSeconds = 10f

private val FullyBufferedTolerance = 250.milliseconds

private val MaxExtrapolation = 500.milliseconds
