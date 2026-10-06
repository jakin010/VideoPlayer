package co.liebi.videoplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.PlayerError
import co.liebi.videoplayer.core.VideoAspectRatio
import co.liebi.videoplayer.core.VideoContentScale
import co.liebi.videoplayer.core.VideoGestures
import co.liebi.videoplayer.core.VideoPlayerSurface
import co.liebi.videoplayer.core.rememberVideoGestureState
import co.liebi.videoplayer.ui.internal.HideSystemBars
import co.liebi.videoplayer.ui.internal.rememberDeviceTurnedRight
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A player shown fullscreen (§12): the video uncropped at its native aspect ratio, fitted to the screen, with
 * the default controls, gestures and auto-hide, the system bars hidden, and Back leaving fullscreen. The
 * view can turn in 90-degree steps, see [FullscreenRotation].
 *
 * The library never shows it by itself: [PlayerController.enterFullscreen] only switches the player's
 * presentation. The app shows this while the player is fullscreen, wherever suits it, such as above the
 * screen's content, in a dialog, or as its own navigation destination:
 *
 * ```
 * Box(Modifier.fillMaxSize()) {
 *     ScreenContent() // with VideoPlayer(controller)
 *     val state by controller.state.collectAsState()
 *     if (state.presentation == Presentation.Fullscreen) FullscreenVideoPlayer(controller)
 * }
 * ```
 *
 * It fills the space it gets and takes every touch, so give it the whole screen, outside any padding for
 * system bars. On iOS, Compose can't hide the status bar itself; see `FullscreenStatusBar`.
 *
 * @param gestures The built-in gestures, each of which can be turned off. Swiping down leaves fullscreen.
 * @param customGestures The app's own gestures on the video, which see touches before the built-in ones.
 *   They turn with the view. See [VideoPlayerSurface].
 * @param rotation Turning the view in 90-degree steps, with buttons and automatically to fit the video's
 *   shape; it works while the user has locked the screen's rotation. `null` turns it off.
 * @param colors Overrides the colors of [VideoPlayerTheme]. Icons come from the theme.
 * @param controls Replaces the whole overlay: controls, loading and error UI. The surface, gestures,
 *   auto-hide timing, rotation and Back handling stay. [FullscreenControls] and custom rotate buttons get
 *   the rotation from [LocalFullscreenViewRotation].
 */
@Composable
public fun FullscreenVideoPlayer(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    hideControlsAfter: Duration = 3.seconds,
    gestures: VideoGestures = VideoGestures(),
    customGestures: Modifier = Modifier,
    rotation: FullscreenRotation? = FullscreenRotation(),
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
    loading: @Composable () -> Unit = { LoadingIndicator(colors = colors) },
    error: @Composable (error: PlayerError, retry: () -> Unit) -> Unit = { _, retry -> ErrorPanel(retry, colors = colors) },
    controls: (@Composable (visibility: ControlsVisibility) -> Unit)? = null,
) {
    // Fresh controls, gestures, auto-hide state and rotation for each player.
    key(controller) {
        FullscreenContent(
            controller = controller,
            modifier = modifier,
            hideControlsAfter = hideControlsAfter,
            gestures = gestures,
            customGestures = customGestures,
            rotation = rotation,
            colors = colors,
            loading = loading,
            error = error,
            controls = controls,
        )
    }
}

