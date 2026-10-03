package co.liebi.videoplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.node.DpTouchBoundsExpansion
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.PlaybackStatus
import co.liebi.videoplayer.core.PlayerController
import co.liebi.videoplayer.core.Presentation
import co.liebi.videoplayer.ui.generated.resources.Res
import co.liebi.videoplayer.ui.generated.resources.ic_fullscreen
import co.liebi.videoplayer.ui.generated.resources.ic_fullscreen_exit
import co.liebi.videoplayer.ui.generated.resources.ic_pause
import co.liebi.videoplayer.ui.generated.resources.ic_play
import co.liebi.videoplayer.ui.generated.resources.ic_replay
import co.liebi.videoplayer.ui.generated.resources.ic_sound_off
import co.liebi.videoplayer.ui.generated.resources.ic_sound_on
import co.liebi.videoplayer.ui.generated.resources.videoplayer_enter_fullscreen
import co.liebi.videoplayer.ui.generated.resources.videoplayer_exit_fullscreen
import co.liebi.videoplayer.ui.generated.resources.videoplayer_mute
import co.liebi.videoplayer.ui.generated.resources.videoplayer_pause
import co.liebi.videoplayer.ui.generated.resources.videoplayer_play
import co.liebi.videoplayer.ui.generated.resources.videoplayer_replay
import co.liebi.videoplayer.ui.generated.resources.videoplayer_unmute
import co.liebi.videoplayer.ui.internal.expandedPointerInput
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
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
    colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
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
        when (mode) {
            // Nudged right so the triangle looks centered in the circle.
            PlayPauseMode.Play -> ControlIcon(Res.drawable.ic_play, 10.dp, 10.dp, colors, Modifier.offset(x = 0.65.dp))
            PlayPauseMode.Pause -> ControlIcon(Res.drawable.ic_pause, 9.25.dp, 9.25.dp, colors)
            PlayPauseMode.Replay -> ControlIcon(Res.drawable.ic_replay, 11.5.dp, 11.5.dp, colors)
        }
    }
}

private enum class PlayPauseMode { Play, Pause, Replay }

/** Toggles mute. Muting keeps the volume, so unmuting restores it. */
@Composable
public fun MuteButton(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility? = null,
    colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
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
        if (state.isMuted) {
            ControlIcon(Res.drawable.ic_sound_off, 11.5.dp, 10.5.dp, colors)
        } else {
            ControlIcon(Res.drawable.ic_sound_on, 13.dp, 10.5.dp, colors)
        }
    }
}

/**
 * Enters fullscreen, or exits it while fullscreen (§12). Hidden when no `FullscreenHost` is placed for the
 * player's coordinator, because entering would do nothing.
 */
@Composable
public fun FullscreenButton(
    controller: PlayerController,
    modifier: Modifier = Modifier,
    visibility: ControlsVisibility? = null,
    colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
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
        ControlIcon(if (isFullscreen) Res.drawable.ic_fullscreen_exit else Res.drawable.ic_fullscreen, 11.dp, 11.dp, colors)
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

@Composable
private fun ControlIcon(
    resource: DrawableResource,
    width: Dp,
    height: Dp,
    colors: PlayerControlsColors,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(resource),
        contentDescription = null,
        modifier = modifier.size(width, height),
        colorFilter = ColorFilter.tint(colors.contentColor),
    )
}
