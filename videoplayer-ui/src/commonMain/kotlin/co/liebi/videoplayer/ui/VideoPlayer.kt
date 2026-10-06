package co.liebi.videoplayer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerError
import co.liebi.videoplayer.core.VideoAspectRatio
import co.liebi.videoplayer.core.VideoContentScale
import co.liebi.videoplayer.core.VideoGestureState
import co.liebi.videoplayer.core.VideoGestures
import co.liebi.videoplayer.core.VideoPlayerSurface
import co.liebi.videoplayer.core.rememberVideoGestureState
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The video with the default controls overlaid (§11): [PlayerControls] at the bottom, [FullscreenControls] in
 * the top right, auto-hide, and the video gestures with their feedback. Every part is also available on its
 * own for custom layouts. The fullscreen button shows when `PlayerConfiguration.fullscreenEnabled` is on.
 *
 * @param hideControlsAfter Controls hide this long after the last interaction while playing.
 * @param gestures The built-in gestures, each of which can be turned off. A single tap shows or hides the
 *   controls. Turn [VideoGestures.swipeToFullscreen] off in scrolling feeds, where swiping up should scroll.
 * @param customGestures The app's own gestures on the video, which see touches before the built-in ones.
 *   See [VideoPlayerSurface].
 * @param colors Overrides the colors of [VideoPlayerTheme] for this player. Icons come from the theme.
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
    gestures: VideoGestures = VideoGestures(),
    customGestures: Modifier = Modifier,
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
    loading: @Composable () -> Unit = { LoadingIndicator(colors = colors) },
    error: @Composable (error: PlayerError, retry: () -> Unit) -> Unit = { _, retry -> ErrorPanel(retry, colors = colors) },
) {
    val visibility = rememberControlsVisibility(controller, hideControlsAfter)
    val gestureState = rememberVideoGestureState()
    KeepControlsWhileSeeking(gestureState, visibility)

    Box(modifier) {
        VideoPlayerSurface(
            controller = controller,
            aspectRatio = aspectRatio,
            contentScale = contentScale,
            poster = poster,
            gestures = gestures,
            gestureState = gestureState,
            onTap = visibility::toggle,
            customGestures = customGestures,
        )
        SeekIndicator(gestureState, Modifier.matchParentSize(), colors)
        SwipeIndicator(gestureState, Modifier.matchParentSize(), colors)
        DefaultOverlay(controller, visibility, colors, loading, error, Modifier.matchParentSize())
    }
}

/** Each double-tap seek counts as an interaction, so visible controls stay up while the user seeks. */
@Composable
internal fun KeepControlsWhileSeeking(state: VideoGestureState, visibility: ControlsVisibility) {
    LaunchedEffect(state, visibility) {
        snapshotFlow { state.seekFeedback }.filterNotNull().collect { visibility.onInteraction() }
    }
}

/**
 * Loading and error content in the center, [PlayerControls] at the bottom and [FullscreenControls] in the top
 * right, with the rotate buttons when [rotation] is given. Both fade with the same visibility.
 */
@Composable
internal fun DefaultOverlay(
    controller: PlayerController,
    visibility: ControlsVisibility,
    colors: PlayerControlsColors,
    loading: @Composable () -> Unit,
    error: @Composable (error: PlayerError, retry: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    rotation: FullscreenViewRotation? = null,
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
        FullscreenControls(
            controller = controller,
            modifier = Modifier.align(AbsoluteAlignment.TopRight),
            visibility = visibility,
            rotation = rotation,
            colors = colors,
        )
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
