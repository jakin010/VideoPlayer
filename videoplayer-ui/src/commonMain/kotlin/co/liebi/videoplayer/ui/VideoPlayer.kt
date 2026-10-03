package co.liebi.videoplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    val state by controller.state.collectAsState()
    val currentError = state.error.takeIf { state.status == PlaybackStatus.Error }
    val isLoading = rememberDelayedLoading(
        state.status == PlaybackStatus.Preparing || state.status == PlaybackStatus.Buffering || state.isSeeking,
    )

    Box(modifier.videoGestures(gestures)) {
        VideoPlayerSurface(controller, aspectRatio = aspectRatio, contentScale = contentScale, poster = poster)
        SeekIndicator(gestures, Modifier.matchParentSize(), colors)
        Box(Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
            when {
                currentError != null -> error(currentError, controller::retry)
                isLoading -> loading()
            }
        }
        PlayerControls(controller, Modifier.align(Alignment.BottomStart), visibility = visibility, colors = colors)
    }
}

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
