package co.liebi.videoplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.ui.generated.resources.Res
import co.liebi.videoplayer.ui.generated.resources.ic_chevron_down
import co.liebi.videoplayer.ui.generated.resources.ic_chevron_left
import co.liebi.videoplayer.ui.generated.resources.ic_chevron_right
import co.liebi.videoplayer.ui.generated.resources.ic_chevron_up
import co.liebi.videoplayer.ui.generated.resources.ic_fullscreen
import co.liebi.videoplayer.ui.generated.resources.ic_fullscreen_exit
import co.liebi.videoplayer.ui.generated.resources.ic_pause
import co.liebi.videoplayer.ui.generated.resources.ic_play
import co.liebi.videoplayer.ui.generated.resources.ic_replay
import co.liebi.videoplayer.ui.generated.resources.ic_rotate_left
import co.liebi.videoplayer.ui.generated.resources.ic_rotate_right
import co.liebi.videoplayer.ui.generated.resources.ic_sound_off
import co.liebi.videoplayer.ui.generated.resources.ic_sound_on
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * The look of the default player UI: the colors and icons of every control, indicator and the fullscreen
 * player. Provide it with [ProvideVideoPlayerTheme]. A different theme applies right away, so an app can switch
 * themes at any time, for example with its own light and dark themes:
 *
 * ```
 * val theme = if (isSystemInDarkTheme()) darkPlayerTheme else lightPlayerTheme
 * ProvideVideoPlayerTheme(theme) {
 *     AppContent() // its players, and wherever it shows FullscreenVideoPlayer
 * }
 * ```
 *
 * A `colors` argument passed to a single control still wins over the theme's colors.
 */
@Immutable
public data class VideoPlayerTheme(
    val colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
    val icons: VideoPlayerIcons = VideoPlayerIcons(),
)

/** The theme the default player UI reads. Provide it with [ProvideVideoPlayerTheme]. */
public val LocalVideoPlayerTheme: ProvidableCompositionLocal<VideoPlayerTheme> = staticCompositionLocalOf { VideoPlayerTheme() }

/**
 * Applies [theme] to the player UI in [content]. Place it around both the players and wherever the app shows
 * [FullscreenVideoPlayer], so fullscreen looks the same. Nest another one to style a single player differently.
 */
@Composable
public fun ProvideVideoPlayerTheme(theme: VideoPlayerTheme, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalVideoPlayerTheme provides theme, content = content)
}

/**
 * The icons of the default player UI. Replace some and keep the rest with `copy`:
 *
 * ```
 * VideoPlayerIcons().copy(play = VideoPlayerIcon(Res.drawable.my_play), pause = VideoPlayerIcon(Res.drawable.my_pause))
 * ```
 */
@Immutable
public data class VideoPlayerIcons(
    val play: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_play, null, null, 10.dp, 10.dp, tinted = true, offsetX = 0.65.dp),
    val pause: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_pause, 9.25.dp, 9.25.dp),
    /** Shown instead of play once the item has ended. */
    val replay: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_replay, 11.5.dp, 11.5.dp),
    /** Shown while the sound is on; tapping mutes. */
    val soundOn: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_sound_on, 13.dp, 10.5.dp),
    /** Shown while muted; tapping unmutes. */
    val soundOff: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_sound_off, 11.5.dp, 10.5.dp),
    val enterFullscreen: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_fullscreen, 11.dp, 11.dp),
    val exitFullscreen: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_fullscreen_exit, 11.dp, 11.dp),
    val rotateLeft: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_rotate_left, 13.dp, 13.dp),
    val rotateRight: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_rotate_right, 13.dp, 13.dp),
    /** Beside the amount in the double-tap seek feedback. */
    val seekBack: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_chevron_left, 5.5.dp, 11.dp),
    val seekForward: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_chevron_right, 6.5.dp, 11.dp),
    /** The swipe feedback while swiping up into fullscreen. */
    val swipeUp: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_chevron_up, 18.dp, 10.5.dp),
    /** The swipe feedback while swiping down out of fullscreen. */
    val swipeDown: VideoPlayerIcon = VideoPlayerIcon(Res.drawable.ic_chevron_down, 18.dp, 10.5.dp),
)

/**
 * One icon of the player UI: a Compose resource, an [ImageVector] or any [Painter], drawn at [width] by
 * [height], fitted and centered in its place (in a button, its circle).
 *
 * @param tinted Draw the icon in the theme's content color, like the default icons. `false` keeps the
 *   icon's own colors.
 */
@Immutable
@ConsistentCopyVisibility
public data class VideoPlayerIcon internal constructor(
    private val resource: DrawableResource?,
    private val vector: ImageVector?,
    private val painter: Painter?,
    val width: Dp,
    val height: Dp,
    val tinted: Boolean,
    /** Moves the icon off center, for optical centering such as the default play triangle's. */
    internal val offsetX: Dp = 0.dp,
) {
    public constructor(resource: DrawableResource, width: Dp = DefaultIconSize, height: Dp = width, tinted: Boolean = true) :
        this(resource, null, null, width, height, tinted)

    public constructor(vector: ImageVector, width: Dp = DefaultIconSize, height: Dp = width, tinted: Boolean = true) :
        this(null, vector, null, width, height, tinted)

    /** [painter] must be remembered by the caller, as `painterResource` and `rememberVectorPainter` do. */
    public constructor(painter: Painter, width: Dp = DefaultIconSize, height: Dp = width, tinted: Boolean = true) :
        this(null, null, painter, width, height, tinted)

    @Composable
    internal fun painter(): Painter = when {
        resource != null -> painterResource(resource)
        vector != null -> rememberVectorPainter(vector)
        else -> painter!!
    }
}

private val DefaultIconSize = 12.dp

/** Draws [icon] in [color], unless the icon keeps its own colors. */
@Composable
internal fun PlayerIcon(icon: VideoPlayerIcon, color: Color, modifier: Modifier = Modifier) {
    Image(
        painter = icon.painter(),
        contentDescription = null,
        modifier = modifier.offset(x = icon.offsetX).size(icon.width, icon.height),
        colorFilter = if (icon.tinted) ColorFilter.tint(color) else null,
    )
}
