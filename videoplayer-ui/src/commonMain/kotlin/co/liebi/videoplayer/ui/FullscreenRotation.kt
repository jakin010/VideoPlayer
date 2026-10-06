package co.liebi.videoplayer.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/**
 * Turning the fullscreen view in 90-degree steps. It works while the user has locked the screen's rotation,
 * because the view turns, not the screen. Pass `null` to [FullscreenVideoPlayer] to turn it off.
 *
 * @param showButtons Rotate-left and rotate-right buttons next to the exit-fullscreen button.
 * @param autoRotate On entering fullscreen, turn the view so the video's long side follows the screen's long
 *   side. Square videos, and videos whose shape already matches the screen, are not turned. When the screen
 *   itself rotates or the video's shape changes, the view is turned again to match.
 */
@Immutable
public data class FullscreenRotation(
    val showButtons: Boolean = true,
    val autoRotate: Boolean = true,
)

/** How far the fullscreen view is turned. Custom controls get it from [LocalFullscreenViewRotation]. */
@Stable
public class FullscreenViewRotation internal constructor(initialDegrees: Int = 0) {

    /** Clockwise: 0, 90, 180 or 270. */
    public var degrees: Int by mutableIntStateOf(initialDegrees.mod(360))
        internal set

    /** Turns the view 90 degrees counterclockwise. */
    public fun rotateLeft() {
        degrees = (degrees + 270) % 360
    }

    /** Turns the view 90 degrees clockwise. */
    public fun rotateRight() {
        degrees = (degrees + 90) % 360
    }
}

/** The rotation of the fullscreen view, inside a [FullscreenVideoPlayer] with rotation on; `null` elsewhere. */
public val LocalFullscreenViewRotation: ProvidableCompositionLocal<FullscreenViewRotation?> = staticCompositionLocalOf { null }

/** The video's shape, as far as turning the view is concerned. */
internal enum class VideoShape { Landscape, Portrait, Square }

/** `null` while the size is unknown. */
internal fun videoShapeOf(size: IntSize?): VideoShape? {
    if (size == null || size.width <= 0 || size.height <= 0) return null
    val ratio = size.width.toFloat() / size.height
    return when {
        abs(ratio - 1f) < SquareTolerance -> VideoShape.Square
        ratio > 1f -> VideoShape.Landscape
        else -> VideoShape.Portrait
    }
}

/**
 * The clockwise turn that makes the video's long side follow the screen's long side: 0 when the video is
 * square, its shape is unknown, or it already matches the screen. Otherwise the view turns toward the way the
 * device is held, so the video is upright right away: counterclockwise when the device's top points right
 * ([deviceTurnedRight]), clockwise otherwise, which suits turning the device to the left.
 */
internal fun autoRotation(screenIsLandscape: Boolean, shape: VideoShape?, deviceTurnedRight: Boolean?): Int {
    if (shape == null || shape == VideoShape.Square) return 0
    if ((shape == VideoShape.Landscape) == screenIsLandscape) return 0
    return if (deviceTurnedRight == true) 270 else 90
}

/** Videos within 10 % of square, such as 1:1, are not turned. */
private const val SquareTolerance = 0.1f
