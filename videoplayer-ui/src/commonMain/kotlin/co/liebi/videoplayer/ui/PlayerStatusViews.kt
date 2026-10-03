package co.liebi.videoplayer.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.liebi.videoplayer.ui.generated.resources.Res
import co.liebi.videoplayer.ui.generated.resources.videoplayer_error_generic
import co.liebi.videoplayer.ui.generated.resources.videoplayer_loading
import co.liebi.videoplayer.ui.generated.resources.videoplayer_retry
import org.jetbrains.compose.resources.stringResource

/**
 * Default loading content: a spinner on the controls' translucent circle. `VideoPlayer` shows it while
 * preparing, buffering or seeking, once that has lasted a moment. Announced to screen readers.
 */
@Composable
public fun LoadingIndicator(
    modifier: Modifier = Modifier,
    colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
) {
    val label = stringResource(Res.string.videoplayer_loading)
    val rotation = rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
    )
    Box(
        modifier = modifier
            .size(PlayerControlsDefaults.ButtonSize + 12.dp)
            .clip(CircleShape)
            .background(colors.buttonContainerColor)
            .semantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        // Rotated in the draw phase only, so the spinner never recomposes.
        Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = rotation.value }) {
            drawArc(
                color = colors.contentColor,
                startAngle = 0f,
                sweepAngle = 270f,
                useCenter = false,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** Default error content: "Unable to play video" and a Retry button. Announced to screen readers. */
@Composable
public fun ErrorPanel(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    colors: PlayerControlsColors = PlayerControlsDefaults.colors(),
) {
    val textStyle = TextStyle(color = colors.contentColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    Column(
        modifier = modifier
            .background(colors.buttonContainerColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BasicText(stringResource(Res.string.videoplayer_error_generic), style = textStyle)
        BasicText(
            text = stringResource(Res.string.videoplayer_retry),
            style = textStyle.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.buttonContainerColor)
                .clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}
