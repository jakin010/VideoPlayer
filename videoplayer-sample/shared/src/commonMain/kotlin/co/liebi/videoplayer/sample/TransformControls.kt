package co.liebi.videoplayer.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.liebi.videoplayer.core.VideoTransform
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Sliders for the inline player's pan, zoom and rotation. Off, the player gets no transform at all, which keeps
 * the cheaper SurfaceView on Android. Pick the 1:1 aspect ratio to see the bounds at work: a 16:9 video then
 * pans sideways only, and turns only once zoomed in.
 */
@Composable
internal fun TransformControls(transform: VideoTransform?, onChange: (VideoTransform?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = transform != null, onCheckedChange = { onChange(if (it) VideoTransform() else null) })
            Text("Transform")
            if (transform != null) {
                TextButton(onClick = { onChange(VideoTransform(allowOutOfBounds = transform.allowOutOfBounds)) }) { Text("Reset") }
            }
        }
        if (transform == null) return@Column
        // Beyond 0..1 to try out of bounds; within bounds those values count as the nearest end.
        TransformSlider("Pan X", transform.panX, PanRange, fraction(transform.panX)) { onChange(transform.copy(panX = it)) }
        TransformSlider("Pan Y", transform.panY, PanRange, fraction(transform.panY)) { onChange(transform.copy(panY = it)) }
        TransformSlider("Zoom", transform.zoom, 0.25f..4f, "${fraction(transform.zoom)}x") { onChange(transform.copy(zoom = it)) }
        TransformSlider("Rotation", transform.rotation, -180f..180f, "${transform.rotation.roundToInt()}°") {
            onChange(transform.copy(rotation = it))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(
                checked = transform.allowOutOfBounds,
                onCheckedChange = { onChange(transform.copy(allowOutOfBounds = it)) },
            )
            Text("Allow out of bounds")
        }
    }
}

@Composable
private fun TransformSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(72.dp), fontSize = 14.sp)
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(valueText, Modifier.width(56.dp), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

private fun fraction(value: Float): String {
    val hundredths = (value * 100).roundToInt()
    val sign = if (hundredths < 0) "-" else ""
    val absolute = abs(hundredths)
    return "$sign${absolute / 100}.${(absolute % 100).toString().padStart(2, '0')}"
}

/** Centered on 0.5, the centered video. */
private val PanRange = -1f..2f
