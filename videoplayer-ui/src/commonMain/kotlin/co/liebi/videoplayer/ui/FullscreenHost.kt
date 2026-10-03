package co.liebi.videoplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import co.liebi.videoplayer.core.InternalVideoPlayerApi
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerCoordinator
import co.liebi.videoplayer.core.PlayerError
import co.liebi.videoplayer.core.VideoAspectRatio
import co.liebi.videoplayer.core.VideoContentScale
import co.liebi.videoplayer.core.VideoPlayerSurface
import co.liebi.videoplayer.ui.internal.HideSystemBars
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Shows the coordinator's fullscreen player above all app content, with system bars hidden (§12).
 * Place it once at the root of the UI, after the app content and outside any padding for system bars:
 *
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     AppContent()
 *     FullscreenHost()
 * }
 * ```
 *
 * Without a host, [PlayerController.enterFullscreen] does nothing and [FullscreenButton] hides itself.
 * The video shows uncropped at its native aspect ratio, fitted and centered: the inline surface's aspect
 * ratio and content scale don't apply. Back on Android, or the exit button, leaves fullscreen.
 * On iOS, Compose can't hide the status bar itself; see `FullscreenStatusBar`.
 *
 * @param controls Replaces the whole overlay: controls, loading and error UI. The surface, gestures,
 *   auto-hide timing and Back handling stay. It receives the fullscreen player, so controls can differ per player.
 */
@OptIn(InternalVideoPlayerApi::class)
@Composable
public fun FullscreenHost(
    coordinator: PlayerCoordinator = PlayerCoordinator.Default,
    modifier: Modifier = Modifier,
    hideControlsAfter: Duration = 3.seconds,
    tapTogglesControls: Boolean = true,
    holdToPause: Boolean = true,
    doubleTapSeek: DoubleTapSeek? = DoubleTapSeek(),
    colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
    loading: @Composable () -> Unit = { LoadingIndicator(colors = colors) },
    error: @Composable (error: PlayerError, retry: () -> Unit) -> Unit = { _, retry -> ErrorPanel(retry, colors = colors) },
    controls: (@Composable (controller: PlayerController, visibility: ControlsVisibility) -> Unit)? = null,
) {
    DisposableEffect(coordinator) {
        val unregister = coordinator.registerFullscreenHost()
        onDispose { unregister() }
    }
    val controller by coordinator.fullscreenPlayer.collectAsState()
    controller?.let {
        // Fresh controls, gestures and auto-hide state for each player.
        key(it) {
            FullscreenContent(
                controller = it,
                modifier = modifier,
                hideControlsAfter = hideControlsAfter,
                tapTogglesControls = tapTogglesControls,
                holdToPause = holdToPause,
                doubleTapSeek = doubleTapSeek,
                colors = colors,
                loading = loading,
                error = error,
                controls = controls,
            )
        }
    }
}

@Composable
internal fun FullscreenContent(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    hideControlsAfter: Duration = 3.seconds,
    tapTogglesControls: Boolean = true,
    holdToPause: Boolean = true,
    doubleTapSeek: DoubleTapSeek? = DoubleTapSeek(),
    colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
    loading: @Composable () -> Unit = { LoadingIndicator(colors = colors) },
    error: @Composable (error: PlayerError, retry: () -> Unit) -> Unit = { _, retry -> ErrorPanel(retry, colors = colors) },
    controls: (@Composable (controller: PlayerController, visibility: ControlsVisibility) -> Unit)? = null,
) {
    val visibility = rememberControlsVisibility(controller, hideControlsAfter)
    val gestures = rememberVideoGestures(
        controller = controller,
        visibility = visibility.takeIf { tapTogglesControls },
        holdToPause = holdToPause,
        doubleTapSeek = doubleTapSeek,
    )
    val state by controller.state.collectAsState()

    HideSystemBars()
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = controller::exitFullscreen,
    )

    // The gestures cover the whole screen, so touches never reach the app content underneath.
    Box(modifier.fillMaxSize().background(Color.Black).videoGestures(gestures)) {
        FittedVideo(controller, state.videoSize)
        SeekIndicator(gestures, Modifier.matchParentSize(), colors)
        Box(Modifier.matchParentSize().safeDrawingPadding()) {
            if (controls != null) {
                controls(controller, visibility)
            } else {
                DefaultOverlay(controller, visibility, colors, loading, error, Modifier.matchParentSize())
            }
        }
    }
}

/** The video at its native aspect ratio, as large as fits and centered; the rest stays black. */
@Composable
private fun FittedVideo(controller: PlayerController, videoSize: IntSize?) {
    val ratio = rememberStableRatio(videoSize)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, maxHeight * ratio)
        VideoPlayerSurface(
            controller = controller,
            modifier = Modifier.size(width, width / ratio),
            aspectRatio = VideoAspectRatio.Native,
            contentScale = VideoContentScale.Fit,
        )
    }
}

/** Ignores the sub-percent ratio changes of adaptive streams switching resolution, so the video doesn't jump. */
@Composable
private fun rememberStableRatio(videoSize: IntSize?): Float {
    val holder = remember { FloatArray(1) { DefaultRatio } }
    val ratio = videoSize?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width.toFloat() / it.height }
    if (ratio != null && abs(ratio - holder[0]) / holder[0] > 0.01f) holder[0] = ratio
    return holder[0]
}

private const val DefaultRatio = 16f / 9f
