package co.liebi.videoplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.liebi.videoplayer.ui.generated.resources.Res
import co.liebi.videoplayer.ui.generated.resources.ic_chevron_left
import co.liebi.videoplayer.ui.generated.resources.ic_chevron_right
import co.liebi.videoplayer.ui.generated.resources.videoplayer_seek_back
import co.liebi.videoplayer.ui.generated.resources.videoplayer_seek_forward
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Default double-tap seek feedback: the direction and running total (for example "+30 s") on the tapped
 * side. Place it over the video, for example with `Modifier.matchParentSize()`. Custom feedback can read
 * [VideoGestures.seekFeedback] instead. Never mirrored for right-to-left layouts (§11).
 */
@Composable
public fun SeekIndicator(
    gestures: VideoGestures,
    modifier: Modifier = Modifier,
    colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
) {
    val feedback = gestures.seekFeedback
    // Keeps showing the last value while fading out.
    val last = remember { arrayOfNulls<SeekFeedback>(1) }
    if (feedback != null) last[0] = feedback
    val shown = feedback ?: last[0]

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(modifier.fillMaxSize()) {
            val isBack = shown?.direction == SeekDirection.Back
            AnimatedVisibility(
                visible = feedback != null,
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .align(if (isBack) Alignment.CenterStart else Alignment.CenterEnd),
                enter = fadeIn(tween(100)),
                exit = fadeOut(tween(250)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (shown != null) SeekPill(shown, colors)
                }
            }
        }
    }
}

@Composable
private fun SeekPill(feedback: SeekFeedback, colors: PlayerControlsColors) {
    val seconds = feedback.amount.inWholeSeconds.toInt()
    val isBack = feedback.direction == SeekDirection.Back
    val text = stringResource(if (isBack) Res.string.videoplayer_seek_back else Res.string.videoplayer_seek_forward, seconds)
    Row(
        modifier = Modifier
            .background(colors.buttonContainerColor, RoundedCornerShape(percent = 50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = ColorFilter.tint(colors.contentColor)
        if (isBack) {
            Image(painterResource(Res.drawable.ic_chevron_left), null, Modifier.size(5.5.dp, 11.dp), colorFilter = tint)
        }
        BasicText(text, style = TextStyle(color = colors.contentColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        if (!isBack) {
            Image(painterResource(Res.drawable.ic_chevron_right), null, Modifier.size(6.5.dp, 11.dp), colorFilter = tint)
        }
    }
}
