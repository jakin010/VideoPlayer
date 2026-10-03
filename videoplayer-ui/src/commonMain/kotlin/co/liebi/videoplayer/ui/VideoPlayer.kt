package co.liebi.videoplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerError
import co.liebi.videoplayer.core.VideoAspectRatio
import co.liebi.videoplayer.core.VideoContentScale
import co.liebi.videoplayer.core.VideoPlayerSurface
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The video with the default controls overlaid (§11): auto-hide, tap to toggle controls, double-tap seek and
 * press-and-hold to pause are wired. Every part is also available on its own for custom layouts.
 * A [FullscreenButton] shows in the top right once a `FullscreenHost` is placed for the player's coordinator.
 *
 * @param hideControlsAfter Controls hide this long after the last interaction while playing.
 * @param tapTogglesControls Single taps on the video show and hide the controls.
 * @param holdToPause Pause while a finger is held on the video.
 * @param doubleTapSeek Seek by double-tapping the sides, or `null` to turn it off.
 * @param loading Shown while preparing, buffering or seeking, once that has lasted a moment.
 * @param error Shown when the player fails; `retry` re-prepares the item.
 */
@Composable
public fun VideoPlayer(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    aspectRatio: VideoAspectRatio = VideoAspectRatio.Ratio16x9,
    contentScale: VideoContentScale = VideoContentScale.Crop,
    poster: @Composable () -> Unit = {},
    hideControlsAfter: Duration = 3.seconds,
    tapTogglesControls: Boolean = true,
    holdToPause: Boolean = true,
    doubleTapSeek: DoubleTapSeek? = DoubleTapSeek(),
    colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
    loading: @Composable () -> Unit = { LoadingIndicator(colors = colors) },
    error: @Composable (error: PlayerError, retry: () -> Unit) -> Unit = { _, retry -> ErrorPanel(retry, colors = colors) },
) {
    val visibility = rememberControlsVisibility(controller, hideControlsAfter)
    val gestures = rememberVideoGestures(
        controller = controller,
        visibility = visibility.takeIf { tapTogglesControls },
        holdToPause = holdToPause,
        doubleTapSeek = doubleTapSeek,
    )

    Box(modifier.videoGestures(gestures)) {
        VideoPlayerSurface(controller, aspectRatio = aspectRatio, contentScale = contentScale, poster = poster)
        SeekIndicator(gestures, Modifier.matchParentSize(), colors)
        DefaultOverlay(controller, visibility, colors, loading, error, Modifier.matchParentSize())
    }
}

/**
 * Loading and error content in the center, the default controls at the bottom and a [FullscreenButton] in the
 * top right. The button is separate from [PlayerControls] but fades with the same visibility.
 */
@Composable
internal fun DefaultOverlay(
    controller: PlayerController,
    visibility: ControlsVisibility,
    colors: PlayerControlsColors,
    loading: @Composable () -> Unit,
    error: @Composable (error: PlayerError, retry: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val currentError = state.error.takeIf { state.status == PlaybackStatus.Error }
    val isLoading = rememberDelayedLoading(
        state.status == PlaybackStatus.Preparing || state.status == PlaybackStatus.Buffering || state.isSeeking,
    )
    Box(modifier) {
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            when {
                currentError != null -> error(currentError, controller::retry)
                isLoading -> loading()
            }
        }
        PlayerControls(controller, Modifier.align(Alignment.BottomStart), visibility = visibility, colors = colors)
        // Top right in every layout direction, like the other media controls (§11).
        AnimatedVisibility(
            visible = visibility.isVisible,
            modifier = Modifier.align(AbsoluteAlignment.TopRight).padding(FullscreenButtonPadding),
            enter = ControlsEnter,
            exit = ControlsExit,
        ) {
            FullscreenButton(controller, visibility = visibility, colors = colors)
        }
    }
}

/** Matches the side padding of [PlayerControls]. */
private val FullscreenButtonPadding = 8.dp

/** True once [isLoading] has lasted [LoadingDelay], so short stalls and fast seeks don't flash a spinner. */
@Composable
private fun rememberDelayedLoading(isLoading: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(isLoading) {
        if (isLoading) delay(LoadingDelay)
        visible = isLoading
    }
    return visible
}

private val LoadingDelay = 500.milliseconds
