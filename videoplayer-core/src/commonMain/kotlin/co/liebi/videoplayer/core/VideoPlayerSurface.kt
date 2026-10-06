package co.liebi.videoplayer.core

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.collectAsState
import co.liebi.videoplayer.core.internal.DefaultPlayerController
import co.liebi.videoplayer.core.internal.KeepScreenAwake
import co.liebi.videoplayer.core.internal.PlatformVideoSurface
import co.liebi.videoplayer.core.internal.turned
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Draws the controller's video and handles the video gestures (§11). It fills the width it is given and takes
 * its height from [aspectRatio]. Controls can be layered on top; touches on them don't reach the gestures.
 *
 * A controller renders to one surface at a time: the most recently attached one. Other surfaces show [poster].
 * When no surface is attached for about a second, the controller suspends and frees its native player.
 *
 * @param contentScale How the video fills the area. The video is always centered.
 * @param poster Drawn over the video until the first frame is rendered. The library ships no image loader.
 * @param posterDelay Once video has been showing (for example when switching items), the poster only
 *   appears if the next first frame takes longer than this. Until then the last frame stays on screen,
 *   so quick switches go straight from video to video.
 * @param rotationDegrees Turns the video clockwise inside the area: 0, 90, 180 or 270. With
 *   [VideoAspectRatio.Native], the area takes the turned video's shape. The poster and the area are not turned;
 *   the gestures are, so a double tap on the video's right side seeks forward however it is turned.
 * @param gestures The built-in gestures, each of which can be turned off; [VideoGestures.None] turns them all off.
 * @param gestureState What the gestures are doing, for feedback such as a seek indicator.
 * @param onTap Called for single taps, unless [VideoGestures.tap] is off. Without it, single taps are left alone.
 * @param customGestures The app's own gestures, for example
 *   `Modifier.pointerInput(Unit) { detectHorizontalDragGestures { … } }`. They see touches before the built-in
 *   gestures, which ignore any touch they consume, and get positions in the video's frame when it is turned.
 */
@Composable
public fun VideoPlayerSurface(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    aspectRatio: VideoAspectRatio = VideoAspectRatio.Ratio16x9,
    contentScale: VideoContentScale = VideoContentScale.Crop,
    poster: @Composable () -> Unit = {},
    posterDelay: Duration = DefaultPosterDelay,
    rotationDegrees: Int = 0,
    gestures: VideoGestures = VideoGestures(),
    gestureState: VideoGestureState = rememberVideoGestureState(),
    onTap: (() -> Unit)? = null,
    customGestures: Modifier = Modifier,
) {
    require(rotationDegrees % 90 == 0) { "rotationDegrees must be a multiple of 90" }
    val rotation = rotationDegrees.mod(360)
    val state by controller.state.collectAsState()
    val videoSize = rememberStableVideoSize(state.videoSize)
    val ratio = when (aspectRatio) {
        is VideoAspectRatio.Fixed -> aspectRatio.ratio
        VideoAspectRatio.Native -> (videoSize?.ratio ?: DefaultRatio).let { if (rotation % 180 != 0) 1f / it else it }
    }
    Box(
        modifier = modifier.fillMaxWidth().aspectRatio(ratio).clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        // Starts visible: before any video has shown there is no frame to keep on screen.
        val posterVisible = remember(controller) { mutableStateOf(true) }
        val defaultController = controller as? DefaultPlayerController
        val isRendering = defaultController?.let {
            AttachedVideo(
                controller = it,
                videoSize = videoSize,
                contentScale = contentScale,
                keepPreviousFrame = !posterVisible.value && !state.isFirstFrameRendered,
                rotation = rotation,
            )
        } ?: false
        val videoVisible = isRendering && state.isFirstFrameRendered
        KeepScreenAwake(isRendering && state.isPlaying && defaultController.keepsScreenAwake)
        LaunchedEffect(videoVisible, posterDelay) {
            if (videoVisible) {
                posterVisible.value = false
            } else if (!posterVisible.value) {
                delay(posterDelay)
                posterVisible.value = true
            }
        }
        if (posterVisible.value) {
            Box(Modifier.matchParentSize()) { poster() }
        }
        // Above the poster, turned with the video, so positions are in the video's frame. Without anything to
        // handle, the built-in gestures stay out of the way, so touches reach the app's own handlers.
        val currentOnTap by rememberUpdatedState(onTap)
        val active = gestures.copy(tap = gestures.tap && onTap != null)
        Box(
            Modifier
                .matchParentSize()
                .turned(rotation)
                .then(
                    if (active == VideoGestures.None) {
                        Modifier
                    } else {
                        Modifier.videoGestures(controller, active, gestureState) { currentOnTap?.invoke() }
                    },
                )
                .then(customGestures),
        )
    }
}