/** @param viewRotation The rotation state, hoisted for tests; created here otherwise. */
@Composable
internal fun FullscreenContent(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    hideControlsAfter: Duration = 3.seconds,
    gestures: VideoGestures = VideoGestures(),
    customGestures: Modifier = Modifier,
    rotation: FullscreenRotation? = FullscreenRotation(),
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
    loading: @Composable () -> Unit = { LoadingIndicator(colors = colors) },
    error: @Composable (error: PlayerError, retry: () -> Unit) -> Unit = { _, retry -> ErrorPanel(retry, colors = colors) },
    controls: (@Composable (visibility: ControlsVisibility) -> Unit)? = null,
    viewRotation: FullscreenViewRotation? = null,
) {
    val visibility = rememberControlsVisibility(controller, hideControlsAfter)
    val gestureState = rememberVideoGestureState()
    KeepControlsWhileSeeking(gestureState, visibility)
    val state by controller.state.collectAsState()
    val shape = rememberLastVideoShape(state.videoSize)

    HideSystemBars()
    NavigationBackHandler(
        state = rememberNavigationEventState(NavigationEventInfo.None),
        onBackCompleted = controller::exitFullscreen,
    )

    // Covers the whole screen and takes every touch, so none reach the app content underneath.
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {}) {
        val screenIsLandscape = maxWidth > maxHeight
        val autoRotate = rotation?.autoRotate == true
        // Turned from the first frame, so entering fullscreen doesn't show an unturned frame first.
        val turn = viewRotation ?: remember {
            FullscreenViewRotation(if (autoRotate) autoRotation(screenIsLandscape, shape, deviceTurnedRight = null) else 0)
        }
        if (autoRotate) AutoRotate(turn, screenIsLandscape, shape)
        val degrees = if (rotation != null) turn.degrees else 0
        val turned = degrees % 180 != 0

        // The video covers the screen, fitted and turned by the platform: native video views don't follow
        // Compose's graphics layers. The surface turns its gestures with the video.
        VideoPlayerSurface(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            // The surface covers exactly the space it gets, so its gestures reach the letterbox bars too.
            aspectRatio = VideoAspectRatio.Fixed((maxWidth / maxHeight).takeIf { it.isFinite() && it > 0f } ?: DefaultRatio),
            contentScale = VideoContentScale.Fit,
            rotationDegrees = degrees,
            gestures = gestures,
            gestureState = gestureState,
            onTap = visibility::toggle,
            customGestures = customGestures,
        )

        // The controls and feedback are turned in Compose to the same angle: the turned box exactly covers the screen.
        Box(
            Modifier
                .align(Alignment.Center)
                .requiredSize(if (turned) maxHeight else maxWidth, if (turned) maxWidth else maxHeight)
                .graphicsLayer { rotationZ = degrees.toFloat() },
        ) {
            SeekIndicator(gestureState, Modifier.matchParentSize(), colors)
            SwipeIndicator(gestureState, Modifier.matchParentSize(), colors)
            CompositionLocalProvider(LocalFullscreenViewRotation provides turn.takeIf { rotation != null }) {
                Box(Modifier.matchParentSize().padding(turnedSafeDrawing(degrees))) {
                    if (controls != null) {
                        controls(visibility)
                    } else {
                        DefaultOverlay(
                            controller = controller,
                            visibility = visibility,
                            colors = colors,
                            loading = loading,
                            error = error,
                            modifier = Modifier.matchParentSize(),
                            rotation = turn.takeIf { rotation?.showButtons == true },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Turns the view to fit the video's shape whenever the screen's orientation or the video's shape changes.
 * The device's tilt, once the sensor reports it, picks the direction, so the video is upright for how the
 * device is held. A turn the user made in the meantime is kept.
 */
@Composable
private fun AutoRotate(turn: FullscreenViewRotation, screenIsLandscape: Boolean, shape: VideoShape?) {
    val turnedRight = rememberDeviceTurnedRight()
    LaunchedEffect(screenIsLandscape, shape) {
        val chosen = autoRotation(screenIsLandscape, shape, turnedRight.value)
        turn.degrees = chosen
        val measured = withTimeoutOrNull(TiltWait) { snapshotFlow { turnedRight.value }.filterNotNull().first() }
            ?: return@LaunchedEffect
        if (turn.degrees == chosen) turn.degrees = autoRotation(screenIsLandscape, shape, measured)
    }
}

/**
 * The screen's safe drawing insets, seen from content turned clockwise by [degrees]: turned 90 degrees, the
 * content's top edge lies along the screen's right edge, and so on. Absolute, because the insets are.
 */
@Composable
private fun turnedSafeDrawing(degrees: Int): PaddingValues {
    val insets = WindowInsets.safeDrawing
    val density = LocalDensity.current
    val (left, top, right, bottom) = with(density) {
        listOf(
            insets.getLeft(density, LayoutDirection.Ltr).toDp(),
            insets.getTop(density).toDp(),
            insets.getRight(density, LayoutDirection.Ltr).toDp(),
            insets.getBottom(density).toDp(),
        )
    }
    return when (degrees) {
        90 -> PaddingValues.Absolute(left = top, top = right, right = bottom, bottom = left)
        180 -> PaddingValues.Absolute(left = right, top = bottom, right = left, bottom = top)
        270 -> PaddingValues.Absolute(left = bottom, top = left, right = top, bottom = right)
        else -> PaddingValues.Absolute(left = left, top = top, right = right, bottom = bottom)
    }
}

/**
 * The video's shape, kept while no size is known (between items, for example), so the view doesn't turn
 * back and forth. `null` until the first size.
 */
@Composable
private fun rememberLastVideoShape(videoSize: IntSize?): VideoShape? {
    val last = remember { arrayOfNulls<VideoShape>(1) }
    videoShapeOf(videoSize)?.let { last[0] = it }
    return last[0]
}

private const val DefaultRatio = 16f / 9f

/** How long a turn waits for the tilt sensor's first reading to pick its direction. */
private val TiltWait = 500.milliseconds
