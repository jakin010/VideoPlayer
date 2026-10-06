package co.liebi.videoplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import co.liebi.videoplayer.core.SwipeDirection
import co.liebi.videoplayer.core.VideoGestureState

/**
 * Default feedback for swiping into or out of fullscreen: a chevron in the swipe's direction in the center,
 * fading in and drifting toward the center as the finger travels. It is fully shown once releasing will act,
 * and fades out when the swipe ends. Place it over the video, for example with `Modifier.matchParentSize()`,
 * with the same [state] as the surface. Custom feedback can read [VideoGestureState.swipeFeedback] instead.
 */
@Composable
public fun SwipeIndicator(
    state: VideoGestureState,
    modifier: Modifier = Modifier,
    colors: PlayerControlsColors = LocalVideoPlayerTheme.current.colors,
) {
    val feedback = state.swipeFeedback
    // Keeps showing the last direction while fading out.
    val last = remember { arrayOfNulls<SwipeDirection>(1) }
    if (feedback != null) last[0] = feedback.direction
    val direction = last[0]
    // Eased, so the chevron appears gradually rather than jumping with every move of the finger.
    val progress by animateFloatAsState(feedback?.progress ?: 0f, tween(IndicatorEasingMillis))
    if (direction == null || progress == 0f) return

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    alpha = progress
                    val scale = 0.8f + 0.2f * progress
                    scaleX = scale
                    scaleY = scale
                    // Starts behind the swipe's direction and arrives in the center at full progress.
                    val drift = (1f - progress) * IndicatorDrift.toPx()
                    translationY = if (direction == SwipeDirection.Up) drift else -drift
                }
                .size(IndicatorSize)
                .background(colors.buttonContainerColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val icons = LocalVideoPlayerTheme.current.icons
            PlayerIcon(if (direction == SwipeDirection.Up) icons.swipeUp else icons.swipeDown, colors.contentColor)
        }
    }
}

private val IndicatorSize = 40.dp
private val IndicatorDrift = 16.dp
private const val IndicatorEasingMillis = 150
