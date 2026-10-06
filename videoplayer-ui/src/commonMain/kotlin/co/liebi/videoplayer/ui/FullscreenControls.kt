package co.liebi.videoplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import co.liebi.videoplayer.core.PlayerController

/**
 * The fullscreen controls: a [FullscreenButton], with [RotateLeftButton] and [RotateRightButton] before it
 * while a fullscreen view can be turned. Place it over the top right of the video, as the default controls
 * do; the counterpart of [PlayerControls] at the bottom. The order never mirrors in right-to-left layouts (§11).
 *
 * @param visibility Auto-hide state; the controls fade with it. Without it they are always visible.
 * @param rotation The fullscreen view's rotation, which shows the rotate buttons. Inside a
 *   [FullscreenVideoPlayer] it comes from [LocalFullscreenViewRotation]; elsewhere it is `null`.
 */
@Composable
public fun FullscreenControls(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility? = null,
    rotation: FullscreenViewRotation? = LocalFullscreenViewRotation.current,
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
    contentPadding: PaddingValues = PlayerControlsDefaults.FullscreenControlsPadding,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        if (visibility == null) {
            FullscreenControlsRow(controller, modifier.padding(contentPadding), null, rotation, colors)
        } else {
            AnimatedVisibility(
                visible = visibility.isVisible,
                modifier = modifier,
                enter = ControlsEnter,
                exit = ControlsExit,
            ) {
                FullscreenControlsRow(controller, Modifier.padding(contentPadding), visibility, rotation, colors)
            }
        }
    }
}

@Composable
private fun FullscreenControlsRow(
    controller: PlayerController,
    modifier: Modifier,
    visibility: ControlsVisibility?,
    rotation: FullscreenViewRotation?,
    colors: PlayerControlsColors,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(PlayerControlsDefaults.ButtonSpacing)) {
        if (rotation != null) {
            RotateLeftButton(rotation, visibility = visibility, colors = colors)
            RotateRightButton(rotation, visibility = visibility, colors = colors)
        }
        FullscreenButton(controller, visibility = visibility, colors = colors)
    }
}
