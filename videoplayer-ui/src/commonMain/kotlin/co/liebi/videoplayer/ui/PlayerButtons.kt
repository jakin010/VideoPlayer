package co.liebi.videoplayer.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.node.DpTouchBoundsExpansion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.Presentation
import co.liebi.videoplayer.ui.generated.resources.Res
import co.liebi.videoplayer.ui.generated.resources.videoplayer_enter_fullscreen
import co.liebi.videoplayer.ui.generated.resources.videoplayer_exit_fullscreen
import co.liebi.videoplayer.ui.generated.resources.videoplayer_mute
import co.liebi.videoplayer.ui.generated.resources.videoplayer_pause
import co.liebi.videoplayer.ui.generated.resources.videoplayer_play
import co.liebi.videoplayer.ui.generated.resources.videoplayer_replay
import co.liebi.videoplayer.ui.generated.resources.videoplayer_rotate_left
import co.liebi.videoplayer.ui.generated.resources.videoplayer_rotate_right
import co.liebi.videoplayer.ui.generated.resources.videoplayer_unmute
import co.liebi.videoplayer.ui.internal.expandedPointerInput
import org.jetbrains.compose.resources.stringResource

/**
 * Toggles play intent. Shows pause while play intent is set (also while buffering), otherwise play.
 * When the item has ended (only possible with auto replay off) it shows replay and restarts from the beginning.
 */
@Composable
public fun PlayPauseButton(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility? = null,
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
) {
    val state by controller.state.collectAsState()
    val mode = when {
        state.status == PlaybackStatus.Ended -> PlayPauseMode.Replay
        state.playWhenReady -> PlayPauseMode.Pause
        else -> PlayPauseMode.Play
    }
    ControlButton(
        onClick = {
            visibility?.onInteraction()
            if (controller.state.value.playWhenReady) controller.pause() else controller.play()
        },
        contentDescription = stringResource(
            when (mode) {
                PlayPauseMode.Play -> Res.string.videoplayer_play
                PlayPauseMode.Pause -> Res.string.videoplayer_pause
                PlayPauseMode.Replay -> Res.string.videoplayer_replay
            },
        ),
        colors = colors,
        modifier = modifier,
    ) {
        val icons = LocalVideoPlayerTheme.current.icons
        PlayerIcon(
            icon = when (mode) {
                PlayPauseMode.Play -> icons.play
                PlayPauseMode.Pause -> icons.pause
                PlayPauseMode.Replay -> icons.replay
            },
            color = colors.contentColor,
        )
    }
}

private enum class PlayPauseMode { Play, Pause, Replay }

/** Toggles mute. Muting keeps the volume, so unmuting restores it. */
@Composable
public fun MuteButton(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility? = null,
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
) {
    val state by controller.state.collectAsState()
    ControlButton(
        onClick = {
            visibility?.onInteraction()
            controller.setMuted(!controller.state.value.isMuted)
        },
        contentDescription = stringResource(if (state.isMuted) Res.string.videoplayer_unmute else Res.string.videoplayer_mute),
        colors = colors,
        modifier = modifier,
    ) {
        val icons = LocalVideoPlayerTheme.current.icons
        PlayerIcon(if (state.isMuted) icons.soundOff else icons.soundOn, colors.contentColor)
    }
}

/**
 * Enters fullscreen, or exits it while fullscreen (§12). Hidden unless `PlayerConfiguration.fullscreenEnabled`
 * is on for the player, because entering would do nothing.
 */
@Composable
public fun FullscreenButton(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility? = null,
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
) {
    val state by controller.state.collectAsState()
    val isFullscreen = state.presentation == Presentation.Fullscreen
    if (!isFullscreen && !state.isFullscreenAvailable) return
    ControlButton(
        onClick = {
            visibility?.onInteraction()
            if (controller.state.value.presentation == Presentation.Fullscreen) controller.exitFullscreen() else controller.enterFullscreen()
        },
        contentDescription = stringResource(
            if (isFullscreen) Res.string.videoplayer_exit_fullscreen else Res.string.videoplayer_enter_fullscreen,
        ),
        colors = colors,
        modifier = modifier,
    ) {
        val icons = LocalVideoPlayerTheme.current.icons
        PlayerIcon(if (isFullscreen) icons.exitFullscreen else icons.enterFullscreen, colors.contentColor)
    }
}

/**
 * Turns the fullscreen view 90 degrees counterclockwise, for users who locked their screen's rotation.
 * The default fullscreen controls place it next to the exit-fullscreen button.
 */
@Composable
public fun RotateLeftButton(
    rotation: FullscreenViewRotation,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility? = null,
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
) {
    RotateButton(clockwise = false, rotation, modifier, visibility, colors)
}

/** Turns the fullscreen view 90 degrees clockwise. See [RotateLeftButton]. */
@Composable
public fun RotateRightButton(
    rotation: FullscreenViewRotation,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility? = null,
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
) {
    RotateButton(clockwise = true, rotation, modifier, visibility, colors)
}

@Composable
private fun RotateButton(
    clockwise: Boolean,
    rotation: FullscreenViewRotation,
    modifier: Modifier,
    visibility: ControlsVisibility?,
    colors: PlayerControlsColors,
) {
    val icons = LocalVideoPlayerTheme.current.icons
    ControlButton(
        onClick = {
            visibility?.onInteraction()
            if (clockwise) rotation.rotateRight() else rotation.rotateLeft()
        },
        contentDescription = stringResource(if (clockwise) Res.string.videoplayer_rotate_right else Res.string.videoplayer_rotate_left),
        colors = colors,
        modifier = modifier,
    ) {
        PlayerIcon(if (clockwise) icons.rotateRight else icons.rotateLeft, colors.contentColor)
    }
}

/**
 * A round, translucent button: the shared shape of the default controls. Its touch target is
 * [PlayerControlsDefaults.ButtonTouchTarget] even though the circle is smaller.
 */
@Composable
internal fun ControlButton(
    onClick: () -> Unit,
    contentDescription: String,
    colors: PlayerControlsColors,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val interactionSource = remember { MutableInteractionSource() }
    val touchExpansion = ((PlayerControlsDefaults.ButtonTouchTarget - PlayerControlsDefaults.ButtonSize) / 2).coerceAtLeast(0.dp)

    Box(
        modifier = modifier
            .size(PlayerControlsDefaults.ButtonSize)
            .expandedPointerInput(
                key = interactionSource,
                expansion = DpTouchBoundsExpansion(touchExpansion, touchExpansion, touchExpansion, touchExpansion),
            ) {
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interactionSource.emit(press)
                        interactionSource.emit(if (tryAwaitRelease()) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    },
                    onTap = { currentOnClick() },
                )
            }
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                onClick {
                    currentOnClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Clipped separately so the press indication stays inside the circle.
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(colors.buttonContainerColor)
                .indication(interactionSource, LocalIndication.current),
        )
        content()
    }
}
