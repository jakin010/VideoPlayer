package co.liebi.videoplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.PlayerController

/**
 * The default controls: play/pause and mute buttons above a full-width scrubber.
 *
 * Place it over the bottom of a `VideoPlayerSurface`. Every part is also available on its own
 * ([PlayPauseButton], [MuteButton], [PlayerScrubber]), so custom layouts can reuse them.
 * Media controls never mirror in right-to-left layouts (§11).
 *
 * @param visibility Auto-hide state; the controls fade with it. Without it they are always visible,
 *   which suits controls placed below the video.
 */
@Composable
public fun PlayerControls(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility? = null,
    colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
    contentPadding: PaddingValues = PlayerControlsDefaults.ContentPadding,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        if (visibility == null) {
            ControlsContent(controller, modifier, null, colors, contentPadding)
        } else {
            AnimatedVisibility(
                visible = visibility.isVisible,
                modifier = modifier,
                enter = ControlsEnter,
                exit = ControlsExit,
            ) {
                ControlsContent(controller, Modifier, visibility, colors, contentPadding)
            }
        }
    }
}

@Composable
private fun ControlsContent(
    controller: PlayerController,
    modifier: Modifier,
    visibility: ControlsVisibility?,
    colors: PlayerControlsColors,
    contentPadding: PaddingValues,
) {
    Column(modifier.fillMaxWidth().padding(contentPadding)) {
        Row(horizontalArrangement = Arrangement.spacedBy(PlayerControlsDefaults.ButtonSpacing)) {
            PlayPauseButton(controller, visibility = visibility, colors = colors)
            MuteButton(controller, visibility = visibility, colors = colors)
        }
        Spacer(Modifier.height(PlayerControlsDefaults.ScrubberSpacing))
        PlayerScrubber(controller, Modifier.fillMaxWidth(), visibility = visibility, colors = colors)
    }
}

/** How auto-hidden controls appear and disappear; shared by every control that follows a [ControlsVisibility]. */
internal val ControlsEnter = fadeIn(tween(150))
internal val ControlsExit = fadeOut(tween(250))

/** Colors of the default controls. */
@Immutable
public data class PlayerControlsColors(
    /** Button icons. */
    val contentColor: Color,
    /** The translucent circle behind each button. */
    val buttonContainerColor: Color,
    val playedTrackColor: Color,
    val bufferedTrackColor: Color,
    val remainingTrackColor: Color,
    val thumbColor: Color,
)

public object PlayerControlsDefaults {
    /** Diameter of the round buttons. */
    public val ButtonSize: Dp = 28.dp

    /** Touch target of each button; it extends past the visible circle without taking layout space. */
    public val ButtonTouchTarget: Dp = 48.dp
    public val ButtonSpacing: Dp = 9.dp

    /** Space between the buttons and the scrubber's thumb; the track itself sits 8 dp below the buttons. */
    public val ScrubberSpacing: Dp = 5.dp
    public val TrackHeight: Dp = 8.dp
    public val ThumbSize: Dp = 14.dp

    /** Minimum touch target of the scrubber and its handle (§11). */
    public val ScrubberTouchTarget: Dp = 48.dp

    /** Keeps the track 8 dp from the sides and 6 dp from the bottom; the thumb overhangs the track by 3 dp. */
    public val ContentPadding: PaddingValues = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 3.dp)

    /** White content on translucent black, readable on any video. */
    public fun colors(
        contentColor: Color = Color.White,
        buttonContainerColor: Color = Color.Black.copy(alpha = 0.45f),
        playedTrackColor: Color = Color.White,
        bufferedTrackColor: Color = Color.White.copy(alpha = 0.25f),
        remainingTrackColor: Color = Color.Black.copy(alpha = 0.3f),
        thumbColor: Color = Color.White,
    ): PlayerControlsColors = PlayerControlsColors(
        contentColor = contentColor,
        buttonContainerColor = buttonContainerColor,
        playedTrackColor = playedTrackColor,
        bufferedTrackColor = bufferedTrackColor,
        remainingTrackColor = remainingTrackColor,
        thumbColor = thumbColor,
    )
}