/** Attaches a surface to [controller] for as long as it is composed. Returns whether it is the rendering one. */
@Composable
private fun AttachedVideo(
    controller: DefaultPlayerController,
    videoSize: IntSize?,
    contentScale: VideoContentScale,
    keepPreviousFrame: Boolean,
    rotation: Int,
): Boolean {
    val token = remember(controller) { Any() }
    DisposableEffect(controller, token) {
        controller.attachSurface(token)
        onDispose { controller.detachSurface(token) }
    }
    val activeSurface by controller.activeSurface.collectAsState()
    val engine by controller.engine.collectAsState()
    val isActive = activeSurface === token
    PlatformVideoSurface(
        engine = engine,
        isActive = isActive,
        videoSize = videoSize,
        contentScale = contentScale,
        keepPreviousFrame = keepPreviousFrame,
        rotation = rotation,
        modifier = Modifier.fillMaxSize(),
    )
    return isActive
}

/** The shape of the inline video area. Fullscreen ignores it. */
@Immutable
public sealed interface VideoAspectRatio {

    /** A fixed width-to-height ratio. */
    public data class Fixed(val ratio: Float) : VideoAspectRatio {
        init {
            require(ratio > 0f) { "ratio must be positive" }
        }
    }

    /** Follows the video's native size, or 16:9 until it is known. */
    public data object Native : VideoAspectRatio

    public companion object {
        public val Ratio16x9: VideoAspectRatio = Fixed(16f / 9f)
        public val Ratio4x3: VideoAspectRatio = Fixed(4f / 3f)
        public val Ratio1x1: VideoAspectRatio = Fixed(1f)
        public val Ratio9x16: VideoAspectRatio = Fixed(9f / 16f)
    }
}

/** How the video fills the surface's area. */
public enum class VideoContentScale {
    /** Show the whole video, letterboxed where the shapes differ. */
    Fit,

    /** Fill the area and crop the overflow. */
    Crop,

    /** Stretch to the area. */
    Fill,
}

/**
 * The video size used for layout. Aspect changes under 1 % (such as HLS quality switches between
 * renditions like 512x288 and 848x480) are ignored, and the last size is kept while none is known,
 * so the native surface is not resized needlessly. Resizing it can show a stretched frame.
 */
@Composable
private fun rememberStableVideoSize(videoSize: IntSize?): IntSize? {
    val holder = remember { StableVideoSize() }
    return holder.update(videoSize)
}

private class StableVideoSize {
    private var current: IntSize? = null

    fun update(size: IntSize?): IntSize? {
        val ratio = size?.ratio ?: return current
        val currentRatio = current?.ratio
        if (currentRatio == null || abs(ratio / currentRatio - 1f) > MaxAspectDeformation) current = size
        return current
    }
}

private const val DefaultRatio = 16f / 9f

private val DefaultPosterDelay = 1.seconds

private const val MaxAspectDeformation = 0.01f

private val IntSize.ratio: Float?
    get() = if (width > 0 && height > 0) width.toFloat() / height else null
